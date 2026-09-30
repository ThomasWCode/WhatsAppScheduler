package com.thomaswcode.whatsappscheduler.sending

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.data.OccurrenceRun
import com.thomaswcode.whatsappscheduler.data.db.Attention
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.PlainResult
import com.thomaswcode.whatsappscheduler.domain.Preflight
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.ui.health.HealthItem
import com.thomaswcode.whatsappscheduler.ui.MainActivity
import java.time.Instant
import java.util.Date
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Needs your attention" (features F3): one channel and one vocabulary for everything that needs the
 * user, each with the buttons its situation calls for. There is at most one per schedule, and it goes
 * once it is resolved: from its own buttons, from the list, or by a later send (B10).
 */
class FallbackNotifier(private val context: Context, private val notifications: Notifications) {

    /** Failed before Send was tapped, after every retry: the message is delayed, not lost. */
    fun notifyFailed(schedule: Schedule, occurrenceAtMillis: Long?, reason: String) = show(
        schedule,
        title = context.getString(R.string.notif_failed_title, schedule.targetName),
        text = failedText(reason, occurrenceAtMillis),
        tap = manualSend(schedule),
        actions = listOfNotNull(
            context.getString(R.string.action_tap_to_send) to manualSend(schedule),
            postponeFor(schedule),
        ),
    )

    /** Failed after Send was tapped: it may have gone out, so the chat is checked before anything else (B02). */
    fun notifyUncertain(schedule: Schedule, occurrenceAtMillis: Long?) = show(
        schedule,
        title = context.getString(R.string.notif_uncertain_title, schedule.targetName),
        text = occurrenceAtMillis?.let { context.getString(R.string.notif_uncertain_text_for, stamp(it)) }
            ?: context.getString(R.string.notif_uncertain_text),
        tap = checkChat(schedule),
        actions = listOf(
            context.getString(R.string.action_open_chat) to checkChat(schedule),
            context.getString(R.string.action_mark_as_sent) to broadcast(schedule, AttentionActionReceiver.ACTION_MARK_SENT),
            context.getString(R.string.action_send_again) to broadcast(schedule, AttentionActionReceiver.ACTION_SEND_NOW),
        ),
    )

    /** An occurrence the alarm could not fire for in time. */
    fun notifyMissed(schedule: Schedule, occurrenceAtMillis: Long) = show(
        schedule,
        title = context.getString(R.string.notif_missed_title, schedule.targetName, stamp(occurrenceAtMillis)),
        text = context.getString(R.string.notif_missed_text),
        tap = manualSend(schedule),
        actions = listOfNotNull(
            context.getString(R.string.action_send_now) to broadcast(schedule, AttentionActionReceiver.ACTION_SEND_NOW),
            postponeFor(schedule),
            context.getString(R.string.action_discard) to broadcast(schedule, AttentionActionReceiver.ACTION_DISCARD),
        ),
    )

    /** Held until the user picks a new time, e.g. the heads-up's Postpone… was dismissed (features F1). */
    fun notifyHeld(schedule: Schedule) = show(
        schedule,
        title = context.getString(R.string.notif_held_title, schedule.targetName),
        text = firstLine(schedule.message),
        tap = openRow(schedule),
        actions = listOfNotNull(
            postponeFor(schedule),
            context.getString(R.string.action_send_now) to broadcast(schedule, AttentionActionReceiver.ACTION_SEND_NOW),
            context.getString(R.string.action_discard) to broadcast(schedule, AttentionActionReceiver.ACTION_DISCARD),
        ),
    )

    /**
     * [schedule]'s alarm couldn't be set, so its next message won't go by itself. Opening the app sets
     * every alarm again.
     */
    fun notifyUnarmed(schedule: Schedule) = show(
        schedule,
        title = context.getString(R.string.notif_unarmed_title, schedule.targetName),
        text = context.getString(R.string.notif_unarmed_text),
        tap = openRow(schedule),
        actions = emptyList(),
    )

