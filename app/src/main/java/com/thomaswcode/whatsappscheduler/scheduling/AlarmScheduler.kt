package com.thomaswcode.whatsappscheduler.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.Preflight
import com.thomaswcode.whatsappscheduler.sending.SendKind
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import com.thomaswcode.whatsappscheduler.sending.SendService
import com.thomaswcode.whatsappscheduler.sending.SendStateHolder
import com.thomaswcode.whatsappscheduler.ui.MainActivity
import com.thomaswcode.whatsappscheduler.util.TraceLog
import java.time.Instant

/**
 * One exact alarm per enabled schedule. `setAlarmClock` is used because it is the alarm type
 * Samsung's power management is least likely to defer; it falls back to an inexact-while-idle
 * alarm if the user revokes the exact-alarm permission. Each alarm carries the occurrence it is
 * for, which the send queue uses to recognise a request it has already handled (B01).
 */
class AlarmScheduler(
    private val context: Context,
    private val repository: ScheduleRepository,
    private val sendState: SendStateHolder,
    private val log: TraceLog,
    /** Removes a schedule's pre-flight warning unless it is about the send due at the time given (FallbackNotifier). */
    private val clearWarning: (scheduleId: Long, unlessAbout: String?) -> Unit = { _, _ -> },
) : ScheduleAlarms {
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = alarmManager.canScheduleExactAlarms()

    /** Sets or clears the alarm for a single schedule according to its state. */
    override fun sync(schedule: Schedule) = sync(schedule, recovering = false)

    /** [sync]; when [recovering] alarms, a pre-flight check whose time has passed is taken at once. */
    fun sync(schedule: Schedule, recovering: Boolean) {
        val next = schedule.nextAtMillis
        if (!schedule.enabled || next == null) {
            cancel(schedule.id)
            return
        }
        val fire = firePendingIntent(schedule.id, next)
        if (canScheduleExact()) {
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(next, show), fire)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, fire)
        }
        armPreflight(schedule.id, next, recovering)
        // A warning about another time or chat is about a send that is no longer due.
        runCatching { clearWarning(schedule.id, Preflight.warningKey(next, schedule.targetName)) }
    }

    fun cancel(scheduleId: Long) {
        // Extras don't take part in PendingIntent matching, so this finds the alarm whatever occurrence it carries.
        PendingIntent.getBroadcast(context, scheduleId.toInt(), fireIntent(scheduleId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
            ?.let(alarmManager::cancel)
        cancelPreflight(scheduleId)
        // Nothing is due any more, so neither is a warning about it.
        runCatching { clearWarning(scheduleId, null) }
    }

    /**
     * The pre-flight check's own alarm (features F5), [Preflight.LEAD] before the send; none when that
     * has already passed. Not an alarm clock: it only looks, so it needn't show as the next alarm.
     */
    private fun armPreflight(scheduleId: Long, occurrenceAtMillis: Long, recovering: Boolean) {
        val at = Preflight.checkAt(occurrenceAtMillis, System.currentTimeMillis(), catchUp = recovering)
        if (at == null) {
            cancelPreflight(scheduleId)
            return
        }
        val check = PendingIntent.getBroadcast(
            context, scheduleId.toInt(),
            preflightIntent(scheduleId).putExtra(PreflightReceiver.EXTRA_OCCURRENCE_AT, occurrenceAtMillis),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (canScheduleExact()) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, check)
        else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, check)
    }

    private fun cancelPreflight(scheduleId: Long) {
        PendingIntent.getBroadcast(context, scheduleId.toInt(), preflightIntent(scheduleId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
            ?.let(alarmManager::cancel)
    }

    private fun preflightIntent(scheduleId: Long): Intent = Intent(context, PreflightReceiver::class.java)
        .setAction(PreflightReceiver.ACTION_PREFLIGHT)
        .putExtra(PreflightReceiver.EXTRA_SCHEDULE_ID, scheduleId)

    /**
     * Re-arms every alarm (after boot, update or clock change). An occurrence whose alarm could not
     * fire (phone off, force-stop, clock change) is handed to the send queue as if the alarm had
     * fired late, and the queue decides between sending it and reporting it missed. Deciding in one
     * place means an alarm delivered at the same moment can't race this into a second outcome.
     * Schedules that are queued or being sent are left alone: their occurrence only moves on when
     * the send finishes, so it would look overdue (B01).
     */
    suspend fun rescheduleAll(now: Instant = Instant.now()) {
        repository.all().forEach { schedule ->
            val next = schedule.nextAtMillis
            when {
                // Read per schedule, not once up front: a send may have started since the list was loaded.
                // Only a request that uses up the occurrence holds it; it re-arms the alarm itself when done.
                sendState.state.value.holdsOccurrence(schedule.id) -> log.info("Schedule ${schedule.id} is being sent; not re-arming it")
                !schedule.enabled || next == null -> cancel(schedule.id)
                next > now.toEpochMilli() -> sync(schedule, recovering = true)
                else -> SendService.enqueue(context, SendRequest(schedule.id, SendKind.SCHEDULED, next))
            }
        }
    }

    private fun firePendingIntent(scheduleId: Long, occurrenceAtMillis: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        scheduleId.toInt(),
        fireIntent(scheduleId).putExtra(AlarmReceiver.EXTRA_OCCURRENCE_AT, occurrenceAtMillis),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun fireIntent(scheduleId: Long): Intent = Intent(context, AlarmReceiver::class.java)
        .setAction(AlarmReceiver.ACTION_FIRE)
        .putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
}
