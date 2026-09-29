package com.thomaswcode.whatsappscheduler.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY CASE WHEN nextAtMillis IS NULL THEN 1 ELSE 0 END, nextAtMillis ASC")
    fun observeAll(): Flow<List<Schedule>>

    @Query("SELECT * FROM schedules")
    suspend fun getAll(): List<Schedule>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: Long): Schedule?


    @Insert
    suspend fun insert(schedule: Schedule): Long

    /** Puts deleted rows back under their own ids (Undo). */
    @Insert
    suspend fun insertAll(schedules: List<Schedule>)

    @Update
    suspend fun update(schedule: Schedule)

    @Delete
    suspend fun delete(schedule: Schedule)

    /** Writes only the run columns, so edits made while a send was running survive it (B08). */
    @Query(
        "UPDATE schedules SET firstAtMillis = :firstAtMillis, nextAtMillis = :nextAtMillis, lastRunAtMillis = :ranAtMillis, " +
            "lastResult = :result, lastStatus = :status, attention = :attention, postponedFromMillis = :postponedFromMillis WHERE id = :id"
    )
    suspend fun updateRun(
        id: Long,
        firstAtMillis: Long,
        nextAtMillis: Long?,
        ranAtMillis: Long,
        result: String,
        status: SendStatus,
        attention: Attention?,
        postponedFromMillis: Long?,
    )

    @Query("UPDATE schedules SET lastRunAtMillis = :ranAtMillis, lastResult = :result, lastStatus = :status, attention = :attention WHERE id = :id")
    suspend fun updateLastRun(id: Long, ranAtMillis: Long, result: String, status: SendStatus, attention: Attention?)

    /**
     * Auto-clear (quick-schedule.md §8): one-offs sent, or dry-run, before [beforeMillis] that wait on
     * nothing. Their history stays (send_log's link is ON DELETE SET NULL). Rows from before schema v2
     * have only the result text, which starts with the status, after "manual " for a run from the app.
     */
    @Query(
        "DELETE FROM schedules WHERE recurrence = 'ONCE' AND nextAtMillis IS NULL AND attention IS NULL " +
            "AND lastRunAtMillis < :beforeMillis AND (lastStatus IN ('SENT', 'DRY_RUN') OR (lastStatus IS NULL AND (" +
            "lastResult LIKE 'SENT%' OR lastResult LIKE 'DRY_RUN%' OR lastResult LIKE 'manual SENT%' OR lastResult LIKE 'manual DRY_RUN%')))"
    )
    suspend fun clearSent(beforeMillis: Long): Int

    /** Rows from before schema v2 whose last scheduled run is known only as text. */
    @Query("SELECT * FROM schedules WHERE lastStatus IS NULL AND lastResult IS NOT NULL AND lastResult NOT LIKE 'manual %'")
    suspend fun untypedOutcomes(): List<Schedule>

    @Query("UPDATE schedules SET lastResult = :result WHERE id = :id")
    suspend fun setLastResult(id: Long, result: String)

    @Query("UPDATE schedules SET attention = :attention WHERE id = :id")
    suspend fun setAttention(id: Long, attention: Attention?)

    /** Records what the user found out about the last run, e.g. that it did go out; nothing else changes. */
    @Query("UPDATE schedules SET lastStatus = :status, lastResult = :result, attention = :attention WHERE id = :id")
    suspend fun setOutcome(id: Long, status: SendStatus, result: String, attention: Attention?)

    @Query("SELECT * FROM schedules WHERE batchId = :batchId ORDER BY id")
    suspend fun getBatch(batchId: String): List<Schedule>

    /** Newest first, so when old schedules disagree about a chat, the newest one's details are kept. */
    @Query("SELECT * FROM schedules WHERE recipientId IS NULL ORDER BY createdAtMillis DESC, id DESC")
    suspend fun getUnlinked(): List<Schedule>

    /**
     * Still to go out: pending, or failed or missed and waiting to be sent by hand (see
     * [updatePendingTarget]). Not one that may have been sent (a series has a next time as well): its
     * prompt is about checking the chat it went to, which is still the right one.
     */
    @Query(
        "SELECT * FROM schedules WHERE recipientId = :recipientId AND (nextAtMillis IS NOT NULL OR attention IN ('FAILED', 'MISSED', 'HELD')) " +
            "AND (attention IS NULL OR attention != 'UNCERTAIN')"
    )
    suspend fun pendingFor(recipientId: Long): List<Schedule>

    @Query("UPDATE schedules SET recipientId = :recipientId WHERE id = :id")
    suspend fun setRecipient(id: Long, recipientId: Long)

    /**
     * Copies a recipient's edited details into its schedules that haven't gone out yet: pending ones,
     * and failed or missed ones still waiting to be sent by hand. Sent ones keep the details they
     * were sent with, and so does a one-off that may have been sent, whose chat is the one to check;
     * a series that may have been sent takes them for its next sends, and its prompt keeps the old chat.
     */
    @Query(
        "UPDATE schedules SET targetKind = :kind, targetName = :chatName, phoneE164 = :phoneE164, isLocked = :isLocked " +
            "WHERE recipientId = :recipientId AND (nextAtMillis IS NOT NULL OR attention IN ('FAILED', 'MISSED', 'HELD'))"
    )
    suspend fun updatePendingTarget(recipientId: Long, kind: TargetKind, chatName: String, phoneE164: String?, isLocked: Boolean)
}

