package com.thomaswcode.whatsappscheduler.sending

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Notifications that need the user: a one-tap manual send when automation failed (so the message
 * is delayed rather than lost), a "check the chat" notice when it may have been sent, and the
 * notice that PIN entry is paused. There is at most one per schedule, and it is removed once the
 * schedule is sent, edited or deleted (B10).
 */
class FallbackNotifier(private val context: Context, private val notifications: Notifications) {

    fun notifyFailed(schedule: Schedule, occurrenceAtMillis: Long?, reason: String) = show(
        schedule,
        title = "Tap to send to ${schedule.targetName}",
        text = "Auto-send${forOccurrence(occurrenceAtMillis)} failed ($reason). Tap to open the chat with the message ready.",
        tap = manualSend(schedule),
    )

    /** Offers to open the chat, not to send: the message may already be there (B02). */
    fun notifyUncertain(schedule: Schedule, occurrenceAtMillis: Long?, reason: String) = show(
        schedule,
        title = "May have been sent to ${schedule.targetName}",
        text = "Auto-send${forOccurrence(occurrenceAtMillis)} was interrupted after tapping Send ($reason). Check the chat before sending it again.",
        tap = checkChat(schedule),
    )

    /** An occurrence the alarm could not fire for, found when alarms were re-armed (interim for B09). */
    fun notifyMissed(schedule: Schedule, occurrenceAtMillis: Long) = show(
        schedule,
        title = "Missed: message to ${schedule.targetName} (${stamp(occurrenceAtMillis)})",
        text = "It wasn't sent because the phone was off or the app was stopped at that time. Tap to open the chat with the message ready.",
        tap = manualSend(schedule),
    )

    fun notifyPinPaused(reason: String) {
        val open = PendingIntent.getActivity(
            context, REQUEST_PIN_PAUSED,
            MainActivity.intent(context, MainActivity.DESTINATION_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notifications.show(Notifications.ID_PIN_PAUSED, notifications.fallback("Automatic sending paused", reason, open))
    }

    fun cancelPinPaused() = notifications.cancel(Notifications.ID_PIN_PAUSED)

    /** Removes the schedule's prompt, which is stale once it has been sent, edited or deleted (B10). */
    fun cancel(scheduleId: Long) = notifications.cancel(Notifications.ID_FALLBACK_BASE + scheduleId.toInt())

    private fun show(schedule: Schedule, title: String, text: String, tap: PendingIntent) =
        notifications.show(Notifications.ID_FALLBACK_BASE + schedule.id.toInt(), notifications.fallback(title, text, tap))

    private fun manualSend(schedule: Schedule): PendingIntent = activity(schedule, FallbackActivity.manualSend(context, schedule))

    private fun checkChat(schedule: Schedule): PendingIntent = activity(schedule, FallbackActivity.checkChat(context, schedule))

    private fun activity(schedule: Schedule, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context, schedule.id.toInt(), intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun forOccurrence(millis: Long?): String = millis?.let { " for ${stamp(it)}" } ?: ""

    private fun stamp(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(stampFormat)

    private companion object {
        val stampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
        const val REQUEST_PIN_PAUSED = -2
    }
}
