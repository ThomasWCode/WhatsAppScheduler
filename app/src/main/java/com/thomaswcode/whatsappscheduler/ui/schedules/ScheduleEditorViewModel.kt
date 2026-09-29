package com.thomaswcode.whatsappscheduler.ui.schedules

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
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
import com.thomaswcode.whatsappscheduler.data.contacts.ContactHit
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.settings.Settings
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.RecurrenceCodec
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.ui.minuteClock
import com.thomaswcode.whatsappscheduler.ui.quick.RecipientInput
import com.thomaswcode.whatsappscheduler.ui.quick.RecipientSearch
import com.thomaswcode.whatsappscheduler.ui.quick.RecipientsSaver
import com.thomaswcode.whatsappscheduler.ui.quick.recipientDraftFor
import com.thomaswcode.whatsappscheduler.ui.quick.saveRecipientDetails
import com.thomaswcode.whatsappscheduler.ui.quick.toDraft
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The full editor's repeat rules; Once only for a one-off opened here, which the quick sheet otherwise edits. */
enum class RuleKind(val label: String) {
    ONCE("Once"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    HOURS("Every few hours"),
    DAYS("Every few days"),
}

/** Everything typed into the full editor, kept as plain values so it can be saved across recreation (B17). */
data class EditorForm(
    /** Who it goes to. Its name, number and Chat Lock are the recipient's own (R5, R8): set in its details. */
    val recipient: RecipientDraft? = null,
    val message: String = "",
    val date: LocalDate,
    val time: LocalTime,
    val ruleKind: RuleKind = RuleKind.DAILY,
    val weekdays: Set<DayOfWeek>,
    val monthDay: String,
    val every: String = "1",
) {
    /** A part of the form that can be wrong. */
    enum class Field { RECIPIENT, MESSAGE, RULE }

    fun rule(): Recurrence = when (ruleKind) {
        RuleKind.ONCE -> Recurrence.Once
        RuleKind.DAILY -> Recurrence.Daily
        RuleKind.WEEKLY -> Recurrence.Weekly(weekdays)
        RuleKind.MONTHLY -> Recurrence.Monthly(monthDay.toIntOrNull() ?: 0)
        RuleKind.HOURS -> Recurrence.EveryNHours(every.toIntOrNull() ?: 0)
        RuleKind.DAYS -> Recurrence.EveryNDays(every.toIntOrNull() ?: 0)
    }

    /**
     * What stands in the way of saving, by field (improvements I7). The screen shows a rule's as they are
     * typed, and the rest once Save has been tried, rather than scolding an empty form.
     */
    fun problems(): Map<Field, String> = buildMap {
        if (recipient == null) put(Field.RECIPIENT, "Choose who it's for.")
        if (message.isBlank()) put(Field.MESSAGE, "Enter a message.")
        when (ruleKind) {
            RuleKind.WEEKLY -> if (weekdays.isEmpty()) put(Field.RULE, "Pick at least one day.")
            RuleKind.MONTHLY -> if (monthDay.toIntOrNull() !in 1..31) put(Field.RULE, "A day from 1 to 31.")
            RuleKind.HOURS -> if ((every.toIntOrNull() ?: 0) < 1) put(Field.RULE, "At least 1 hour.")
            RuleKind.DAYS -> if ((every.toIntOrNull() ?: 0) < 1) put(Field.RULE, "At least 1 day.")
            RuleKind.ONCE, RuleKind.DAILY -> Unit
        }
    }

    companion object {
        /**
         * A new message five minutes from [now], repeating daily. Date and time come from one moment, so at
         * 23:57 the default is tomorrow 00:02 rather than a time today that has already passed (B23).
         */
        fun blank(now: ZonedDateTime): EditorForm {
            val at = now.plusMinutes(5).withSecond(0).withNano(0)
            return EditorForm(date = at.toLocalDate(), time = at.toLocalTime(), weekdays = setOf(at.dayOfWeek), monthDay = at.dayOfMonth.toString())
        }

        /** [schedule] as it is stored, going to [recipient]. */
        fun from(schedule: Schedule, recipient: RecipientDraft): EditorForm {
            val first = Instant.ofEpochMilli(schedule.firstAtMillis).atZone(ZoneId.of(schedule.zoneId))
            val base = EditorForm(
                recipient = recipient,
                message = schedule.message,
                date = first.toLocalDate(),
                time = first.toLocalTime(),
                weekdays = setOf(first.dayOfWeek),
                monthDay = first.dayOfMonth.toString(),
            )
            return when (val r = schedule.rule) {
                Recurrence.Once -> base.copy(ruleKind = RuleKind.ONCE)
                Recurrence.Daily -> base.copy(ruleKind = RuleKind.DAILY)
                is Recurrence.Weekly -> base.copy(ruleKind = RuleKind.WEEKLY, weekdays = r.days)
                is Recurrence.Monthly -> base.copy(ruleKind = RuleKind.MONTHLY, monthDay = r.dayOfMonth.toString())
                is Recurrence.EveryNHours -> base.copy(ruleKind = RuleKind.HOURS, every = r.hours.toString())
                is Recurrence.EveryNDays -> base.copy(ruleKind = RuleKind.DAYS, every = r.days.toString())
            }
        }

        /** Bundle-safe values only: the recipient as the quick sheet saves its chips, epoch day, second of day, day numbers. */
        val Saver = listSaver<EditorForm, Any>(
            save = {
                listOf(
                    // No one chosen saves as an empty list: listSaver saves an empty one as nothing.
                    with(RecipientsSaver) { save(listOfNotNull(it.recipient)) } ?: ArrayList<ArrayList<String>>(), it.message, it.date.toEpochDay(), it.time.toSecondOfDay(),
                    it.ruleKind.name, ArrayList(it.weekdays.map(DayOfWeek::getValue)), it.monthDay, it.every,
                )
            },
            restore = {
                @Suppress("UNCHECKED_CAST")
                EditorForm(
                    recipient = RecipientsSaver.restore(it[0] as ArrayList<ArrayList<String>>)?.firstOrNull(),
                    message = it[1] as String,
                    date = LocalDate.ofEpochDay(it[2] as Long),
                    time = LocalTime.ofSecondOfDay((it[3] as Int).toLong()),
                    ruleKind = RuleKind.valueOf(it[4] as String),
                    weekdays = (it[5] as List<Int>).map(DayOfWeek::of).toSet(),
                    monthDay = it[6] as String,
                    every = it[7] as String,
                )
            },
        )
    }
}

/**
 * The full editor's state and actions (T2), for repeating messages (improvements I7). The form lives in
 * the [SavedStateHandle], so rotation, a theme change at sunset or process death keep what was typed,
 * and an existing schedule is only loaded into it once (B17). Saving is a single in-flight job (B05).
 */
@OptIn(SavedStateHandleSaveableApi::class)
class ScheduleEditorViewModel(private val container: AppContainer, handle: SavedStateHandle) : ViewModel() {