@Dao
interface RecipientDao {
    /** Most recently used first; never-used ones (lastUsedAtMillis = 0) are left out. */
    @Query("SELECT * FROM recipients WHERE lastUsedAtMillis > 0 ORDER BY lastUsedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Recipient>>

    @Query(
        "SELECT * FROM recipients WHERE displayName LIKE '%' || :query || '%' OR whatsappName LIKE '%' || :query || '%' " +
            "OR phoneE164 LIKE '%' || :query || '%' ORDER BY lastUsedAtMillis DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<Recipient>

    @Query("SELECT * FROM recipients WHERE id = :id")
    suspend fun get(id: Long): Recipient?

    @Query("SELECT * FROM recipients WHERE phoneE164 = :phoneE164 LIMIT 1")
    suspend fun byPhone(phoneE164: String): Recipient?

    @Query("SELECT * FROM recipients WHERE phoneE164 = :phoneE164 AND id != :exceptId LIMIT 1")
    suspend fun otherByPhone(phoneE164: String, exceptId: Long): Recipient?

    /**
     * Recipients found by chat name: groups, and contacts without a number, which WhatsApp's search
     * treats alike. The name is matched in Kotlin (RecipientRepository): SQLite's NOCASE only folds
     * ASCII, so "Équipe" wouldn't match "équipe".
     */
    @Query("SELECT * FROM recipients WHERE phoneE164 IS NULL ORDER BY id")
    suspend fun named(): List<Recipient>

    @Insert
    suspend fun insert(recipient: Recipient): Long

    @Update
    suspend fun update(recipient: Recipient)

    @Delete
    suspend fun delete(recipient: Recipient)

    @Query("UPDATE recipients SET verifiedAtMillis = :atMillis WHERE id = :id")
    suspend fun markVerified(id: Long, atMillis: Long?)

    @Query("UPDATE recipients SET lastUsedAtMillis = :atMillis, useCount = useCount + 1 WHERE id = :id")
    suspend fun markUsed(id: Long, atMillis: Long)
}

@Dao
interface SendLogDao {
    @Query("SELECT * FROM send_log ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SendLog>>

    @Insert
    suspend fun insert(log: SendLog)

    @Insert
    suspend fun insertAll(logs: List<SendLog>)

    @Query("SELECT * FROM send_log WHERE scheduleId IN (:scheduleIds)")
    suspend fun forSchedules(scheduleIds: List<Long>): List<SendLog>

    @Query("DELETE FROM send_log WHERE scheduleId IN (:scheduleIds)")
    suspend fun deleteForSchedules(scheduleIds: List<Long>)

    /**
     * Each schedule's latest run for an occurrence (not a manual one), so the list can say when the
     * message that needs attention was due.
     */
    @Query(
        "SELECT * FROM send_log WHERE id IN (SELECT MAX(id) FROM send_log " +
            "WHERE scheduleId IS NOT NULL AND occurrenceAtMillis IS NOT NULL GROUP BY scheduleId)"
    )
    fun observeLatestOccurrenceRuns(): Flow<List<SendLog>>

    @Query("DELETE FROM send_log WHERE atMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)
}
