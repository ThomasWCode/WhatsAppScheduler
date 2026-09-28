package com.thomaswcode.whatsappscheduler.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.data.settings.SettingsStore
import com.thomaswcode.whatsappscheduler.sending.FallbackNotifier
import com.thomaswcode.whatsappscheduler.sending.SendKind
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import com.thomaswcode.whatsappscheduler.sending.SendService
import com.thomaswcode.whatsappscheduler.sending.SendStateHolder
import com.thomaswcode.whatsappscheduler.ui.MainActivity
import com.thomaswcode.whatsappscheduler.util.TraceLog
import java.time.Duration
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
    private val settings: SettingsStore,
    private val sendState: SendStateHolder,
    private val fallback: FallbackNotifier,
    private val log: TraceLog,
) : ScheduleAlarms {
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = alarmManager.canScheduleExactAlarms()

    /** Sets or clears the alarm for a single schedule according to its state. */
    override fun sync(schedule: Schedule) {
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
    }

    fun cancel(scheduleId: Long) {
        // Extras don't take part in PendingIntent matching, so this finds the alarm whatever occurrence it carries.
        PendingIntent.getBroadcast(context, scheduleId.toInt(), fireIntent(scheduleId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
            ?.let(alarmManager::cancel)
    }

    /**
     * Re-arms every alarm (after boot, update or clock change). Occurrences missed while the phone
     * was off are sent now when inside the grace window; later ones are logged as skipped and the
     * user is told (interim for B09). Schedules that are queued or being sent are left alone: their
     * occurrence only moves on when the send finishes, so it would look overdue (B01).
     */
    suspend fun rescheduleAll(now: Instant = Instant.now()) {
        val grace = Duration.ofMinutes(settings.current().missedGraceMinutes.toLong())
        val busy = sendState.state.value
        repository.all().forEach { schedule ->
            val next = schedule.nextAtMillis
            when {
                busy.isBusy(schedule.id) -> log.info("Schedule ${schedule.id} is being sent; not re-arming it")
                !schedule.enabled || next == null -> cancel(schedule.id)
                next > now.toEpochMilli() -> sync(schedule)
                Duration.between(Instant.ofEpochMilli(next), now) <= grace ->
                    SendService.enqueue(context, SendRequest(schedule.id, SendKind.SCHEDULED, next))
                else -> {
                    val minutes = Duration.between(Instant.ofEpochMilli(next), now).toMinutes()
                    val updated = repository.recordRun(schedule.id, next, SendStatus.SKIPPED, "missed by $minutes min (phone off?)", schedule.targetName, now)
                    updated?.let(::sync)
                    fallback.notifyMissed(schedule, next)
                }
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
