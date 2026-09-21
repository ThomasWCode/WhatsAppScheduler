package com.thomaswcode.whatsappscheduler.data

import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.ScheduleDao
import com.thomaswcode.whatsappscheduler.data.db.SendLog
import com.thomaswcode.whatsappscheduler.data.db.SendLogDao
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.RecurrenceCalculator
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

class ScheduleRepository(private val schedules: ScheduleDao, private val logs: SendLogDao) {

    fun observeSchedules(): Flow<List<Schedule>> = schedules.observeAll()
    fun observeSchedule(id: Long): Flow<Schedule?> = schedules.observeById(id)
    fun observeLogs(limit: Int = 200): Flow<List<SendLog>> = logs.observeRecent(limit)

    suspend fun all(): List<Schedule> = schedules.getAll()
    suspend fun get(id: Long): Schedule? = schedules.getById(id)

    /** Inserts or updates, recomputing the next occurrence from now. */
    suspend fun save(schedule: Schedule, now: Instant = Instant.now()): Schedule {
        val next = RecurrenceCalculator.nextOccurrence(
            rule = schedule.rule,
            firstAt = Instant.ofEpochMilli(schedule.firstAtMillis),
            after = now.minusMillis(1),
            zone = ZoneId.of(schedule.zoneId),
        )
        val withNext = schedule.copy(nextAtMillis = next?.toEpochMilli())
        return if (withNext.id == 0L) withNext.copy(id = schedules.insert(withNext)) else withNext.also { schedules.update(it) }
    }

    suspend fun delete(schedule: Schedule) = schedules.delete(schedule)

    suspend fun setEnabled(schedule: Schedule, enabled: Boolean): Schedule = save(schedule.copy(enabled = enabled))

    /** Records an outcome and moves the schedule to its next occurrence after [ranAt]. */
    suspend fun recordRun(schedule: Schedule, status: SendStatus, detail: String?, ranAt: Instant = Instant.now()): Schedule {
        logs.insert(SendLog(scheduleId = schedule.id, atMillis = ranAt.toEpochMilli(), status = status, targetName = schedule.targetName, detail = detail))
        val next = RecurrenceCalculator.nextOccurrence(
            rule = schedule.rule,
            firstAt = Instant.ofEpochMilli(schedule.firstAtMillis),
            after = maxOf(ranAt, Instant.ofEpochMilli(schedule.nextAtMillis ?: 0L)),
            zone = ZoneId.of(schedule.zoneId),
        )
        val updated = schedule.copy(
            nextAtMillis = next?.toEpochMilli(),
            lastRunAtMillis = ranAt.toEpochMilli(),
            lastResult = status.name + (detail?.let { ": $it" } ?: ""),
        )
        schedules.update(updated)
        return updated
    }

    /** Logs a "send now" run without touching the schedule's next occurrence. */
    suspend fun recordManual(schedule: Schedule, status: SendStatus, detail: String?, ranAt: Instant = Instant.now()) {
        logs.insert(SendLog(scheduleId = schedule.id, atMillis = ranAt.toEpochMilli(), status = status, targetName = schedule.targetName, detail = "(manual) " + (detail ?: "")))
        schedules.update(schedule.copy(lastRunAtMillis = ranAt.toEpochMilli(), lastResult = "manual ${status.name}" + (detail?.let { ": $it" } ?: "")))
    }

    suspend fun pruneLogs(olderThan: Instant) = logs.prune(olderThan.toEpochMilli())
}
