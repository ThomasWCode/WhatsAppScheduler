package com.thomaswcode.whatsappscheduler.data

import androidx.room.withTransaction
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.db.Attention
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendLog
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.RecurrenceCalculator
import com.thomaswcode.whatsappscheduler.domain.RecurrenceCodec
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What [ScheduleRepository.saveBatch] did: the members as saved (to arm) and the ones deleted (to disarm). */
data class BatchChange(val saved: List<Schedule>, val deleted: List<Schedule>)

/** A postponed schedule as it was (for Undo) and as it is now (to re-arm). */
data class Postponed(val before: Schedule, val after: Schedule)

/** What [ScheduleRepository.deleteWithHistory] removed, kept so Undo can put it back. */
data class DeletedSchedules(val schedules: List<Schedule>, val logs: List<SendLog>)

/** [members] went out, or may have, while their edit was open, so the edit wasn't saved (see saveBatch). */
class SentWhileEditing(val members: List<Schedule>) : IllegalStateException("sent while it was being edited")

class ScheduleRepository(private val db: AppDatabase) : SendStore {

    private val schedules = db.scheduleDao()
    private val logs = db.sendLogDao()
    private val recipients = db.recipientDao()

    fun observeSchedules(): Flow<List<Schedule>> = schedules.observeAll()
    fun observeSchedule(id: Long): Flow<Schedule?> = schedules.observeById(id)
    fun observeLogs(limit: Int = 200): Flow<List<SendLog>> = logs.observeRecent(limit)

    suspend fun all(): List<Schedule> = schedules.getAll()
    override suspend fun get(id: Long): Schedule? = schedules.getById(id)

    /**
     * Inserts or updates, recomputing the next occurrence from now. That replaces any postponed
     * occurrence, so the "moved from" time goes with it.
     */
    suspend fun save(schedule: Schedule, now: Instant = Instant.now()): Schedule {
        val next = RecurrenceCalculator.nextOccurrence(
            rule = schedule.rule,
            firstAt = Instant.ofEpochMilli(schedule.firstAtMillis),
            after = now.minusMillis(1),
            zone = ZoneId.of(schedule.zoneId),
        )
        val withNext = schedule.copy(nextAtMillis = next?.toEpochMilli(), postponedFromMillis = null)
        return if (withNext.id == 0L) withNext.copy(id = schedules.insert(withNext)) else withNext.also { schedules.update(it) }
    }

    suspend fun delete(schedule: Schedule) = schedules.delete(schedule)

    /**
     * A delete the user makes on the list: the schedules go together with their send history, since
     * you'd expect it gone (quick-schedule.md §7). What was removed is returned, for Undo.
     */
    suspend fun deleteWithHistory(ids: List<Long>): DeletedSchedules = db.withTransaction {
        val rows = ids.distinct().mapNotNull { schedules.getById(it) }
        val rowIds = rows.map { it.id }
        val history = if (rowIds.isEmpty()) emptyList() else logs.forSchedules(rowIds)
        if (rowIds.isNotEmpty()) logs.deleteForSchedules(rowIds)
        rows.forEach { schedules.delete(it) }
        DeletedSchedules(rows, history)
    }

    /**
     * Undo for [deleteWithHistory]: the same rows come back under the same ids (ids are never reused),
     * with their history. A recipient forgotten in the meantime is unlinked rather than failing.
     */
    suspend fun restore(deleted: DeletedSchedules): List<Schedule> = db.withTransaction {
        val rows = deleted.schedules.map { row ->
            if (row.recipientId != null && recipients.get(row.recipientId) == null) row.copy(recipientId = null) else row
        }
        schedules.insertAll(rows)
        logs.insertAll(deleted.logs)
        rows
    }

