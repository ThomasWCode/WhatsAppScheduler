package com.thomaswcode.whatsappscheduler.data

import androidx.room.withTransaction
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Who the user picked in the quick sheet's To field, before it is stored: a contact search result,
 * a saved recipient ([recipientId]), or a typed group or chat name.
 */
data class RecipientDraft(
    val kind: TargetKind,
    val displayName: String,
    val whatsappName: String? = null,
    val phoneE164: String? = null,
    val isLocked: Boolean = false,
    val recipientId: Long? = null,
) {
    val chatName: String get() = whatsappName?.takeIf { it.isNotBlank() } ?: displayName
}

/** Everyone messages have been scheduled to (quick-schedule.md §3). */
class RecipientRepository(private val db: AppDatabase) {

    private val recipients = db.recipientDao()
    private val schedules = db.scheduleDao()

    /** The recent chips: the most recently used, up to [limit]. */
    fun observeRecent(limit: Int = RECENT_CHIPS): Flow<List<Recipient>> = recipients.observeRecent(limit)

    suspend fun search(query: String, limit: Int = 10): List<Recipient> =
        query.trim().takeIf { it.isNotEmpty() }?.let { recipients.search(it, limit) } ?: emptyList()

    suspend fun get(id: Long): Recipient? = recipients.get(id)

    /**
     * The stored recipient for [draft]: the one it was picked from, else the one with the same number,
     * else (for chats without a number) the same kind and chat name, else a new one. Picking someone
     * again therefore keeps what was learned about them, such as Chat Lock.
     */
    suspend fun resolve(draft: RecipientDraft): Recipient = db.withTransaction {
        draft.recipientId?.let { recipients.get(it) }
            ?: draft.phoneE164?.let { recipients.byPhone(it) }
            ?: (if (draft.phoneE164 == null) recipients.byChatName(draft.kind, draft.chatName) else null)
            ?: Recipient(
                kind = draft.kind,
                displayName = draft.displayName.trim(),
                whatsappName = draft.whatsappName?.trim()?.takeIf { it.isNotEmpty() && it != draft.displayName.trim() },
                phoneE164 = draft.phoneE164,
                isLocked = draft.isLocked,
            ).let { it.copy(id = recipients.insert(it)) }
    }

    suspend fun markUsed(ids: Collection<Long>, at: Instant = Instant.now()) = db.withTransaction {
        ids.forEach { recipients.markUsed(it, at.toEpochMilli()) }
    }

    /**
     * Saves edited details and copies them into the recipient's schedules that are still to be
     * sent. Sent history keeps the details it was actually sent with.
     */
    suspend fun updateDetails(recipient: Recipient) = db.withTransaction {
        recipients.update(recipient)
        schedules.updatePendingTarget(recipient.id, recipient.kind, recipient.chatName, recipient.phoneE164, recipient.isLocked)
    }

    /** "Remove from recents": forgets the recipient. Its schedules keep their own copy of the details. */
    suspend fun remove(recipient: Recipient) = recipients.delete(recipient)

    /**
     * Links schedules made before recipients existed (schema v1) to a recipient each, so recents and
     * Chat Lock carry over. Runs on every fresh start; only unlinked schedules are touched.
     */
    suspend fun linkUnlinkedSchedules() = db.withTransaction {
        schedules.getUnlinked().forEach { schedule ->
            val recipient = resolve(
                RecipientDraft(schedule.targetKind, schedule.targetName, phoneE164 = schedule.phoneE164, isLocked = schedule.isLocked),
            )
            schedules.setRecipient(schedule.id, recipient.id)
            val usedAt = schedule.lastRunAtMillis ?: schedule.createdAtMillis
            if (usedAt > recipient.lastUsedAtMillis) recipients.update(recipient.copy(lastUsedAtMillis = usedAt, useCount = recipient.useCount + 1))
        }
    }

    companion object {
        const val RECENT_CHIPS = 8
    }
}
