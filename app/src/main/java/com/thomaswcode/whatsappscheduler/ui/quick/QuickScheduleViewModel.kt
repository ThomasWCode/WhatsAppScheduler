package com.thomaswcode.whatsappscheduler.ui.quick

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.RecipientDraft
import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.contacts.ContactHit
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.settings.Settings
import com.thomaswcode.whatsappscheduler.domain.ChatCheck
import com.thomaswcode.whatsappscheduler.domain.FriendlyTime
import com.thomaswcode.whatsappscheduler.domain.PhoneNormaliser
import com.thomaswcode.whatsappscheduler.domain.PinFailures
import com.thomaswcode.whatsappscheduler.domain.QuickTimes
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.RecurrenceCodec
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.domain.TimeChip
import com.thomaswcode.whatsappscheduler.sending.accessibility.WaAccessibilityService
import com.thomaswcode.whatsappscheduler.ui.minuteClock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import com.thomaswcode.whatsappscheduler.sending.CheckOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something the screen hosting the sheet should show or do once. */
sealed interface QuickEvent {
    /**
     * [createdIds] is set for a new message, which can be undone by deleting them. [checkOffers] are the
     * chats found by name that were new just now, offered "Check this chat now" once (features F4).
     */
    data class Scheduled(val summary: String, val createdIds: List<Long>?, val checkOffers: List<RecipientDraft> = emptyList()) : QuickEvent

    /** What "Check this chat now" found. */
    data class Checked(val message: String) : QuickEvent
    data class Deleted(val summary: String) : QuickEvent

    /** "Repeat…": continue in the full editor with this recipient, message and time. */
    data class OpenFullEditor(val recipient: RecipientDraft?, val message: String, val atMillis: Long?) : QuickEvent
}

/**
 * The quick sheet (quick-schedule.md §5): who, what and when, for a one-off to one or more
 * recipients. Saving is a single in-flight job, so a double tap can't create two (B05).
 */
@OptIn(SavedStateHandleSaveableApi::class, FlowPreview::class, ExperimentalCoroutinesApi::class)
class QuickScheduleViewModel(private val container: AppContainer, handle: SavedStateHandle) : ViewModel() {

    var draft: QuickDraft by handle.saveable(stateSaver = QuickDraft.Saver) { mutableStateOf(QuickDraft()) }
        private set
    var query: String by handle.saveable { mutableStateOf("") }
        private set

    /** Whether the sheet is showing; saved, so it is still open after a rotation. */
    var visible: Boolean by handle.saveable { mutableStateOf(false) }
        private set

    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var contactHits by mutableStateOf<List<ContactHit>>(emptyList())
        private set
    var savedHits by mutableStateOf<List<Recipient>>(emptyList())
        private set
    var contactsAllowed by mutableStateOf(container.contactSearch.hasPermission())
        private set

    private val whileShown = SharingStarted.WhileSubscribed(5_000)
    val recents: StateFlow<List<Recipient>> = container.recipients.observeRecent().stateIn(viewModelScope, whileShown, emptyList())
    val settings: StateFlow<Settings> = container.settings.settings.stateIn(viewModelScope, whileShown, Settings())

    /**
     * The pinned Me chip (quick-schedule.md §3), when the user's own number is set. It stands for the
     * stored recipient with that number, if any, so it carries Chat Lock; looked up again whenever
     * recipients change.
     */
    val me: StateFlow<RecipientDraft?> = combine(settings.map { it.ownNumberE164 }, recents) { phone, _ -> phone }
        .mapLatest { phone ->
            phone?.let { p ->
                val stored = container.recipients.byPhone(p)
                // "Me" is only the chip's label: the chat keeps its real name, which the Locked chats search
                // and the full editor (Repeat…) need.
                RecipientDraft(TargetKind.CONTACT, "Me", stored?.chatName, p, stored?.isLocked ?: false, stored?.id)
            }
        }
        .stateIn(viewModelScope, whileShown, null)

    // What the sheet's warning row needs (F5): observed, so it updates when the service connects.
    val serviceConnected: StateFlow<Boolean> = WaAccessibilityService.instance.map { it != null }.stateIn(viewModelScope, whileShown, WaAccessibilityService.instance.value != null)
    val hasPin: StateFlow<Boolean> = container.secrets.hasPin().stateIn(viewModelScope, whileShown, true)
    val pinFailures: StateFlow<PinFailures> = container.secrets.observePinFailures().stateIn(viewModelScope, whileShown, PinFailures())

