package com.thomaswcode.whatsappscheduler.data

import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import java.time.Instant

/** The slice of [ScheduleRepository] the send queue needs; JVM tests stand in a fake for it. */
interface SendStore {

    suspend fun get(id: Long): Schedule?

    /**
     * Logs the outcome of sending the occurrence due at [occurrenceAtMillis] and moves the schedule
     * past it, if the row still shows that occurrence. Returns the row as stored, or null when the
     * schedule has been deleted (the outcome is still logged).
     */
    suspend fun recordRun(id: Long, occurrenceAtMillis: Long, status: SendStatus, detail: String?, sentTo: String, ranAt: Instant): Schedule?

    /** Logs a send that was not for the pending occurrence; the next occurrence is left alone. */
    suspend fun recordManual(id: Long, status: SendStatus, detail: String?, sentTo: String, ranAt: Instant): Schedule?

    /**
     * Logs the occurrence due at [occurrenceAtMillis] as missed and holds it for the user (B09): a
     * one-off waits itself, with no next time; a series goes on to its next occurrence as usual, and
     * the missed one is held as a one-off copy of its own. Null, and nothing recorded, when the schedule
     * no longer has that occurrence pending (deleted, edited or moved since the queue looked).
     */
    suspend fun recordMissed(id: Long, occurrenceAtMillis: Long, detail: String, sentTo: String, ranAt: Instant): HeldRun?

    /** [schedule] and the members of its batch due at the same occurrence, which share one countdown (features F1). */
    suspend fun duePeers(schedule: Schedule, occurrenceAtMillis: Long): List<Schedule>

    /**
     * Snooze on the countdown (features F1): for each of [ids] (a batch shares its countdown), the
     * occurrence due at [occurrenceAtMillis] moves to [to], and it is logged, not as a failure. All in one
     * transaction, so a batch is never left part snoozed. Those whose occurrence is no longer the
     * pending one are left out.
     */
    suspend fun snooze(ids: List<Long>, occurrenceAtMillis: Long, to: Instant, ranAt: Instant): List<Schedule>

    /**
     * Postpone… on the countdown (features F1): nothing is sent until the user picks a time. A one-off
     * waits itself (attention HELD, no alarm); a series goes on, and the occurrence is held as a
     * one-off copy. For each of [ids], in one transaction; those whose occurrence is no longer the
     * pending one are left out.
     */
    suspend fun hold(ids: List<Long>, occurrenceAtMillis: Long, ranAt: Instant, detail: String): List<HeldRun>

    /**
     * Schedule [id]'s chat turned out to be behind Chat Lock: it is marked locked, and so are its
     * recipient and that recipient's other messages still to go, as Behind Chat Lock in its details does.
     */
    suspend fun markLocked(id: Long)
}

/**
 * What holding an occurrence did ([SendStore.recordMissed], [SendStore.hold]): [schedule] as stored
 * (to arm), and [held], the message now waiting on the user; the same row for a one-off.
 */
data class HeldRun(val schedule: Schedule, val held: Schedule)