    /**
     * Postpone (quick-schedule.md §9): moves one occurrence to [at]. For a one-off that is its time;
     * for a series only the next occurrence moves and the rule is untouched, so the series resumes
     * after the moved send. Deliberately not through [save], which would recompute the occurrence
     * from the rule. The time it was due is kept as "moved from" (the first one, if moved again).
     * [seen] is the row as the user chose to move it: if it has been sent, retried or edited since,
     * nothing is moved and null is returned, as it is for a row that is gone.
     */
    suspend fun postpone(id: Long, at: Instant, seen: Schedule? = null): Postponed? = db.withTransaction {
        val current = schedules.getById(id) ?: return@withTransaction null
        if (seen != null && (current.nextAtMillis != seen.nextAtMillis || current.lastRunAtMillis != seen.lastRunAtMillis)) return@withTransaction null
        val due = current.nextAtMillis ?: current.firstAtMillis
        val atMillis = at.toEpochMilli()
        val moved = if (current.rule == Recurrence.Once) {
            // A missed or failed one-off is scheduled again, even one switched off before one-offs lost
            // their switch, and has nothing left to wait on.
            current.copy(firstAtMillis = atMillis, nextAtMillis = atMillis, enabled = true, attention = null)
        } else {
            // A series' attention is about an earlier occurrence, which moving the next one doesn't settle.
            current.copy(nextAtMillis = atMillis)
        }.copy(postponedFromMillis = current.postponedFromMillis ?: due)
        schedules.update(moved)
        Postponed(current, moved)
    }

    /**
     * [postpone] for every row of a batch in one transaction: all of them, as the user saw them, or none,
     * so a batch can't end up half moved. Null when any was sent, changed or deleted since.
     */
    suspend fun postponeAll(seen: List<Schedule>, at: Instant): List<Postponed>? = db.withTransaction {
        val current = seen.map { schedules.getById(it.id) }
        val unchanged = seen.zip(current).all { (was, now) -> now != null && now.nextAtMillis == was.nextAtMillis && now.lastRunAtMillis == was.lastRunAtMillis }
        if (!unchanged) return@withTransaction null
        seen.map { postpone(it.id, at, seen = it) ?: error("row changed inside the transaction") }
    }

    /**
     * [restoreTiming] for a whole move, in one transaction: every row, or none (null) if any has changed
     * since, or if a moved time is within [UNDO_MARGIN] of [now]. An alarm never fires before its time, so
     * then the moved send can't be picked up while the old time is being put back, which would lose the
     * mark that lets that send use its occurrence up, and send it twice.
     */
    suspend fun restoreAll(moved: List<Postponed>, now: Instant = Instant.now()): List<Schedule>? = db.withTransaction {
        val stands = moved.all { m -> schedules.getById(m.after.id)?.let { it.nextAtMillis == m.after.nextAtMillis && it.lastRunAtMillis == m.after.lastRunAtMillis } == true }
        val farEnough = moved.all { m -> (m.after.nextAtMillis ?: Long.MAX_VALUE) > now.plus(UNDO_MARGIN).toEpochMilli() }
        if (!stands || !farEnough) return@withTransaction null
        moved.map { restoreTiming(it) ?: error("row changed inside the transaction") }
    }

    /**
     * Undo for [postpone]: the timing, switch and attention as they were; the rest as it is now. Only
     * while the move still stands: once the moved occurrence has run, or the row was changed, putting
     * the old time back would send it again, so nothing is restored and null is returned.
     */
    suspend fun restoreTiming(moved: Postponed): Schedule? = db.withTransaction {
        val before = moved.before
        val current = schedules.getById(before.id) ?: return@withTransaction null
        if (current.nextAtMillis != moved.after.nextAtMillis || current.lastRunAtMillis != moved.after.lastRunAtMillis) return@withTransaction null
        val restored = current.copy(
            firstAtMillis = before.firstAtMillis,
            nextAtMillis = before.nextAtMillis,
            enabled = before.enabled,
            postponedFromMillis = before.postponedFromMillis,
            // Only a one-off's attention was cleared by the move; a series' is as the user has left it since.
            attention = if (before.rule == Recurrence.Once) before.attention else current.attention,
        )
        schedules.update(restored)
        restored
    }

