package com.thomaswcode.whatsappscheduler.ui.quick

import androidx.compose.runtime.saveable.listSaver
import com.thomaswcode.whatsappscheduler.data.RecipientDraft
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.domain.TimeChip

/** The chosen send time: a chip, resolved again whenever it is shown or used, or an exact moment. */
sealed interface TimeChoice {
    /** "In 30 min" stays thirty minutes from now until Schedule is tapped. */
    data class Chip(val kind: TimeChip.Kind) : TimeChoice

    data class Exact(val atMillis: Long) : TimeChoice
}

/**
 * Everything in the quick sheet. Kept in the SavedStateHandle, so it survives rotation, process
 * death and the sheet being swiped away; the next + brings it back (quick-schedule.md §5).
 */
data class QuickDraft(
    val recipients: List<RecipientDraft> = emptyList(),
    val message: String = "",
    val time: TimeChoice? = null,
    /** The one-off, or the members of a batch, being edited; empty for a new message. */
    val editingIds: List<Long> = emptyList(),
) {
    val isEditing: Boolean get() = editingIds.isNotEmpty()

    companion object {
        /** Bundle-safe values only. */
        val Saver = listSaver<QuickDraft, Any>(
            save = { draft ->
                listOf(
                    ArrayList(draft.recipients.map { r ->
                        arrayListOf(r.kind.name, r.displayName, r.whatsappName ?: "", r.phoneE164 ?: "", r.isLocked.toString(), (r.recipientId ?: -1L).toString())
                    }),
                    draft.message,
                    when (val t = draft.time) {
                        is TimeChoice.Chip -> "chip:${t.kind.name}"
                        is TimeChoice.Exact -> "at:${t.atMillis}"
                        null -> ""
                    },
                    ArrayList(draft.editingIds),
                )
            },
            restore = { values ->
                @Suppress("UNCHECKED_CAST")
                QuickDraft(
                    recipients = (values[0] as List<List<String>>).map { r ->
                        RecipientDraft(
                            kind = TargetKind.valueOf(r[0]),
                            displayName = r[1],
                            whatsappName = r[2].ifEmpty { null },
                            phoneE164 = r[3].ifEmpty { null },
                            isLocked = r[4].toBoolean(),
                            recipientId = r[5].toLong().takeIf { it >= 0 },
                        )
                    },
                    message = values[1] as String,
                    time = (values[2] as String).let { t ->
                        when {
                            t.startsWith("chip:") -> TimeChip.Kind.entries.firstOrNull { it.name == t.removePrefix("chip:") }?.let(TimeChoice::Chip)
                            t.startsWith("at:") -> TimeChoice.Exact(t.removePrefix("at:").toLong())
                            else -> null
                        }
                    },
                    editingIds = values[3] as List<Long>,
                )
            },
        )
    }
}

/** A stored recipient as a draft, e.g. when its recent chip is tapped. */
fun Recipient.toDraft(): RecipientDraft = RecipientDraft(kind, displayName, whatsappName, phoneE164, isLocked, id)

/**
 * The recent chips to offer: leaves out anyone already chosen, however they were chosen (recent,
 * contact or typed), and the user's own chat while the pinned Me chip ([me]) stands for it.
 */
fun availableRecents(recents: List<Recipient>, chosen: List<RecipientDraft>, me: RecipientDraft?): List<Recipient> =
    recents.filter { recent ->
        val draft = recent.toDraft()
        chosen.none { it.sameChatAs(draft) } && me?.sameChatAs(draft) != true
    }

/** Keeps the first of any that are the same chat, so one message can't go to a chat twice. */
fun List<RecipientDraft>.distinctChats(): List<RecipientDraft> =
    fold(emptyList()) { kept, recipient -> if (kept.any { it.sameChatAs(recipient) }) kept else kept + recipient }

/**
 * True when [other] would be the same chat as this draft: same stored recipient, same number, or same
 * named chat whatever the case, since WhatsApp's search falls back to a case-insensitive match.
 */
fun RecipientDraft.sameChatAs(other: RecipientDraft): Boolean = when {
    recipientId != null && recipientId == other.recipientId -> true
    phoneE164 != null -> phoneE164 == other.phoneE164
    else -> other.phoneE164 == null && kind == other.kind && chatName.trim().equals(other.chatName.trim(), ignoreCase = true)
}
