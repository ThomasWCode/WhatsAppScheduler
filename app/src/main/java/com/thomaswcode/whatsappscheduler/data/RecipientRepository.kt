package com.thomaswcode.whatsappscheduler.data

import androidx.room.withTransaction
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.data.db.Schedule
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

    suspend fun byPhone(phoneE164: String): Recipient? = recipients.byPhone(phoneE164)

    /**
     * The stored recipient for [draft]: the one it was picked from, else the one with the same number,
     * else (for chats without a number) the same kind and chat name, else a new one. Picking someone
     * again therefore keeps what was learned about them, such as Chat Lock.
     */
    suspend fun resolve(draft: RecipientDraft): Recipient = db.withTransaction {
        existing(draft)
            ?: Recipient(
                kind = draft.kind,
                displayName = draft.displayName.trim(),
                whatsappName = draft.whatsappName?.trim()?.takeIf { it.isNotEmpty() && it != draft.displayName.trim() },
                phoneE164 = draft.phoneE164,
                isLocked = draft.isLocked,
            ).let { it.copy(id = recipients.insert(it)) }
    }

    /**
     * Another stored recipient that already is the chat [recipient] would point to, e.g. after its
     * number was edited to one saved for someone else; null when there is none. Saving such an edit
     * would leave two identities for one chat, and lookups by number could then pick either.
     */
    suspend fun sameChatElsewhere(recipient: Recipient): Recipient? =
        recipient.phoneE164?.let { recipients.otherByPhone(it, recipient.id) }
            ?: if (recipient.phoneE164 == null) byChatName(recipient.kind, recipient.chatName, except = recipient.id) else null

    /** "Check this chat now" found the chat (features F4). */
    suspend fun markVerified(id: Long, atMillis: Long) = recipients.markVerified(id, atMillis)

    /** A check didn't find the chat: it no longer shows as found. */
    suspend fun clearVerified(id: Long) = recipients.markVerified(id, null)

    /** The stored recipient [draft] already is, if any, without creating one: by id, number, or chat name. */
    suspend fun existing(draft: RecipientDraft): Recipient? =
        draft.recipientId?.let { recipients.get(it) }
            ?: draft.phoneE164?.let { recipients.byPhone(it) }
            ?: if (draft.phoneE164 == null) byChatName(draft.kind, draft.chatName) else null

    /**
     * A recipient found by chat name, whatever the case: WhatsApp's search falls back to a
     * case-insensitive match, so "book club" is the same chat as "Book Club", and "équipe" as "Équipe".
     */
    private suspend fun byChatName(kind: TargetKind, chatName: String, except: Long? = null): Recipient? =
        recipients.namedOfKind(kind).firstOrNull { it.id != except && it.chatName.trim().equals(chatName.trim(), ignoreCase = true) }

    suspend fun markUsed(ids: Collection<Long>, at: Instant = Instant.now()) = db.withTransaction {
        ids.forEach { recipients.markUsed(it, at.toEpochMilli()) }
    }

    /**
     * Saves edited details and copies them into the recipient's schedules that are still to be
     * sent; sent history keeps the details it was actually sent with. Returns those pending schedules
     * as they are now: their alarms and any Tap to send prompts still carry the old target.
     */
    suspend fun updateDetails(recipient: Recipient): List<Schedule> = db.withTransaction {
        // A check found the chat by its old name or kind; after a change it has to be checked again.
        val before = recipients.get(recipient.id)
        val renamed = before != null && (before.chatName != recipient.chatName || before.kind != recipient.kind)
        recipients.update(if (renamed) recipient.copy(verifiedAtMillis = null) else recipient)
        schedules.updatePendingTarget(recipient.id, recipient.kind, recipient.chatName, recipient.phoneE164, recipient.isLocked)
        schedules.pendingFor(recipient.id)
    }

    /** "Remove from recents": forgets the recipient. Its schedules keep their own copy of the details. */
    suspend fun remove(recipient: Recipient) = recipients.delete(recipient)

    /**
     * Links schedules made before recipients existed (schema v1) to a recipient each, so recents and
     * Chat Lock carry over. Runs once per install (MainActivity): afterwards an unlinked schedule is one
     * whose recipient was removed from recents on purpose.
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
