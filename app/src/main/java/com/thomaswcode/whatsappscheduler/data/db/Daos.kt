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

    @Query("SELECT * FROM schedules WHERE id = :id")
    fun observeById(id: Long): Flow<Schedule?>

    @Insert
    suspend fun insert(schedule: Schedule): Long

    @Update
    suspend fun update(schedule: Schedule)

    @Delete
    suspend fun delete(schedule: Schedule)

    /** Writes only the run columns, so edits made while a send was running survive it (B08). */
    @Query(
        "UPDATE schedules SET firstAtMillis = :firstAtMillis, nextAtMillis = :nextAtMillis, lastRunAtMillis = :ranAtMillis, " +
            "lastResult = :result, lastStatus = :status, attention = :attention WHERE id = :id"
    )
    suspend fun updateRun(id: Long, firstAtMillis: Long, nextAtMillis: Long?, ranAtMillis: Long, result: String, status: SendStatus, attention: Attention?)

    @Query("UPDATE schedules SET lastRunAtMillis = :ranAtMillis, lastResult = :result, lastStatus = :status, attention = :attention WHERE id = :id")
    suspend fun updateLastRun(id: Long, ranAtMillis: Long, result: String, status: SendStatus, attention: Attention?)

    @Query("SELECT * FROM schedules WHERE batchId = :batchId ORDER BY id")
    suspend fun getBatch(batchId: String): List<Schedule>

    /** Newest first, so when old schedules disagree about a chat, the newest one's details are kept. */
    @Query("SELECT * FROM schedules WHERE recipientId IS NULL ORDER BY createdAtMillis DESC, id DESC")
    suspend fun getUnlinked(): List<Schedule>

    @Query("SELECT * FROM schedules WHERE recipientId = :recipientId AND nextAtMillis IS NOT NULL")
    suspend fun pendingFor(recipientId: Long): List<Schedule>

    @Query("UPDATE schedules SET recipientId = :recipientId WHERE id = :id")
    suspend fun setRecipient(id: Long, recipientId: Long)

    /**
     * Copies a recipient's edited details into its schedules that haven't gone out yet. Sent ones
     * keep the details they were sent with.
     */
    @Query(
        "UPDATE schedules SET targetKind = :kind, targetName = :chatName, phoneE164 = :phoneE164, isLocked = :isLocked " +
            "WHERE recipientId = :recipientId AND nextAtMillis IS NOT NULL"
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

    @Query("SELECT * FROM recipients WHERE phoneE164 IS NULL AND kind = :kind AND COALESCE(whatsappName, displayName) = :chatName COLLATE NOCASE AND id != :exceptId LIMIT 1")
    suspend fun otherByChatName(kind: TargetKind, chatName: String, exceptId: Long): Recipient?

    /**
     * A recipient found by chat name (groups, and contacts without a number), whatever the case: the
     * search falls back to a case-insensitive match, so "book club" would be the same chat as "Book Club".
     */
    @Query("SELECT * FROM recipients WHERE phoneE164 IS NULL AND kind = :kind AND COALESCE(whatsappName, displayName) = :chatName COLLATE NOCASE LIMIT 1")
    suspend fun byChatName(kind: TargetKind, chatName: String): Recipient?

    @Insert
    suspend fun insert(recipient: Recipient): Long

    @Update
    suspend fun update(recipient: Recipient)

    @Delete
    suspend fun delete(recipient: Recipient)

    @Query("UPDATE recipients SET lastUsedAtMillis = :atMillis, useCount = useCount + 1 WHERE id = :id")
    suspend fun markUsed(id: Long, atMillis: Long)
}

@Dao
interface SendLogDao {
    @Query("SELECT * FROM send_log ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SendLog>>

    @Insert
    suspend fun insert(log: SendLog)

    @Query("DELETE FROM send_log WHERE atMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)
}
