package com.thomaswcode.whatsappscheduler.data

import androidx.room.withTransaction
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendLog
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.RecurrenceCalculator
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

class ScheduleRepository(private val db: AppDatabase) : SendStore {

    private val schedules = db.scheduleDao()
    private val logs = db.sendLogDao()

    fun observeSchedules(): Flow<List<Schedule>> = schedules.observeAll()
    fun observeSchedule(id: Long): Flow<Schedule?> = schedules.observeById(id)
    fun observeLogs(limit: Int = 200): Flow<List<SendLog>> = logs.observeRecent(limit)

    suspend fun all(): List<Schedule> = schedules.getAll()
    override suspend fun get(id: Long): Schedule? = schedules.getById(id)

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

    /**
     * Reads the row as it is now rather than trusting the copy the send started with, and writes
     * only the run columns, so a change made while the send ran is kept (B08). The schedule moves
     * past [occurrenceAtMillis] only while the row still shows it: if it was edited or switched off
     * meanwhile, the edit decided the next occurrence and is left alone.
     */
    override suspend fun recordRun(
        id: Long,
        occurrenceAtMillis: Long,
        status: SendStatus,
        detail: String?,
        sentTo: String,
        ranAt: Instant,
    ): Schedule? = db.withTransaction {
        val current = schedules.getById(id) ?: return@withTransaction null
        logs.insert(SendLog(scheduleId = id, atMillis = ranAt.toEpochMilli(), status = status, targetName = sentTo, detail = detail))
        val usedUp = current.nextAtMillis == occurrenceAtMillis
        val next = if (usedUp) nextAfterRun(current, occurrenceAtMillis, ranAt) else current.nextAtMillis
        // A one-off sent early is dated to when it went out. Its old, still-future time would otherwise
        // bring it back whenever the next occurrence is recomputed, e.g. on switching it back on.
        val firstAt = if (usedUp && current.rule == Recurrence.Once) minOf(current.firstAtMillis, ranAt.toEpochMilli()) else current.firstAtMillis
        val result = status.name + (detail?.let { ": $it" } ?: "")
        schedules.updateRun(id, firstAt, next, ranAt.toEpochMilli(), result)
        current.copy(firstAtMillis = firstAt, nextAtMillis = next, lastRunAtMillis = ranAt.toEpochMilli(), lastResult = result)
    }

    override suspend fun recordManual(id: Long, status: SendStatus, detail: String?, sentTo: String, ranAt: Instant): Schedule? =
        db.withTransaction {
            val current = schedules.getById(id) ?: return@withTransaction null
            logs.insert(SendLog(scheduleId = id, atMillis = ranAt.toEpochMilli(), status = status, targetName = sentTo, detail = "(manual) " + (detail ?: "")))
            val result = "manual ${status.name}" + (detail?.let { ": $it" } ?: "")
            schedules.updateLastRun(id, ranAt.toEpochMilli(), result)
            current.copy(lastRunAtMillis = ranAt.toEpochMilli(), lastResult = result)
        }

    suspend fun pruneLogs(olderThan: Instant) = logs.prune(olderThan.toEpochMilli())

    companion object {
        /** The occurrence after the one due at [occurrenceAtMillis], or null when the rule has no more. */
        fun nextAfterRun(schedule: Schedule, occurrenceAtMillis: Long, ranAt: Instant): Long? =
            RecurrenceCalculator.nextOccurrence(
                rule = schedule.rule,
                firstAt = Instant.ofEpochMilli(schedule.firstAtMillis),
                after = maxOf(ranAt, Instant.ofEpochMilli(occurrenceAtMillis)),
                zone = ZoneId.of(schedule.zoneId),
            )?.toEpochMilli()
    }
}