    /** Auto-clear: deletes one-offs sent before [before]; their history stays. Returns how many went. */
    suspend fun clearSent(before: Instant): Int = schedules.clearSent(before.toEpochMilli())

    /**
     * Rows from before schema v2 know their last outcome only as text, so a failure then wouldn't show
     * under Needs attention. The outcome of such a row's last scheduled run becomes its typed status
     * and, when it failed or was missed, what it waits on. Each row is done once (it then has a typed
     * status); runs by hand are left alone, as they never decide attention. Returns how many.
     */
    suspend fun typeLegacyOutcomes(): Int = db.withTransaction {
        schedules.untypedOutcomes().count { row ->
            val result = row.lastResult ?: return@count false
            val status = statusFromResult(result) ?: return@count false
            schedules.setOutcome(row.id, status, result, attentionAfter(status, null))
            true
        }
    }

    /** Sets or clears what the schedule is waiting on the user for, e.g. Discard on a missed occurrence. */
    suspend fun setAttention(id: Long, attention: Attention?) = schedules.setAttention(id, attention)

    /**
     * "Mark as sent" on a message that may have gone out (B02): the user checked the chat and it did.
     * Logged as the user's finding; the run keeps its own time, since that is when the message went.
     */
    suspend fun markSent(id: Long, now: Instant = Instant.now()): Schedule? = db.withTransaction {
        val current = schedules.getById(id) ?: return@withTransaction null
        val result = "SENT: marked as sent by you"
        logs.insert(SendLog(scheduleId = id, atMillis = now.toEpochMilli(), status = SendStatus.SENT, targetName = current.targetName, detail = "Marked as sent by you"))
        schedules.setOutcome(id, SendStatus.SENT, result, attention = null)
        current.copy(lastStatus = SendStatus.SENT, lastResult = result, attention = null)
    }

    /** Schedule id to the occurrence its latest scheduled run was for. */
    fun observeLatestOccurrences(): Flow<Map<Long, Long>> = logs.observeLatestOccurrenceRuns().map { runs ->
        runs.mapNotNull { run -> run.scheduleId?.let { id -> run.occurrenceAtMillis?.let { id to it } } }.toMap()
    }

    suspend fun batch(batchId: String): List<Schedule> = schedules.getBatch(batchId)

