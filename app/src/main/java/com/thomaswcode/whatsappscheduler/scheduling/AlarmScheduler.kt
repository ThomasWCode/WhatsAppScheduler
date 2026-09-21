package com.thomaswcode.whatsappscheduler.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.data.settings.SettingsStore
import com.thomaswcode.whatsappscheduler.sending.SendService
import com.thomaswcode.whatsappscheduler.ui.MainActivity
import java.time.Duration
import java.time.Instant

/**
 * One exact alarm per enabled schedule. `setAlarmClock` is used because it is the alarm type
 * Samsung's power management is least likely to defer; it falls back to an inexact-while-idle
 * alarm if the user revokes the exact-alarm permission.
 */
class AlarmScheduler(
    private val context: Context,
    private val repository: ScheduleRepository,
    private val settings: SettingsStore,
) {
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = alarmManager.canScheduleExactAlarms()

    /** Sets or clears the alarm for a single schedule according to its state. */
    fun sync(schedule: Schedule) {
        val next = schedule.nextAtMillis
        if (!schedule.enabled || next == null) {
            cancel(schedule.id)
            return
        }
        val fire = pendingIntent(schedule.id)
        if (canScheduleExact()) {
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(next, show), fire)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, fire)
        }
    }

    fun cancel(scheduleId: Long) = alarmManager.cancel(pendingIntent(scheduleId))

    /**
     * Re-arms every alarm (after boot, update or clock change). Occurrences missed while the phone
     * was off are sent now when inside the grace window, otherwise skipped and logged.
     */
    suspend fun rescheduleAll(now: Instant = Instant.now()) {
        val grace = Duration.ofMinutes(settings.current().missedGraceMinutes.toLong())
        repository.all().forEach { schedule ->
            val next = schedule.nextAtMillis
            when {
                !schedule.enabled || next == null -> cancel(schedule.id)
                next > now.toEpochMilli() -> sync(schedule)
                Duration.between(Instant.ofEpochMilli(next), now) <= grace -> SendService.enqueue(context, schedule.id)
                else -> {
                    val minutes = Duration.between(Instant.ofEpochMilli(next), now).toMinutes()
                    val updated = repository.recordRun(schedule, SendStatus.SKIPPED, "missed by $minutes min (phone off?)", now)
                    sync(updated)
                }
            }
        }
    }

    private fun pendingIntent(scheduleId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
        return PendingIntent.getBroadcast(
            context, scheduleId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