    val scheduleId: Long = handle.get<Long>("id") ?: 0L
    private val zone: ZoneId = ZoneId.systemDefault()
    private val openedAt: ZonedDateTime = ZonedDateTime.now(zone)

    /** What the quick sheet's Repeat… handed over, for a new schedule. */
    private val prefill: EditorForm? = if (scheduleId != 0L) null else prefillFrom(handle, zone, openedAt)

    var form: EditorForm by handle.saveable(stateSaver = EditorForm.Saver) { mutableStateOf(prefill ?: EditorForm.blank(openedAt)) }
        private set

    /** The form as it was before any change here: leaving with anything else asks first (I7). */
    private var initial: EditorForm by handle.saveable(stateSaver = EditorForm.Saver) { mutableStateOf(EditorForm.blank(openedAt)) }

    /** Whether [form] already holds the stored schedule; saved with it, so a recreate never reloads over edits. */
    private var formLoaded: Boolean by handle.saveable { mutableStateOf(scheduleId == 0L) }

    /** The stored row being edited: kept fields (enabled, attention, …) come from here. Reloaded, not saved. */
    private var existing: Schedule? = null

    var ready by mutableStateOf(false)
        private set
    var countryIso by mutableStateOf("GB")
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Save was tried: from then on every field says what is missing, not only the rule's. */
    var triedSaving by mutableStateOf(false)
        private set

    /** Whether leaving now would lose something: a change to the message, or a new one not saved. */
    val unsaved: Boolean get() = form != initial

    var query: String by handle.saveable { mutableStateOf("") }
        private set

    private val whileShown = SharingStarted.WhileSubscribed(5_000)
    private val settings: StateFlow<Settings> = container.settings.settings.stateIn(viewModelScope, whileShown, Settings())