    /** The time, moving on each minute, so the chips and "in 18 h 52 min" stay right while the sheet is open. */
    val now: StateFlow<ZonedDateTime> = minuteClock().stateIn(viewModelScope, whileShown, ZonedDateTime.now())

    private val events = Channel<QuickEvent>(Channel.BUFFERED)
    val eventFlow: Flow<QuickEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            // collectLatest: a newer query cancels a search still running for an older one. Numbers are
            // read with the default country, so changing it in Settings searches again.
            combine(snapshotFlow { query }, settings.map { it.defaultCountryIso }.distinctUntilChanged()) { q, iso -> q to iso }
                .debounce(150)
                .collectLatest { (q, iso) -> search(q, iso) }
        }
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
        if (q != query) return
        contactHits = contacts
        savedHits = saved
    }

    fun chips(now: ZonedDateTime = this.now.value, time: (LocalTime) -> String = { it.toString() }): List<TimeChip> =
        QuickTimes.suggest(now, settings.value.morning, settings.value.evening, time)

    /** The moment the draft would be sent, or null when no (still valid) time is chosen. */
    fun resolvedTime(now: ZonedDateTime = this.now.value): ZonedDateTime? = when (val t = draft.time) {
        is TimeChoice.Chip -> chips(now).firstOrNull { it.kind == t.kind }?.at
        is TimeChoice.Exact -> Instant.ofEpochMilli(t.atMillis).atZone(now.zone)
        null -> null
    }

    /** Shows the sheet for a new message, bringing back a draft left by swiping it away. */
    fun openNew() {
        if (draft.isEditing) draft = QuickDraft()
        error = null
        visible = true
    }

    /** Text shared from another app; the sheet opens on the recipient. */
    fun openShared(text: String) {
        draft = QuickDraft(message = text)
        query = ""
        error = null
        visible = true
    }

    /**
     * Opens a one-off in edit mode: the same sheet, titled "Edit message". [members] are exactly the
     * schedules the row shows. A batch can be split across the list's sections, and editing members
     * that have already gone would schedule them again.
     */
    fun openEdit(members: List<Schedule>) {
        val schedule = members.firstOrNull() ?: return
        viewModelScope.launch {
            show(
                QuickDraft(
                    recipients = members.map { recipientOf(it) },
                    message = schedule.message,
                    time = schedule.nextAtMillis?.let(TimeChoice::Exact),
                    editingIds = members.map { it.id },
                ),
            )
        }
    }

    /**
     * "Send again…" on a sent message (quick-schedule.md §8): a new message to the same people with
     * the same text, and no time chosen yet.
     */
    fun openSendAgain(group: List<Schedule>) {
        viewModelScope.launch {
            show(QuickDraft(recipients = group.map { recipientOf(it) }, message = group.first().message))
        }
    }

    private fun show(newDraft: QuickDraft) {
        draft = newDraft
        query = ""
        error = null
        visible = true
    }

    /** The stored recipient a schedule went to, or its own copy of the target when it isn't linked. */
    private suspend fun recipientOf(schedule: Schedule): RecipientDraft =
        schedule.recipientId?.let { container.recipients.get(it) }?.toDraft()
            ?: RecipientDraft(schedule.targetKind, schedule.targetName, phoneE164 = schedule.phoneE164, isLocked = schedule.isLocked)

    /** Swiping the sheet away keeps the draft for the next time it opens. */
    fun hide() {
        visible = false
    }

    /** Refused while saving: the save would still arm the message, and a failed one could no longer be retried. */
    fun discard() {
        if (saving) return
        draft = QuickDraft()
        query = ""
        error = null
        visible = false
    }

    // While a save runs, the draft is what is being saved: changing it then would show one message and
    // arm another, and the save would clear the change. So edits wait (the sheet disables them too).

    fun updateQuery(text: String) {
        if (saving) return
        // The rows for the old text go at once, rather than staying tappable until the new search is back.
        if (text != query) {
            contactHits = emptyList()
            savedHits = emptyList()
        }
        query = text
    }

    fun setMessage(text: String) {
        if (saving) return
        draft = draft.copy(message = text)
    }

    fun choose(time: TimeChoice) {
        if (saving) return
        draft = draft.copy(time = time)
        error = null
    }

    fun contactsPermissionChanged() {
        contactsAllowed = container.contactSearch.hasPermission()
        viewModelScope.launch { search(query, settings.value.defaultCountryIso) }
    }

    /** Adds [recipient] to the To field, unless it is already there; clears the search. */
    fun add(recipient: RecipientDraft) {
        if (saving) return
        when {
            draft.recipients.any { it.sameChatAs(recipient) } -> Unit
            draft.recipients.size >= ScheduleRepository.MAX_BATCH -> error = "One message can go to at most ${ScheduleRepository.MAX_BATCH} chats."
            else -> draft = draft.copy(recipients = draft.recipients + recipient)
        }
        query = ""
    }

    fun add(recipient: Recipient) = add(recipient.toDraft())

    /**
     * A contact result; a number that doesn't parse is refused on the spot (B06). Someone already
     * saved as a recipient is added as that recipient, so what was learned (Chat Lock) shows at once.
     */
    fun add(hit: ContactHit) {
        // Read with the country set now, not the one the results were found with: it may have changed
        // in Settings since, and the search again may not be back yet.
        val e164 = PhoneNormaliser.toE164(hit.number, settings.value.defaultCountryIso)
        if (e164 == null) {
            error = "${hit.number} isn't a number WhatsApp can open. Edit the contact, or type the number with its country code."
            return
        }
        // At once, so a Schedule tapped straight after can't leave it out; saving resolves it by number
        // anyway. The lookup only shows sooner what is known about the chat, such as Chat Lock.
        val picked = RecipientDraft(TargetKind.CONTACT, hit.name, phoneE164 = e164)
        add(picked)
        viewModelScope.launch {
            val saved = container.recipients.byPhone(e164) ?: return@launch
            if (!saving) draft = draft.copy(recipients = draft.recipients.map { if (it == picked) saved.toDraft() else it })
        }
    }

    /**
     * Something typed into To and confirmed. A phone number is a contact reached by that number; one
     * that doesn't parse is refused rather than searched for as a chat name. Anything else is a group
     * or chat found by that exact name through WhatsApp's search.
     */
    fun addTyped(text: String) {
        if (saving) return
        val typed = text.trim()
        if (typed.isEmpty()) return
        if (!looksLikeNumber(typed)) return add(RecipientDraft(TargetKind.GROUP, typed))
        val iso = settings.value.defaultCountryIso
        val e164 = PhoneNormaliser.toE164(typed, iso)
        if (e164 == null) {
            error = "$typed isn't a number WhatsApp can open. Type it with its country code."
            return
        }
        val typedChat = RecipientDraft(TargetKind.CONTACT, PhoneNormaliser.display(e164, iso), phoneE164 = e164)
        add(typedChat)
        // A number already saved shows what is known about it, as a picked contact does.
        viewModelScope.launch {
            val saved = container.recipients.byPhone(e164) ?: return@launch
            if (!saving) draft = draft.copy(recipients = draft.recipients.map { if (it == typedChat) saved.toDraft() else it })
        }
    }

    fun remove(recipient: RecipientDraft) {
        if (saving) return
        draft = draft.copy(recipients = draft.recipients.filterNot { it == recipient })
    }

    /**
     * Recipient details (quick-schedule.md §3): saved to the stored recipient, which also updates its
     * pending schedules, and to the draft.
     */
    fun saveDetails(original: RecipientDraft, updated: RecipientDraft) {
        if (saving) return
        // The edit may make it the same chat as another one chosen; it is then there once.
        fun applyToDraft() {
            draft = draft.copy(recipients = draft.recipients.map { if (it == original) updated else it }.distinctChats())
        }
        // Counts as saving, so Schedule waits for it: a message saved meanwhile would go to the chat as it was.
        saving = true
        viewModelScope.launch {
            val outcome = withContext(NonCancellable) {
                runCatching {
                    // Also a chip not linked to it yet (a number typed, a lookup still running): saving would
                    // resolve it to the stored recipient anyway, and the edit must land there.
                    val stored = container.recipients.existing(original) ?: return@runCatching null
                    // Only what the user changed: a chip that isn't linked yet may not know the stored name or
                    // Chat Lock, and saving the dialog unchanged mustn't wipe them.
                    val edited = stored.copy(
                        whatsappName = if (updated.whatsappName != original.whatsappName) updated.whatsappName else stored.whatsappName,
                        phoneE164 = if (updated.phoneE164 != original.phoneE164) updated.phoneE164 else stored.phoneE164,
                        isLocked = if (updated.isLocked != original.isLocked) updated.isLocked else stored.isLocked,
                    )
                    // Refused rather than leaving two stored identities for one chat.
                    container.recipients.sameChatElsewhere(edited)?.let { return@runCatching it }
                    // Pending schedules just changed target: re-armed from the stored rows, and a Tap to send
                    // prompt they had would open the chat as it was.
                    container.recipients.updateDetails(edited).forEach { changed ->
                        // The prompt first, and each step on its own: a failure re-arming one mustn't leave its
                        // prompt pointing at the chat as it was, or skip the others.
                        runCatching { container.fallbackNotifier.cancel(changed.id) }.onFailure { container.log.error("Clearing a stale prompt failed", it) }
                        runCatching { container.alarmScheduler.sync(changed) }.onFailure { container.log.error("Re-arming after a details edit failed", it) }
                    }
                    null
                }
            }
            saving = false
            outcome
                .onSuccess { conflict ->
                    if (conflict == null) {
                        applyToDraft()
                    } else {
                        val what = if (updated.phoneE164 != null) "that number" else "that name"
                        error = "${conflict.displayName} already has $what. Choose ${conflict.displayName} instead."
                    }
                }
                .onFailure {
                    container.log.error("Saving recipient details failed", it)
                    error = "Couldn't save the details (${it.message ?: it.javaClass.simpleName}). Try again."
                }
        }
    }

    /** "Remove from recents": forgets the stored recipient; a chosen chip stays, as a one-off entry. */
    fun forget(recipient: RecipientDraft) {
        val id = recipient.recipientId ?: return
        if (saving) return
        saving = true
        viewModelScope.launch {
            runCatching {
                withContext(NonCancellable) {
                    container.linkRecipientsOnce()
                    container.recipients.get(id)?.let { container.recipients.remove(it) }
                }
            }
                .onFailure { container.log.error("Removing a recipient failed", it) }
            saving = false
            draft = draft.copy(recipients = draft.recipients.map { if (it.recipientId == id) it.copy(recipientId = null) else it })
        }
    }

    /**
     * "Check this chat now" (features F4) for a chat found by name: WhatsApp's search is run for it
     * while the user watches, nothing typed. It outlives this screen, which WhatsApp covers meanwhile.
     */
    fun checkChat(recipient: RecipientDraft) = checkChats(listOf(recipient))

    /** [checkChat] for each of [chats] in turn, stopping at one that couldn't be checked, as the rest couldn't either. */
    fun checkChats(chats: List<RecipientDraft>) {
        container.appScope.launch {
            for (recipient in chats) {
                val name = recipient.chatName
                val outcome = try {
                    container.chatChecker.check(recipient)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Anything the steps don't turn into a result, such as a node gone while WhatsApp changed
                    // windows, or the database: a check that failed, rather than the app crashing.
                    container.log.error("Checking a chat failed", e)
                    CheckOutcome(ChatCheck.Failed(e.message ?: e.javaClass.simpleName), recipient.recipientId)
                }
                // A chip for a name typed but not saved is linked to the recipient the check stored.
                if (recipient.recipientId == null && outcome.recipientId != null && !saving) {
                    draft = draft.copy(recipients = draft.recipients.map { if (it == recipient) it.copy(recipientId = outcome.recipientId) else it })
                }
                val message = when (val result = outcome.result) {
                    ChatCheck.Found -> "Found “$name” in WhatsApp."
                    // The step's own reason is in the automation log; here, what to do about it.
                    is ChatCheck.NotFound -> "Couldn't find “$name” in WhatsApp's search. Check the name is exactly as WhatsApp shows it."
                    is ChatCheck.Failed -> "Couldn't check “$name”: WhatsApp didn't respond as expected. Logs under Settings → Diagnostics say where."
                    is ChatCheck.NotChecked -> "Didn't check “$name”: ${result.reason}."
                }
                events.trySend(QuickEvent.Checked(message))
                if (outcome.result is ChatCheck.NotChecked) break
            }
        }
    }

    /** When the chat was last found by a check (features F4), whether or not it is a recent. */
    suspend fun checkedAt(recipient: RecipientDraft): Long? = container.recipients.existing(recipient)?.verifiedAtMillis

    /** Recent chips toggle the recipient in or out of the To field. */
    fun toggle(recipient: Recipient) {
        if (saving) return
        val existing = draft.recipients.firstOrNull { it.recipientId == recipient.id }
        if (existing != null) remove(existing) else add(recipient)
    }

    fun schedule() {
        if (saving) return
        val now = this.now.value.let { if (Instant.now().isAfter(it.toInstant())) ZonedDateTime.now() else it }
        val d = draft
        val at = resolvedTime(now)
        error = when {
            d.recipients.isEmpty() -> "Choose who it's for."
            d.message.isBlank() -> "Enter a message."
            at == null -> "Choose when to send it."
            !isSendable(at, now) -> "Pick a time at least a minute from now."
            else -> null
        }
        if (error != null || at == null) return
        saving = true
        viewModelScope.launch {
            var committed = false
            var checkOffers: List<RecipientDraft> = emptyList()
            // Not cancelled if the sheet or screen goes away mid-save, which would strand rows without alarms.
            val outcome = withContext(NonCancellable) {
                runCatching {
                    // Once per chat, however the recipients were stored, so one chat can't get the message twice.
                    val resolved = d.recipients.map { container.recipients.resolve(it) }.distinctBy { it.phoneE164 ?: "${it.kind}:${it.chatName.trim().lowercase()}" }
                    val members = d.editingIds.mapNotNull { container.repository.get(it) }
                    val template = Schedule(
                        message = d.message.trim(), targetKind = TargetKind.CONTACT, targetName = "", phoneE164 = null, isLocked = false,
                        firstAtMillis = at.toInstant().toEpochMilli(), zoneId = ZoneId.systemDefault().id,
                        recurrence = RecurrenceCodec.encode(Recurrence.Once), nextAtMillis = null,
                    )
                    // Before markUsed below, which would make them look used: named chats never used before
                    // are the ones to offer checking, every one of them.
                    checkOffers = resolved.filter { it.phoneE164 == null && !it.isLocked && it.lastUsedAtMillis == 0L && it.verifiedAtMillis == null }.map { it.toDraft() }
                    val change = container.repository.saveBatch(template, resolved, members)
                    // The rows exist from here on: if arming them fails, Save again must update these rows
                    // rather than add a second batch, which would send the message twice.
                    committed = true
                    draft = draft.copy(editingIds = change.saved.map { it.id })
                    // Removed members first, each on its own: their rows are gone, so a retry couldn't find
                    // them again, and a Tap to send left behind would offer the old message to them.
                    change.deleted.forEach { removed ->
                        runCatching { container.alarmScheduler.cancel(removed.id) }.onFailure { container.log.error("Cancelling a removed member's alarm failed", it) }
                        runCatching { container.fallbackNotifier.cancel(removed.id) }.onFailure { container.log.error("Clearing a removed member's prompt failed", it) }
                    }
                    // Each member's stale prompt first, then its alarm, each on its own: one failure mustn't
                    // leave an old prompt behind or skip the other members. The first failure is reported.
                    var armFailure: Throwable? = null
                    change.saved.forEach { saved ->
                        runCatching { container.fallbackNotifier.cancel(saved.id) }.onFailure { container.log.error("Clearing a prompt failed", it) }
                        runCatching { container.alarmScheduler.sync(saved) }.onFailure { armFailure = armFailure ?: it }
                    }
                    armFailure?.let { throw it }
                    // Only orders the recent chips, so it doesn't fail the save.
                    runCatching { container.recipients.markUsed(resolved.map { it.id }) }.onFailure { container.log.error("Marking recipients used failed", it) }
                    change
                }
            }
            saving = false
            outcome
                .onSuccess { change ->
                    val summary = (if (d.isEditing) "Saved: " else "Scheduled: ") + names(d.recipients) + " · " + FriendlyTime.dayAndTime(at, now)
                    events.trySend(QuickEvent.Scheduled(summary, change.saved.map { it.id }.takeUnless { d.isEditing }, checkOffers))
                    draft = QuickDraft()
                    query = ""
                    visible = false
                }
                .onFailure {
                    container.log.error("Quick schedule failed", it)
                    val reason = it.message ?: it.javaClass.simpleName
                    error = if (committed) "Saved, but its alarm couldn't be set ($reason). Tap Save to try again." else "Couldn't save ($reason). Try again."
                }
        }
    }

    /** Edit mode's Delete: removes the one-off, or every member of its batch. */
    fun delete() {
        val d = draft
        if (!d.isEditing || saving) return
        saving = true
        viewModelScope.launch {
            val outcome = withContext(NonCancellable) {
                runCatching {
                    d.editingIds.mapNotNull { container.repository.get(it) }.forEach { member ->
                        // The row first: if that fails, the alarm must still be there. A stale alarm for a
                        // deleted row is harmless, as the send queue drops it.
                        container.repository.delete(member)
                        // Then its prompt (B10) and alarm, each on its own: a retry can't find the row again.
                        runCatching { container.fallbackNotifier.cancel(member.id) }.onFailure { container.log.error("Clearing a prompt failed", it) }
                        runCatching { container.alarmScheduler.cancel(member.id) }.onFailure { container.log.error("Cancelling an alarm failed", it) }
                    }
                }
            }
            saving = false
            outcome
                .onSuccess {
                    events.trySend(QuickEvent.Deleted("Deleted: " + names(d.recipients)))
                    draft = QuickDraft()
                    visible = false
                }
                .onFailure {
                    container.log.error("Quick delete failed", it)
                    error = "Couldn't delete (${it.message ?: it.javaClass.simpleName}). Try again."
                }
        }
    }

    /** Undo for a new message: deletes what was just created. */
    fun undo(createdIds: List<Long>) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                runCatching {
                    createdIds.mapNotNull { container.repository.get(it) }.forEach {
                        container.repository.delete(it)
                        container.alarmScheduler.cancel(it.id)
                    }
                }.onFailure { container.log.error("Undo failed", it) }
            }
        }
    }

    /**
     * "Repeat…": the full editor takes over with what has been entered so far. The draft goes with
     * it, since a copy left here would come back as a duplicate the next time the sheet opens.
     */
    fun repeat() {
        if (saving) return
        val d = draft
        events.trySend(QuickEvent.OpenFullEditor(d.recipients.firstOrNull(), d.message, resolvedTime()?.toInstant()?.toEpochMilli()))
        discard()
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { QuickScheduleViewModel(container, createSavedStateHandle()) } }

        /** A time far enough ahead to schedule: at least a minute from now. The sheet's button uses it too. */
        fun isSendable(at: ZonedDateTime, now: ZonedDateTime): Boolean = at.isAfter(now.plusSeconds(59))

        /** Digits with the usual separators, at least five of them: a phone number, not a chat name. */
        fun looksLikeNumber(text: String): Boolean =
            text.count(Char::isDigit) >= 5 && text.all { it.isDigit() || it in "+ -()." }

        /** "Mum", "Mum and Dad", "Mum, Dad +1". */
        /** “Book club”; “Book club” and “Work crew”; or “Book club”, “Work crew” and 2 more. */
        fun quoted(chats: List<RecipientDraft>): String {
            val q = chats.map { "“${it.chatName}”" }
            return when (q.size) {
                0 -> ""
                1 -> q[0]
                2 -> "${q[0]} and ${q[1]}"
                else -> "${q[0]}, ${q[1]} and ${q.size - 2} more"
            }
        }

        fun names(recipients: List<RecipientDraft>): String = when (recipients.size) {
            0 -> ""
            1 -> recipients[0].displayName
            2 -> "${recipients[0].displayName} and ${recipients[1].displayName}"
            else -> "${recipients[0].displayName}, ${recipients[1].displayName} +${recipients.size - 2}"
        }
    }
}
