package com.thomaswcode.whatsappscheduler.ui.quick

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.RecipientDraft
import com.thomaswcode.whatsappscheduler.data.contacts.ContactHit
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.PhoneNormaliser
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The To field's type-ahead (quick-schedule.md §3), for the quick sheet and the full editor: contacts
 * and saved recipients matching what is typed, looked up again as it changes. Numbers are read with
 * the default country, so changing it in Settings searches again. The typed text is kept by the owner
 * ([query], [setQuery]), with the rest of its saved state.
 */
@OptIn(FlowPreview::class)
class RecipientSearch(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val query: () -> String,
    private val setQuery: (String) -> Unit,
    countryIso: Flow<String>,
    private val currentCountryIso: () -> String,
) {
    var contactHits by mutableStateOf<List<ContactHit>>(emptyList())
        private set
    var savedHits by mutableStateOf<List<Recipient>>(emptyList())
        private set
    var contactsAllowed by mutableStateOf(container.contactSearch.hasPermission())
        private set

    init {
        scope.launch {
            // collectLatest: a newer query cancels a search still running for an older one.
            combine(snapshotFlow { query() }, countryIso.distinctUntilChanged()) { q, iso -> q to iso }
                .debounce(150)
                .collectLatest { (q, iso) -> search(q, iso) }
        }
    }

    fun update(text: String) {
        // The rows for the old text go at once, rather than staying tappable until the new search is back.
        if (text != query()) {
            contactHits = emptyList()
            savedHits = emptyList()
        }
        setQuery(text)
    }

    fun permissionChanged() {
        contactsAllowed = container.contactSearch.hasPermission()
        scope.launch { search(query(), currentCountryIso()) }
    }

    private suspend fun search(q: String, countryIso: String) {
        contactsAllowed = container.contactSearch.hasPermission()
        if (q.isBlank()) {
            contactHits = emptyList()
            savedHits = emptyList()
            return
        }
        val contacts = container.contactSearch.search(q, countryIso)
        val saved = container.recipients.search(q)
        // Only if the field still shows that query: a result for another one could add the wrong chat.
        if (q != query()) return
        contactHits = contacts
        savedHits = saved
    }
}

/** What the To field makes of a contact picked or text typed: a recipient, or why it can't be one. */
sealed interface RecipientInput {
    data class Chosen(val recipient: RecipientDraft) : RecipientInput

    data class Refused(val reason: String) : RecipientInput

    companion object {
        /** A contact result; a number that doesn't parse is refused on the spot (B06). */
        fun fromContact(hit: ContactHit, countryIso: String): RecipientInput {
            val e164 = PhoneNormaliser.toE164(hit.number, countryIso)
                ?: return Refused("${hit.number} isn't a number WhatsApp can open. Edit the contact, or type the number with its country code.")
            return Chosen(RecipientDraft(TargetKind.CONTACT, hit.name, phoneE164 = e164))
        }

        /**
         * Something typed into To and confirmed. A phone number is a contact reached by that number; one
         * that doesn't parse is refused rather than searched for as a chat name. Anything else is a group
         * or chat found by that exact name through WhatsApp's search. Null when nothing is typed.
         */
        fun fromTyped(text: String, countryIso: String): RecipientInput? {
            val typed = text.trim()
            if (typed.isEmpty()) return null
            if (!QuickScheduleViewModel.looksLikeNumber(typed)) return Chosen(RecipientDraft(TargetKind.GROUP, typed))
            val e164 = PhoneNormaliser.toE164(typed, countryIso) ?: return Refused("$typed isn't a number WhatsApp can open. Type it with its country code.")
            return Chosen(RecipientDraft(TargetKind.CONTACT, PhoneNormaliser.display(e164, countryIso), phoneE164 = e164))
        }
    }
}

/** The stored recipient a schedule went to, or its own copy of the target when it isn't linked. */
suspend fun AppContainer.recipientDraftFor(schedule: Schedule): RecipientDraft =
    schedule.recipientId?.let { recipients.get(it) }?.toDraft()
        ?: RecipientDraft(schedule.targetKind, schedule.targetName, phoneE164 = schedule.phoneE164, isLocked = schedule.isLocked)

/** What saving recipient details did. */
sealed interface DetailsSaved {
    /** Saved to the stored recipient, and its pending schedules. */
    data object Stored : DetailsSaved

    /** Nothing is stored for the chat yet: the details go with its chip until the message is saved. */
    data object NotStored : DetailsSaved

    /** Refused: [existing] already is the chat the edit points to. */
    data class Refused(val existing: Recipient) : DetailsSaved
}

/**
 * Recipient details (quick-schedule.md §3) saved to the stored recipient, which also updates its
 * pending schedules; or not, when nothing is stored for the chat yet, or another recipient already is
 * the chat the edit points to.
 */
suspend fun AppContainer.saveRecipientDetails(original: RecipientDraft, updated: RecipientDraft): DetailsSaved {
    // Also a chip not linked to it yet (a number typed, a lookup still running): saving would resolve it
    // to the stored recipient anyway, and the edit must land there.
    val stored = recipients.existing(original) ?: return DetailsSaved.NotStored
    // Only what the user changed: a chip that isn't linked yet may not know the stored name or Chat Lock,
    // and saving the dialog unchanged mustn't wipe them.
    val edited = stored.copy(
        whatsappName = if (updated.whatsappName != original.whatsappName) updated.whatsappName else stored.whatsappName,
        phoneE164 = if (updated.phoneE164 != original.phoneE164) updated.phoneE164 else stored.phoneE164,
        isLocked = if (updated.isLocked != original.isLocked) updated.isLocked else stored.isLocked,
    )
    // Refused rather than leaving two stored identities for one chat.
    recipients.sameChatElsewhere(edited)?.let { return DetailsSaved.Refused(it) }
    // Pending schedules just changed target: re-armed from the stored rows, and a Tap to send prompt they
    // had would open the chat as it was.
    recipients.updateDetails(edited).forEach { changed ->
        // The prompt first, and each step on its own: a failure re-arming one mustn't leave its prompt
        // pointing at the chat as it was, or skip the others.
        runCatching { fallbackNotifier.cancel(changed.id) }.onFailure { log.error("Clearing a stale prompt failed", it) }
        runCatching { alarmScheduler.sync(changed) }.onFailure { log.error("Re-arming after a details edit failed", it) }
    }
    return DetailsSaved.Stored
}