    /**
     * Saves one message for several recipients (quick-schedule.md §10): one ordinary schedule each,
     * so retries, failures and history stay per recipient. Members share a batchId so the list shows
     * them as one row and edits apply to all. [members] are the batch as it was (empty when new):
     * [template]'s message and time go to every member, a member whose recipient was removed is
     * deleted, and an added recipient gets a new member. [lastRunsSeen] is each member's last run when
     * the edit began: one that has since gone out, or may have, would be armed again by saving it and
     * send twice, so nothing is saved and [SentWhileEditing] says which.
     */
    suspend fun saveBatch(
        template: Schedule,
        recipients: List<Recipient>,
        members: List<Schedule> = emptyList(),
        now: Instant = Instant.now(),
        lastRunsSeen: Map<Long, Long?> = emptyMap(),
    ): BatchChange =
        db.withTransaction {
            require(recipients.isNotEmpty() && recipients.size <= MAX_BATCH) { "A message goes to 1–$MAX_BATCH recipients" }
            // The rows as they are now: a member may have finished sending since the caller read it, and
            // saving that stale copy would overwrite the run it just recorded (B08).
            val current = members.mapNotNull { schedules.getById(it.id) }
            val sentSince = current.filter { m ->
                m.id in lastRunsSeen && m.lastRunAtMillis != lastRunsSeen[m.id] && (m.lastStatus == SendStatus.SENT || m.lastStatus == SendStatus.UNCERTAIN)
            }
            if (sentSince.isNotEmpty()) throw SentWhileEditing(sentSince)
            // Part of a batch edited on its own (the list shows a batch split by outcome) gets a batch of
            // its own; keeping the old id would show it again with members it no longer matches.
            val original = current.firstNotNullOfOrNull { it.batchId }
            val wholeBatch = original != null && schedules.getBatch(original).all { member -> current.any { it.id == member.id } }
            val batchId = if (recipients.size > 1) original?.takeIf { wholeBatch } ?: UUID.randomUUID().toString() else null
            val byRecipient = current.filter { it.recipientId != null }.associateBy { it.recipientId }
            val saved = recipients.map { recipient ->
                val base = byRecipient[recipient.id] ?: current.singleOrNull { it.recipientId == null && recipients.size == 1 } ?: template.copy(id = 0)
                save(
                    base.copy(
                        message = template.message,
                        firstAtMillis = template.firstAtMillis,
                        zoneId = template.zoneId,
                        recurrence = template.recurrence,
                        batchId = batchId,
                        recipientId = recipient.id,
                        targetKind = recipient.kind,
                        targetName = recipient.chatName,
                        phoneE164 = recipient.phoneE164,
                        isLocked = recipient.isLocked,
                        // Edited by the user, so whatever it was waiting on has been dealt with.
                        attention = null,
                        // The sheet has no pause: saving schedules the message, for every member alike, so
                        // an added recipient can't be armed while the rest of the batch stays switched off.
                        enabled = true,
                    ),
                    now,
                )
            }
            val removed = current.filter { member -> saved.none { it.id == member.id } }
            removed.forEach { schedules.delete(it) }
            BatchChange(saved, removed)
        }

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
        // A run is only recorded for an occurrence other than the row's next when the row changed during
        // the send. An edit clears "moved from", so a row that still has it was postponed while that
        // occurrence was on its way (however many times: A to B to C): it has gone now, so it counts as
        // used up and the moved copy is dropped, or the message would go twice.
        val usedUp = current.nextAtMillis == occurrenceAtMillis || current.postponedFromMillis != null
        val next = when {
            !usedUp -> current.nextAtMillis
            // Used up, a one-off is done, even one whose time a Postpone moved on meanwhile.
            current.rule == Recurrence.Once -> null
            else -> nextAfterRun(current, occurrenceAtMillis, ranAt, movedFromMillis = current.postponedFromMillis)
        }
        // A one-off sent early is dated to when it went out. Its old, still-future time would otherwise
        // bring it back whenever the next occurrence is recomputed, e.g. on switching it back on.
        val firstAt = if (usedUp && current.rule == Recurrence.Once) minOf(current.firstAtMillis, ranAt.toEpochMilli()) else current.firstAtMillis
        val result = status.name + (detail?.let { ": $it" } ?: "")
        val attention = attentionAfter(status, current.attention)
        // A postponed occurrence that has run is no longer "moved from" anywhere.
        val postponedFrom = if (usedUp) null else current.postponedFromMillis
        schedules.updateRun(id, firstAt, next, ranAt.toEpochMilli(), result, status, attention, postponedFrom)
        current.copy(
            firstAtMillis = firstAt, nextAtMillis = next, lastRunAtMillis = ranAt.toEpochMilli(),
            lastResult = result, lastStatus = status, attention = attention, postponedFromMillis = postponedFrom,
        )
    }

    override suspend fun recordMissed(id: Long, occurrenceAtMillis: Long, detail: String, sentTo: String, ranAt: Instant): HeldRun? =
        db.withTransaction {
            // Only while the row still has that occurrence pending: one edited, moved or deleted since the
            // queue looked at it isn't missed, and holding it would mark the user's new time as missed.
            val current = schedules.getById(id)?.takeIf { it.nextAtMillis == occurrenceAtMillis } ?: return@withTransaction null
            if (current.rule == Recurrence.Once) {
                val stored = recordRun(id, occurrenceAtMillis, SendStatus.MISSED, detail, sentTo, ranAt) ?: return@withTransaction null
                return@withTransaction HeldRun(stored, stored)
            }
            holdAsCopy(current, occurrenceAtMillis, ranAt, Attention.MISSED, SendStatus.MISSED, detail, ran = true)
        }

    override suspend fun duePeers(schedule: Schedule, occurrenceAtMillis: Long): List<Schedule> =
        schedule.batchId
            ?.let { batch -> schedules.getBatch(batch).filter { it.enabled && it.nextAtMillis == occurrenceAtMillis } }
            ?.takeIf { peers -> peers.any { it.id == schedule.id } }
            ?: listOf(schedule)

    override suspend fun snooze(id: Long, occurrenceAtMillis: Long, to: Instant, ranAt: Instant): Schedule? = db.withTransaction {
        val current = schedules.getById(id)?.takeIf { it.nextAtMillis == occurrenceAtMillis } ?: return@withTransaction null
        val moved = postpone(id, to, seen = current)?.after ?: return@withTransaction null
        logs.insert(SendLog(scheduleId = id, atMillis = ranAt.toEpochMilli(), status = SendStatus.SKIPPED, targetName = current.targetName, detail = SNOOZED, occurrenceAtMillis = occurrenceAtMillis))
        moved
    }

    override suspend fun hold(id: Long, occurrenceAtMillis: Long, ranAt: Instant): HeldRun? = db.withTransaction {
        val current = schedules.getById(id)?.takeIf { it.nextAtMillis == occurrenceAtMillis } ?: return@withTransaction null
        if (current.rule != Recurrence.Once) {
            return@withTransaction holdAsCopy(current, occurrenceAtMillis, ranAt, Attention.HELD, SendStatus.SKIPPED, HELD_FOR_A_TIME, ran = false)
        }
        // Waits itself, with no alarm: nothing is sent until the user picks a time.
        val held = current.copy(nextAtMillis = null, attention = Attention.HELD)
        schedules.update(held)
        logs.insert(SendLog(scheduleId = id, atMillis = ranAt.toEpochMilli(), status = SendStatus.SKIPPED, targetName = current.targetName, detail = HELD_FOR_A_TIME, occurrenceAtMillis = occurrenceAtMillis))
        HeldRun(held, held)
    }

    /**
     * Moves series [current] past [occurrenceAtMillis], as after any run, but its own last run and
     * whatever it already waits on (an earlier failure, say) stay as they were. That occurrence's
     * message waits on the user as a one-off copy of its own ([attention]), with its own history, so
     * Send now, Postpone and Discard are the one-off ones. [ran] when [status] is that copy's run.
     */
    private suspend fun holdAsCopy(
        current: Schedule,
        occurrenceAtMillis: Long,
        ranAt: Instant,
        attention: Attention,
        status: SendStatus,
        detail: String,
        ran: Boolean,
    ): HeldRun {
        val usedUp = current.nextAtMillis == occurrenceAtMillis || current.postponedFromMillis != null
        val series = if (!usedUp) current else current.copy(
            nextAtMillis = nextAfterRun(current, occurrenceAtMillis, ranAt, movedFromMillis = current.postponedFromMillis),
            postponedFromMillis = null,
        )
        if (series != current) schedules.update(series)
        val held = Schedule(
            message = current.message, targetKind = current.targetKind, targetName = current.targetName, phoneE164 = current.phoneE164,
            isLocked = current.isLocked, firstAtMillis = occurrenceAtMillis, zoneId = current.zoneId,
            recurrence = RecurrenceCodec.encode(Recurrence.Once), nextAtMillis = null, createdAtMillis = ranAt.toEpochMilli(),
            lastRunAtMillis = ranAt.toEpochMilli().takeIf { ran }, lastResult = "${status.name}: $detail".takeIf { ran },
            lastStatus = status.takeIf { ran }, recipientId = current.recipientId, attention = attention, sourceScheduleId = current.id,
        ).let { it.copy(id = schedules.insert(it)) }
        logs.insert(SendLog(scheduleId = held.id, atMillis = ranAt.toEpochMilli(), status = status, targetName = current.targetName, detail = detail, occurrenceAtMillis = occurrenceAtMillis))
        return HeldRun(series, held)
    }

    /**
     * A manual run doesn't decide whether the schedule needs attention, except that a real send by
     * hand resolves whatever it was waiting on, and one that may have gone out needs checking. Only a run that may have
     * sent something becomes the schedule's last run; a test, or a failure that sent nothing, is
     * logged and noted for diagnosis without changing when the list says the message went.
     */
    override suspend fun recordManual(id: Long, status: SendStatus, detail: String?, sentTo: String, ranAt: Instant): Schedule? =
        db.withTransaction {
            val current = schedules.getById(id)
            logs.insert(SendLog(scheduleId = current?.id, atMillis = ranAt.toEpochMilli(), status = status, targetName = sentTo, detail = "(manual) " + (detail ?: "")))
            if (current == null) return@withTransaction null
            val result = "manual ${status.name}" + (detail?.let { ": $it" } ?: "")
            if (status != SendStatus.SENT && status != SendStatus.UNCERTAIN) {
                schedules.setLastResult(id, result)
                return@withTransaction current.copy(lastResult = result)
            }
            // Sent resolves whatever it waited on; may-have-been-sent needs the chat checked, whatever it
            // waited on before, or the list would still offer to send it again.
            val attention = if (status == SendStatus.SENT) null else Attention.UNCERTAIN
            schedules.updateLastRun(id, ranAt.toEpochMilli(), result, status, attention)
            current.copy(lastRunAtMillis = ranAt.toEpochMilli(), lastResult = result, lastStatus = status, attention = attention)
        }

    suspend fun pruneLogs(olderThan: Instant) = logs.prune(olderThan.toEpochMilli())

    companion object {
        /** In the history for a countdown's Snooze and Postpone… (features F1). */
        private const val SNOOZED = "snoozed 5 min on the countdown"
        private const val HELD_FOR_A_TIME = "held for a new time: Postpone… on the countdown"

        /** The status a result text starts with ("FAILED: search: …"), after "manual " for a run by hand. */
        fun statusFromResult(result: String?): SendStatus? = result?.removePrefix("manual ")?.let { text ->
            SendStatus.entries.firstOrNull { text == it.name || text.startsWith(it.name + ":") }
        }

        /** How far off a moved send must still be for Undo to put the old time back ([restoreAll]). */
        val UNDO_MARGIN: java.time.Duration = java.time.Duration.ofSeconds(30)

        /** At most this many recipients per message, so one mistake can't turn into a flood (§10). */
        const val MAX_BATCH = 10

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

        /**
         * The occurrence after the one due at [occurrenceAtMillis], or null when the rule has no more.
         * For a postponed occurrence, [movedFromMillis] is when it was originally due: moved later, the
         * occurrences it passed are skipped; moved earlier, the original time mustn't send it again.
         */
        fun nextAfterRun(schedule: Schedule, occurrenceAtMillis: Long, ranAt: Instant, movedFromMillis: Long? = null): Long? =
            RecurrenceCalculator.nextOccurrence(
                rule = schedule.rule,
                firstAt = Instant.ofEpochMilli(schedule.firstAtMillis),
                after = listOfNotNull(ranAt, Instant.ofEpochMilli(occurrenceAtMillis), movedFromMillis?.let(Instant::ofEpochMilli)).max(),
                zone = ZoneId.of(schedule.zoneId),
            )?.toEpochMilli()
    }
}