    /** The To field's type-ahead, the quick sheet's (I7). */
    val search = RecipientSearch(
        container, viewModelScope, query = { query }, setQuery = { query = it },
        countryIso = settings.map { it.defaultCountryIso }, currentCountryIso = { settings.value.defaultCountryIso },
    )
    val recents: StateFlow<List<Recipient>> = container.recipients.observeRecent().stateIn(viewModelScope, whileShown, emptyList())

    /** The time, moving on each minute, so the rule's "next …" stays right while the editor is open. */
    val now: StateFlow<ZonedDateTime> = minuteClock().stateIn(viewModelScope, whileShown, openedAt)

    private val doneEvents = Channel<Unit>(Channel.CONFLATED)

    /** Emits once the schedule has been saved and its alarm armed, or deleted. */
    val done: Flow<Unit> = doneEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            countryIso = container.settings.current().defaultCountryIso
            if (scheduleId != 0L) {
                existing = container.repository.get(scheduleId)
                if (!formLoaded) {
                    existing?.let { stored ->
                        form = EditorForm.from(stored, container.recipientDraftFor(stored))
                        initial = form
                    }
                    formLoaded = true
                }
            } else {
                // A recipient handed over by Repeat… as the stored one it is, with what was learned about it.
                val handed = prefill?.recipient
                if (handed != null && form.recipient == handed) {
                    runCatching { container.recipients.existing(handed) }.getOrNull()?.let { stored ->
                        if (form.recipient == handed) form = form.copy(recipient = stored.toDraft())
                    }
                }
            }
            ready = true
        }
    }

    fun update(transform: EditorForm.() -> EditorForm) {
        if (saving) return
        form = form.transform()
    }

    fun updateQuery(text: String) {
        if (saving) return
        search.update(text)
    }

    /** One recipient: choosing another replaces it; the search is cleared. */
    fun choose(recipient: RecipientDraft) {
        if (saving) return
        form = form.copy(recipient = recipient)
        query = ""
        error = null
    }

    fun clearRecipient() = update { copy(recipient = null) }

    /** A contact result or one from the system picker; someone already saved is taken as that recipient. */
    fun add(hit: ContactHit) {
        val picked = when (val input = RecipientInput.fromContact(hit, countryIso)) {
            is RecipientInput.Refused -> return run { error = input.reason }
            is RecipientInput.Chosen -> input.recipient
        }
        choose(picked)
        linkStored(picked)
    }

    fun addTyped(text: String) {
        val typed = when (val input = RecipientInput.fromTyped(text, countryIso)) {
            null -> return
            is RecipientInput.Refused -> return run { error = input.reason }
            is RecipientInput.Chosen -> input.recipient
        }
        choose(typed)
        linkStored(typed)
    }

    /** Shows what is known about [draft]'s chat, such as Chat Lock, once the stored one is found. */
    private fun linkStored(draft: RecipientDraft) {
        val phone = draft.phoneE164 ?: return
        viewModelScope.launch {
            val stored = container.recipients.byPhone(phone) ?: return@launch
            if (!saving && form.recipient == draft) form = form.copy(recipient = stored.toDraft())
        }
    }

    /**
     * Recipient details (quick-schedule.md §3): saved to the stored recipient, which also updates its
     * other pending schedules, and to the form.
     */
    fun saveDetails(original: RecipientDraft, updated: RecipientDraft) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            val outcome = withContext(NonCancellable) { runCatching { container.saveRecipientDetails(original, updated) } }
            saving = false
            outcome
                .onSuccess { conflict ->
                    if (conflict == null) {
                        if (form.recipient == original) form = form.copy(recipient = updated)
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

    /** "Remove from recents": forgets the stored recipient; this message still goes to the chat. */
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
            }.onFailure { container.log.error("Removing a recipient failed", it) }
            saving = false
            if (form.recipient?.recipientId == id) form = form.copy(recipient = form.recipient?.copy(recipientId = null))
        }
    }

    fun save() {
        if (saving) return
        triedSaving = true
        val f = form
        val recipientDraft = f.recipient
        if (f.problems().isNotEmpty() || recipientDraft == null) return
        val rule = f.rule()
        val firstAt = ZonedDateTime.of(f.date, f.time, zone).toInstant()
        if (rule == Recurrence.Once && !firstAt.isAfter(Instant.now())) {
            error = "A one-off message must be in the future."
            return
        }
        error = null
        val template = existing ?: Schedule(
            message = "", targetKind = TargetKind.CONTACT, targetName = "", phoneE164 = null, isLocked = false,
            firstAtMillis = 0L, zoneId = zone.id, recurrence = "ONCE", nextAtMillis = null,
        )
        saving = true
        viewModelScope.launch {
            // Not cancelled if the screen closes mid-save, which would leave the row without its alarm.
            val outcome = withContext(NonCancellable) {
                runCatching {
                    // The stored recipient, so it shows in the quick sheet's recents; the chat's own name is
                    // what WhatsApp's search needs, and its Chat Lock is the chat's.
                    val recipient = container.recipients.resolve(recipientDraft)
                    val saved = container.repository.save(
                        // Enabled as it was (B21): only the switch on the list changes that.
                        template.copy(
                            message = f.message.trim(),
                            targetKind = recipient.kind,
                            targetName = recipient.chatName,
                            phoneE164 = recipient.phoneE164,
                            isLocked = recipient.isLocked,
                            recipientId = recipient.id,
                            firstAtMillis = firstAt.toEpochMilli(),
                            zoneId = zone.id,
                            recurrence = RecurrenceCodec.encode(rule),
                            // A one-off saved with a time is dealt with; a series' attention is about a past occurrence.
                            attention = if (rule == Recurrence.Once) null else existing?.attention,
                        ),
                    )
                    // If a later step fails, Save again updates this row rather than inserting another.
                    existing = saved
                    runCatching { container.recipients.markUsed(listOf(recipient.id)) }.onFailure { container.log.error("Marking a recipient used failed", it) }
                    // A prompt for the old version of the message is stale now (B10); cleared first, so a
                    // failure arming the alarm can't leave it behind.
                    runCatching { container.fallbackNotifier.cancel(saved.id) }.onFailure { container.log.error("Clearing a prompt failed", it) }
                    container.alarmScheduler.sync(saved)
                }
            }
            outcome
                .onSuccess { doneEvents.trySend(Unit) }
                .onFailure {
                    container.log.error("Saving schedule failed", it)
                    error = "Couldn't save (${it.message ?: it.javaClass.simpleName}). Try again."
                    saving = false
                }
        }
    }

    /** Delete, after the screen asked: the row, then its prompt and alarm. */
    fun delete() {
        val schedule = existing ?: return
        if (saving) return
        saving = true
        viewModelScope.launch {
            val outcome = withContext(NonCancellable) {
                runCatching {
                    // The row first: if that fails, the alarm must still be there. A stale alarm for a deleted
                    // row is harmless, as the send queue drops it.
                    container.repository.delete(schedule)
                    runCatching { container.fallbackNotifier.cancel(schedule.id) }.onFailure { container.log.error("Clearing a prompt failed", it) }
                    runCatching { container.alarmScheduler.cancel(schedule.id) }.onFailure { container.log.error("Cancelling an alarm failed", it) }
                }
            }
            outcome
                .onSuccess { doneEvents.trySend(Unit) }
                .onFailure {
                    container.log.error("Deleting schedule failed", it)
                    error = "Couldn't delete (${it.message ?: it.javaClass.simpleName}). Try again."
                    saving = false
                }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ScheduleEditorViewModel(container, createSavedStateHandle()) }
        }

        /**
         * The form Repeat… asks for: the sheet's recipient, message and time, repeating daily as a
         * starting point (the chips offer the rest). Null when nothing was handed over.
         */
        fun prefillFrom(handle: SavedStateHandle, zone: ZoneId, now: ZonedDateTime = ZonedDateTime.now(zone)): EditorForm? {
            val name = handle.get<String>("name")
            val message = handle.get<String>("message")
            val at = handle.get<Long>("at")?.takeIf { it > 0 }
            if (name == null && message == null && at == null) return null
            val blank = EditorForm.blank(now)
            val first = at?.let { Instant.ofEpochMilli(it).atZone(zone) }
            val recipient = name?.let {
                RecipientDraft(
                    kind = handle.get<String>("kind")?.let { k -> TargetKind.entries.firstOrNull { it.name == k } } ?: TargetKind.CONTACT,
                    displayName = it,
                    phoneE164 = handle.get<String>("phone"),
                    isLocked = handle.get<Boolean>("locked") ?: false,
                )
            }
            return blank.copy(
                recipient = recipient,
                message = message ?: "",
                date = first?.toLocalDate() ?: blank.date,
                time = first?.toLocalTime() ?: blank.time,
                ruleKind = RuleKind.DAILY,
                weekdays = setOf((first ?: now).dayOfWeek),
                monthDay = (first ?: now).dayOfMonth.toString(),
            )
        }
    }
}
