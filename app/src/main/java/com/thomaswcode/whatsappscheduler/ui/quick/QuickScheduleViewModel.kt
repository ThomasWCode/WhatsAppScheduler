package com.thomaswcode.whatsappscheduler.ui.quick

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.thomaswcode.whatsappscheduler.data.SentWhileEditing
import com.thomaswcode.whatsappscheduler.data.contacts.ContactHit
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.settings.Settings
import com.thomaswcode.whatsappscheduler.domain.ChatCheck
import com.thomaswcode.whatsappscheduler.domain.FriendlyTime
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
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

    /** The checks started together have all finished, after their [Checked] results. */
    data object ChecksDone : QuickEvent
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

    private val whileShown = SharingStarted.WhileSubscribed(5_000)
    val recents: StateFlow<List<Recipient>> = container.recipients.observeRecent().stateIn(viewModelScope, whileShown, emptyList())
    val settings: StateFlow<Settings> = container.settings.settings.stateIn(viewModelScope, whileShown, Settings())

    /** The To field's type-ahead, shared with the full editor. */
    private val search = RecipientSearch(
        container, viewModelScope, query = { query }, setQuery = { query = it },
        countryIso = settings.map { it.defaultCountryIso }, currentCountryIso = { settings.value.defaultCountryIso },
    )
    val contactHits: List<ContactHit> get() = search.contactHits
    val savedHits: List<Recipient> get() = search.savedHits
    val contactsAllowed: Boolean get() = search.contactsAllowed

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

    fun chips(now: ZonedDateTime = this.now.value, time: (LocalTime) -> String = { it.toString() }): List<TimeChip> =
        QuickTimes.suggest(now, settings.value.morning, settings.value.evening, time)

    /** The moment the draft would be sent, or null when no (still valid) time is chosen. */
    fun resolvedTime(now: ZonedDateTime = this.now.value): ZonedDateTime? = when (val t = draft.time) {
        is TimeChoice.Chip -> chips(now).firstOrNull { it.kind == t.kind }?.at
        is TimeChoice.Exact -> Instant.ofEpochMilli(t.atMillis).atZone(now.zone)
        null -> null
    }

    /**
     * The check offer (features F4) from the last message scheduled, until it is answered. A host that
     * closes with the sheet, the Share target, asks it itself: it is set as the sheet closes, so it is
     * there before that host could finish, and saved, so it is still asked after the process was killed
     * meanwhile. The app's list offers it under the Scheduled snackbar instead.
     */
    var pendingCheckOffers: List<RecipientDraft> by handle.saveable(stateSaver = RecipientsSaver) { mutableStateOf(emptyList()) }
        private set

    /** How many check runs started here are still going: they outlive the sheet, but not the process. */
    private val runningChecks = AtomicInteger()

    /** Checks started here are running (features F4), so a host that closes with the sheet can wait for them. */
    var checksRunning by mutableStateOf(false)
        private set

    /** Checks the offered chats, or not; either way the offer is answered. */
    fun answerCheckOffer(check: Boolean) {
        val chats = pendingCheckOffers
        pendingCheckOffers = emptyList()
        if (check && chats.isNotEmpty()) checkChats(chats)
    }

    /** Shows the sheet for a new message, bringing back a draft left by swiping it away. */
    fun openNew() {
        if (draft.isEditing) draft = QuickDraft()
        pendingCheckOffers = emptyList()
        error = null
        visible = true
    }

    /** Text shared from another app; the sheet opens on the recipient. */
    fun openShared(text: String) {
        draft = QuickDraft(message = text)
        pendingCheckOffers = emptyList()
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
                    editingRuns = members.map { it.lastRunAtMillis ?: -1L },
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
        pendingCheckOffers = emptyList()
        query = ""
        error = null
        visible = true
    }

    /** The stored recipient a schedule went to, or its own copy of the target when it isn't linked. */
    private suspend fun recipientOf(schedule: Schedule): RecipientDraft = container.recipientDraftFor(schedule)

    /**
     * Members that went out (or may have) while the sheet was open: nothing was saved, and they leave
     * the edit, chips and all, so saving again changes only what is still to go. Sending it to them
     * again is then a choice the user makes by adding them back.
     */
    private fun sentWhileEditing(sent: List<Schedule>) {
        val ids = sent.map { it.id }.toSet()
        val chats = sent.map { RecipientDraft(it.targetKind, it.targetName, phoneE164 = it.phoneE164, recipientId = it.recipientId) }
        val kept = draft.editingIds.indices.filter { draft.editingIds[it] !in ids }
        draft = draft.copy(
            recipients = draft.recipients.filterNot { chip -> chats.any { chip.sameChatAs(it) } },
            editingIds = kept.map { draft.editingIds[it] },
            editingRuns = if (draft.editingRuns.size == draft.editingIds.size) kept.map { draft.editingRuns[it] } else emptyList(),
        )
        error = "It went to ${sent.joinToString { it.targetName }} while you were editing, so nothing was saved." +
            if (draft.isEditing) " Save again to change the rest." else ""
    }

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
        search.update(text)
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

    fun contactsPermissionChanged() = search.permissionChanged()

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
        val picked = when (val input = RecipientInput.fromContact(hit, settings.value.defaultCountryIso)) {
            is RecipientInput.Refused -> return run { error = input.reason }
            is RecipientInput.Chosen -> input.recipient
        }
        val e164 = picked.phoneE164 ?: return
        // At once, so a Schedule tapped straight after can't leave it out; saving resolves it by number
        // anyway. The lookup only shows sooner what is known about the chat, such as Chat Lock.
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
        val typedChat = when (val input = RecipientInput.fromTyped(text, settings.value.defaultCountryIso)) {
            null -> return
            is RecipientInput.Refused -> return run { error = input.reason }
            is RecipientInput.Chosen -> input.recipient
        }
        add(typedChat)
        // A group or chat name has nothing to look up by number.
        val e164 = typedChat.phoneE164 ?: return
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
                runCatching { container.saveRecipientDetails(original, updated) }
            }
            saving = false
            outcome
                .onSuccess { saved ->
                    if (saved is DetailsSaved.Refused) {
                        val what = if (updated.phoneE164 != null) "that number" else "that name"
                        error = "${saved.existing.displayName} already has $what. Choose ${saved.existing.displayName} instead."
                    } else {
                        applyToDraft()
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
            val removed = runCatching {
                withContext(NonCancellable) {
                    container.linkRecipientsOnce()
                    container.recipients.get(id)?.let { container.recipients.remove(it) }
                }
            }
                .onFailure { container.log.error("Removing a recipient failed", it) }
            saving = false
            // The chip stays linked unless it really went: a failure would otherwise look like success.
            if (removed.isFailure) {
                error = "Couldn't remove ${recipient.displayName} from recents. Try again."
                return@launch
            }
            draft = draft.copy(recipients = draft.recipients.map { if (it.recipientId == id) it.copy(recipientId = null) else it })
        }
    }

    /**
     * "Check this chat now" (features F4) for a chat found by name: WhatsApp's search is run for it
     * while the user watches, nothing typed. It outlives this screen, which WhatsApp covers meanwhile.
     */
    fun checkChat(recipient: RecipientDraft) = checkChats(listOf(recipient))

    /**
     * [checkChat] for each of [chats] in turn. A name that isn't found says nothing about the next one,
     * but a check that couldn't run, or that WhatsApp failed, would most likely go the same way again,
     * so the rest are left. [QuickEvent.ChecksDone] follows the results.
     */
    fun checkChats(chats: List<RecipientDraft>) {
        checksRunning = runningChecks.incrementAndGet() > 0
        container.appScope.launch {
            try {
                for (recipient in chats) {
                    val result = checkOne(recipient)
                    if ((result is ChatCheck.NotChecked && result.stopsOthers) || result is ChatCheck.Failed) break
                }
            } finally {
                events.trySend(QuickEvent.ChecksDone)
                checksRunning = runningChecks.decrementAndGet() > 0
            }
        }
    }

    /** Checks [recipient] and reports what it found. */
    private suspend fun checkOne(recipient: RecipientDraft): ChatCheck {
        val outcome = try {
            container.chatChecker.check(recipient)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Anything the steps don't turn into a result, such as a node gone while WhatsApp changed
            // windows, or the database: a check that failed, rather than the app crashing.
            container.log.error("Checking a chat failed", e)
            CheckOutcome(ChatCheck.Failed(e.message ?: e.javaClass.simpleName), recipient.recipientId, recipient.chatName)
        }
        // The name as searched: the chat's own, which may have been edited since this was offered.
        val name = outcome.name
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
        return outcome.result
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
                    val resolved = d.recipients.map { container.recipients.resolve(it) }.distinctBy { it.phoneE164 ?: "name:${it.chatName.trim().lowercase()}" }
                    val members = d.editingIds.mapNotNull { container.repository.get(it) }
                    val template = Schedule(
                        message = d.message.trim(), targetKind = TargetKind.CONTACT, targetName = "", phoneE164 = null, isLocked = false,
                        firstAtMillis = at.toInstant().toEpochMilli(), zoneId = ZoneId.systemDefault().id,
                        recurrence = RecurrenceCodec.encode(Recurrence.Once), nextAtMillis = null,
                    )
                    // Before markUsed below, which would make them look used: named chats never used before
                    // are the ones to offer checking, every one of them.
                    checkOffers = resolved.filter { it.phoneE164 == null && !it.isLocked && it.lastUsedAtMillis == 0L && it.verifiedAtMillis == null }.map { it.toDraft() }
                    val change = container.repository.saveBatch(template, resolved, members, lastRunsSeen = d.lastRunsSeen())
                    // The rows exist from here on: if arming them fails, Save again must update these rows
                    // rather than add a second batch, which would send the message twice.
                    committed = true
                    draft = draft.copy(editingIds = change.saved.map { it.id }, editingRuns = change.saved.map { it.lastRunAtMillis ?: -1L })
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
                    pendingCheckOffers = checkOffers
                    events.trySend(QuickEvent.Scheduled(summary, change.saved.map { it.id }.takeUnless { d.isEditing }, checkOffers))
                    draft = QuickDraft()
                    query = ""
                    visible = false
                }
                .onFailure {
                    if (it is SentWhileEditing) {
                        sentWhileEditing(it.members)
                        return@onFailure
                    }
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

    /** "Turn off" on the test-mode warning (B11). */
    fun turnOffDryRun() {
        viewModelScope.launch { container.settings.update { it.copy(dryRunUntilMillis = null) } }
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
