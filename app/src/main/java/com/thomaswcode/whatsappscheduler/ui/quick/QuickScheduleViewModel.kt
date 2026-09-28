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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something the screen hosting the sheet should show or do once. */
sealed interface QuickEvent {
    /** [createdIds] is set for a new message, which can be undone by deleting them. */
    data class Scheduled(val summary: String, val createdIds: List<Long>?) : QuickEvent
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
                RecipientDraft(TargetKind.CONTACT, "Me", stored?.whatsappName, p, stored?.isLocked ?: false, stored?.id)
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
            snapshotFlow { query }.debounce(150).collect { q -> search(q) }
        }
    }

    private suspend fun search(q: String) {
        contactsAllowed = container.contactSearch.hasPermission()
        if (q.isBlank()) {
            contactHits = emptyList()
            savedHits = emptyList()
            return
        }
        contactHits = container.contactSearch.search(q, settings.value.defaultCountryIso)
        savedHits = container.recipients.search(q)
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

    /** Opens the one-off (or batch) in edit mode: the same sheet, titled "Edit message". */
    fun openEdit(schedule: Schedule) {
        viewModelScope.launch {
            val members = schedule.batchId?.let { container.repository.batch(it) }?.ifEmpty { null } ?: listOf(schedule)
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

    fun discard() {
        draft = QuickDraft()
        query = ""
        error = null
        visible = false
    }

    fun updateQuery(text: String) {
        query = text
    }

    fun setMessage(text: String) {
        draft = draft.copy(message = text)
    }

    fun choose(time: TimeChoice) {
        draft = draft.copy(time = time)
        error = null
    }

    fun contactsPermissionChanged() {
        contactsAllowed = container.contactSearch.hasPermission()
        viewModelScope.launch { search(query) }
    }

    /** Adds [recipient] to the To field, unless it is already there; clears the search. */
    fun add(recipient: RecipientDraft) {
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
        val e164 = hit.e164
        if (e164 == null) {
            error = "${hit.number} isn't a number WhatsApp can open. Edit the contact, or type the number with its country code."
            return
        }
        viewModelScope.launch {
            val saved = container.recipients.byPhone(e164)
            if (saved != null) add(saved) else add(RecipientDraft(TargetKind.CONTACT, hit.name, phoneE164 = e164))
        }
    }

    /** "Use "Work crew" as a group or chat name": found by name through WhatsApp's search. */
    fun addChatName(name: String) {
        if (name.isNotBlank()) add(RecipientDraft(TargetKind.GROUP, name.trim()))
    }

    fun remove(recipient: RecipientDraft) {
        draft = draft.copy(recipients = draft.recipients.filterNot { it == recipient })
    }

    /**
     * Recipient details (quick-schedule.md §3): saved to the stored recipient, which also updates its
     * pending schedules, and to the draft.
     */
    fun saveDetails(original: RecipientDraft, updated: RecipientDraft) {
        viewModelScope.launch {
            original.recipientId?.let { container.recipients.get(it) }?.let { stored ->
                container.recipients.updateDetails(
                    stored.copy(whatsappName = updated.whatsappName, phoneE164 = updated.phoneE164, isLocked = updated.isLocked, kind = updated.kind),
                )
                // Pending schedules just changed target, so their alarms are re-armed from the stored rows.
                container.repository.all().filter { it.recipientId == stored.id && it.nextAtMillis != null }.forEach(container.alarmScheduler::sync)
            }
            draft = draft.copy(recipients = draft.recipients.map { if (it == original) updated else it })
        }
    }

    /** "Remove from recents": forgets the stored recipient; a chosen chip stays, as a one-off entry. */
    fun forget(recipient: RecipientDraft) {
        val id = recipient.recipientId ?: return
        viewModelScope.launch {
            container.recipients.get(id)?.let { container.recipients.remove(it) }
            draft = draft.copy(recipients = draft.recipients.map { if (it.recipientId == id) it.copy(recipientId = null) else it })
        }
    }

    /** Recent chips toggle the recipient in or out of the To field. */
    fun toggle(recipient: Recipient) {
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
            !at.isAfter(now.plusSeconds(59)) -> "Pick a time at least a minute from now."
            else -> null
        }
        if (error != null || at == null) return
        saving = true
        viewModelScope.launch {
            var committed = false
            // Not cancelled if the sheet or screen goes away mid-save, which would strand rows without alarms.
            val outcome = withContext(NonCancellable) {
                runCatching {
                    val resolved = d.recipients.map { container.recipients.resolve(it) }
                    val members = d.editingIds.mapNotNull { container.repository.get(it) }
                    val template = Schedule(
                        message = d.message.trim(), targetKind = TargetKind.CONTACT, targetName = "", phoneE164 = null, isLocked = false,
                        firstAtMillis = at.toInstant().toEpochMilli(), zoneId = ZoneId.systemDefault().id,
                        recurrence = RecurrenceCodec.encode(Recurrence.Once), nextAtMillis = null,
                    )
                    val change = container.repository.saveBatch(template, resolved, members)
                    // The rows exist from here on: if arming them fails, Save again must update these rows
                    // rather than add a second batch, which would send the message twice.
                    committed = true
                    draft = draft.copy(editingIds = change.saved.map { it.id })
                    change.saved.forEach { container.alarmScheduler.sync(it); container.fallbackNotifier.cancel(it.id) }
                    change.deleted.forEach { container.alarmScheduler.cancel(it.id); container.fallbackNotifier.cancel(it.id) }
                    // Only orders the recent chips, so it doesn't fail the save.
                    runCatching { container.recipients.markUsed(resolved.map { it.id }) }.onFailure { container.log.error("Marking recipients used failed", it) }
                    change
                }
            }
            saving = false
            outcome
                .onSuccess { change ->
                    val summary = (if (d.isEditing) "Saved: " else "Scheduled: ") + names(d.recipients) + " · " + FriendlyTime.dayAndTime(at, now)
                    events.trySend(QuickEvent.Scheduled(summary, change.saved.map { it.id }.takeUnless { d.isEditing }))
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
                    d.editingIds.mapNotNull { container.repository.get(it) }.forEach {
                        container.alarmScheduler.cancel(it.id)
                        container.repository.delete(it)
                        // Its Tap to send prompt would otherwise outlive it (B10).
                        container.fallbackNotifier.cancel(it.id)
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
                        container.alarmScheduler.cancel(it.id)
                        container.repository.delete(it)
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
        val d = draft
        events.trySend(QuickEvent.OpenFullEditor(d.recipients.firstOrNull(), d.message, resolvedTime()?.toInstant()?.toEpochMilli()))
        discard()
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { QuickScheduleViewModel(container, createSavedStateHandle()) } }

        /** "Mum", "Mum and Dad", "Mum, Dad +1". */
        fun names(recipients: List<RecipientDraft>): String = when (recipients.size) {
            0 -> ""
            1 -> recipients[0].displayName
            2 -> "${recipients[0].displayName} and ${recipients[1].displayName}"
            else -> "${recipients[0].displayName}, ${recipients[1].displayName} +${recipients.size - 2}"
        }
    }
}
