package com.thomaswcode.whatsappscheduler.data

import androidx.room.withTransaction
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.db.Attention
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
     * Switches a schedule on or off starting from the row as stored, not a copy the UI may have
     * held since before a send finished, so the switch can't write back an outdated run (B08).
     */
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Instant = Instant.now()): Schedule? = db.withTransaction {
        schedules.getById(id)?.let { save(it.copy(enabled = enabled), now) }
    }

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
        val current = schedules.getById(id)
        // Logged even when the schedule was deleted during the send: history outlives the schedule.
        logs.insert(SendLog(scheduleId = current?.id, atMillis = ranAt.toEpochMilli(), status = status, targetName = sentTo, detail = detail, occurrenceAtMillis = occurrenceAtMillis))
        if (current == null) return@withTransaction null
        val usedUp = current.nextAtMillis == occurrenceAtMillis
        val next = if (usedUp) nextAfterRun(current, occurrenceAtMillis, ranAt) else current.nextAtMillis
        // A one-off sent early is dated to when it went out. Its old, still-future time would otherwise
        // bring it back whenever the next occurrence is recomputed, e.g. on switching it back on.
        val firstAt = if (usedUp && current.rule == Recurrence.Once) minOf(current.firstAtMillis, ranAt.toEpochMilli()) else current.firstAtMillis
        val result = status.name + (detail?.let { ": $it" } ?: "")
        val attention = attentionAfter(status, current.attention)
        schedules.updateRun(id, firstAt, next, ranAt.toEpochMilli(), result, status, attention)
        current.copy(firstAtMillis = firstAt, nextAtMillis = next, lastRunAtMillis = ranAt.toEpochMilli(), lastResult = result, lastStatus = status, attention = attention)
    }

    /**
     * A manual run doesn't decide whether the schedule needs attention, except that a real send by
     * hand resolves whatever it was waiting on: the message has now gone out.
     */
    override suspend fun recordManual(id: Long, status: SendStatus, detail: String?, sentTo: String, ranAt: Instant): Schedule? =
        db.withTransaction {
            val current = schedules.getById(id)
            logs.insert(SendLog(scheduleId = current?.id, atMillis = ranAt.toEpochMilli(), status = status, targetName = sentTo, detail = "(manual) " + (detail ?: "")))
            if (current == null) return@withTransaction null
            val result = "manual ${status.name}" + (detail?.let { ": $it" } ?: "")
            val attention = if (status == SendStatus.SENT) null else current.attention
            schedules.updateLastRun(id, ranAt.toEpochMilli(), result, status, attention)
            current.copy(lastRunAtMillis = ranAt.toEpochMilli(), lastResult = result, lastStatus = status, attention = attention)
        }

    suspend fun pruneLogs(olderThan: Instant) = logs.prune(olderThan.toEpochMilli())

    companion object {
        /**
         * What an occurrence's outcome leaves the schedule waiting on the user for (T5). A real send
         * clears it; a dry run sent nothing, so it leaves whatever was already waiting.
         */
        fun attentionAfter(status: SendStatus, previous: Attention?): Attention? = when (status) {
            SendStatus.SENT -> null
            SendStatus.DRY_RUN -> previous
            SendStatus.FAILED -> Attention.FAILED
            SendStatus.UNCERTAIN -> Attention.UNCERTAIN
            SendStatus.SKIPPED, SendStatus.MISSED -> Attention.MISSED
        }

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