    /**
     * What the pre-flight check found would make the send due at [occurrenceAtMillis] fail (features
     * F5), with the way to fix it. It goes once that send is no longer due (it ran, or the schedule was
     * moved, switched off or deleted), or an hour after it was due.
     */
    fun notifyWillFail(schedule: Schedule, occurrenceAtMillis: Long, problem: HealthItem) {
        val fix = when {
            problem.problem.fixedInAppSettings -> MainActivity.intent(context, MainActivity.DESTINATION_SETTINGS)
            problem.fix != null -> Intent(problem.fix).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else -> MainActivity.rowIntent(context, schedule.id)
        }
        val tap = PendingIntent.getActivity(context, schedule.id.toInt(), fix, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val at = DateFormat.getTimeFormat(context).format(Date(occurrenceAtMillis))
        val untilSendIsOver = occurrenceAtMillis - System.currentTimeMillis() + WILL_FAIL_AFTERWARDS_MS
        notifications.show(
            Notifications.ID_PREFLIGHT_BASE + schedule.id.toInt(),
            notifications.fallback(
                context.getString(R.string.notif_will_fail_title, at, schedule.targetName), problem.message, tap,
                listOfNotNull(problem.fixLabel?.let { context.getString(R.string.action_fix) to tap }),
                lifetimeMs = untilSendIsOver.coerceAtLeast(WILL_FAIL_AFTERWARDS_MS),
                about = Preflight.warningKey(occurrenceAtMillis, schedule.targetName),
            ),
        )
    }

    /**
     * Removes the schedule's pre-flight warning: the check found nothing wrong (any more), or the send it
     * was about is no longer due. One still [unlessAbout] ([Preflight.warningKey]) stays.
     */
    fun cancelWillFail(scheduleId: Long, unlessAbout: String? = null) {
        val id = Notifications.ID_PREFLIGHT_BASE + scheduleId.toInt()
        if (unlessAbout != null && notifications.aboutOf(id) == unlessAbout) return
        notifications.cancel(id)
    }

    fun notifyPinPaused(reason: String) {
        val open = PendingIntent.getActivity(
            context, REQUEST_PIN_PAUSED,
            MainActivity.intent(context, MainActivity.DESTINATION_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notifications.show(
            Notifications.ID_PIN_PAUSED,
            notifications.fallback(
                context.getString(R.string.notif_pin_paused_title), reason, open, listOf(context.getString(R.string.action_open_settings) to open),
            ),
        )
    }

    fun cancelPinPaused() = notifications.cancel(Notifications.ID_PIN_PAUSED)

    /** Whether the schedule's prompt is showing, e.g. to put it back if a delete is undone. */
    fun isShowing(scheduleId: Long): Boolean = notifications.isShowing(Notifications.ID_FALLBACK_BASE + scheduleId.toInt())

    /**
     * Posts again the prompt for what [schedule] waits on, e.g. after Undo on the list put it back.
     * The reason is what its latest scheduled [run] recorded, or for a row from before runs were logged
     * by occurrence, its last result ("FAILED: search: …" gives "search: …").
     */
    fun notifyAttention(schedule: Schedule, run: OccurrenceRun?) {
        val occurrenceAtMillis = run?.occurrenceAtMillis
        val reason = run?.detail ?: schedule.lastResult?.let { it.substringAfter(": ", missingDelimiterValue = it) } ?: "unknown"
        when (schedule.attention) {
            Attention.FAILED -> notifyFailed(schedule, occurrenceAtMillis, reason)
            Attention.UNCERTAIN -> notifyUncertain(schedule, occurrenceAtMillis)
            Attention.MISSED -> notifyMissed(schedule, occurrenceAtMillis ?: schedule.firstAtMillis)
            Attention.HELD -> notifyHeld(schedule)
            null -> Unit
        }
    }

    /**
     * Removes the schedule's prompt, which is stale once it has been sent, edited or deleted (B10). A
     * pre-flight warning is about its next send, not this, and goes with that send (AlarmScheduler).
     */
    fun cancel(scheduleId: Long) = notifications.cancel(Notifications.ID_FALLBACK_BASE + scheduleId.toInt())

    private fun show(schedule: Schedule, title: String, text: String, tap: PendingIntent, actions: List<Pair<String, PendingIntent>>) =
        notifications.show(Notifications.ID_FALLBACK_BASE + schedule.id.toInt(), notifications.fallback(title, text, tap, actions))

    /**
     * Postpone… for a one-off. A series' missed occurrence isn't moved from here: Postpone moves a
     * series' next occurrence, not the one that was missed.
     */
    private fun postponeFor(schedule: Schedule): Pair<String, PendingIntent>? =
        if (schedule.rule != Recurrence.Once) null else context.getString(R.string.action_postpone_ellipsis) to PendingIntent.getActivity(
            context, schedule.id.toInt(), PostponeActivity.intent(context, schedule.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun broadcast(schedule: Schedule, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, schedule.id.toInt(), AttentionActionReceiver.intent(context, action, schedule.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openRow(schedule: Schedule): PendingIntent = PendingIntent.getActivity(
        context, schedule.id.toInt(), MainActivity.rowIntent(context, schedule.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun manualSend(schedule: Schedule): PendingIntent = activity(schedule, FallbackActivity.manualSend(context, schedule))

    private fun checkChat(schedule: Schedule): PendingIntent = activity(schedule, FallbackActivity.checkChat(context, schedule))

    private fun activity(schedule: Schedule, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context, schedule.id.toInt(), intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Why it failed, in words where the step is known (improvements I2), and what to do about it where
     * there is something (a Chat Lock secret code to store, say); the technical text is in Logs.
     */
    private fun failedText(reason: String, occurrenceAtMillis: Long?): String {
        val why = PlainResult.reason(reason) ?: context.getString(R.string.notif_failed_reason, reason)
        val next = PlainResult.nextStep(reason)
        return when {
            occurrenceAtMillis != null && next != null -> context.getString(R.string.notif_failed_text_for_next, why, stamp(occurrenceAtMillis), next)
            occurrenceAtMillis != null -> context.getString(R.string.notif_failed_text_for, why, stamp(occurrenceAtMillis))
            next != null -> context.getString(R.string.notif_failed_text_next, why, next)
            else -> context.getString(R.string.notif_failed_text, why)
        }
    }

    /** In the phone's zone, and its 12/24-hour choice (improvements I1). */
    private fun stamp(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(if (DateFormat.is24HourFormat(context)) stampFormat else stampFormat12)

    private fun firstLine(message: String): String = message.lineSequence().firstOrNull().orEmpty().let { if (it.length > 80) it.take(79) + "…" else it }

    private companion object {
        val stampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
        val stampFormat12: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM h:mm a")
        const val REQUEST_PIN_PAUSED = -2
        /** A pre-flight warning stays this long after its send was due, unless that send removes it first. */
        const val WILL_FAIL_AFTERWARDS_MS = 60 * 60 * 1000L
    }
}
