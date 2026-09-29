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
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.PhoneNormaliser
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.RecurrenceCodec
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RuleKind { ONCE, DAILY, WEEKLY, MONTHLY, HOURS, DAYS }

/** Everything typed into the editor, kept as plain values so it can be saved across recreation (B17). */
data class EditorForm(
    val kind: TargetKind = TargetKind.CONTACT,
    val name: String = "",
    val phone: String = "",
    val locked: Boolean = false,
    val message: String = "",
    val date: LocalDate,
    val time: LocalTime,
    val ruleKind: RuleKind = RuleKind.ONCE,
    val weekdays: Set<DayOfWeek>,
    val monthDay: String,
    val every: String = "1",
    /** The name the contact picker last filled in, so picking someone else replaces it (B22). */
    val pickedName: String? = null,
    /** The user flipped the Chat Lock switch here, rather than it coming from the schedule or its chat. */
    val lockedTouched: Boolean = false,
    /** The user typed the name here, rather than it coming from the schedule or the contact picker. */
    val nameTyped: Boolean = false,
) {
    /**
     * With a contact from the system picker, [number] as it will be saved. A name typed here for the
     * contact already chosen stands; any other name goes with the contact it came with, so it can't be
     * remembered as the new one's WhatsApp name.
     */
    fun picked(number: String, displayName: String, countryIso: String): EditorForm {
        val sameContact = phone.isNotBlank() && (PhoneNormaliser.toE164(phone, countryIso) ?: phone) == number
        val keep = nameTyped && sameContact && name.isNotBlank() && name != pickedName
        return copy(phone = number, name = if (keep) name else displayName, pickedName = displayName, nameTyped = keep)
    }

    fun rule(): Recurrence = when (ruleKind) {
        RuleKind.ONCE -> Recurrence.Once
        RuleKind.DAILY -> Recurrence.Daily
        RuleKind.WEEKLY -> Recurrence.Weekly(weekdays)
        RuleKind.MONTHLY -> Recurrence.Monthly(monthDay.toIntOrNull() ?: 0)
        RuleKind.HOURS -> Recurrence.EveryNHours(every.toIntOrNull() ?: 0)
        RuleKind.DAYS -> Recurrence.EveryNDays(every.toIntOrNull() ?: 0)
    }

    companion object {
        /**
         * A new message five minutes from [now]. Date and time come from one moment, so at 23:57 the
         * default is tomorrow 00:02 rather than a time today that has already passed (B23).
         */
        fun blank(now: ZonedDateTime): EditorForm {
            val at = now.plusMinutes(5).withSecond(0).withNano(0)
            return EditorForm(date = at.toLocalDate(), time = at.toLocalTime(), weekdays = setOf(at.dayOfWeek), monthDay = at.dayOfMonth.toString())
        }

        fun from(schedule: Schedule): EditorForm {
            val first = Instant.ofEpochMilli(schedule.firstAtMillis).atZone(ZoneId.of(schedule.zoneId))
            val base = EditorForm(
                kind = schedule.targetKind,
                name = schedule.targetName,
                phone = schedule.phoneE164 ?: "",
                locked = schedule.isLocked,
                message = schedule.message,
                date = first.toLocalDate(),
                time = first.toLocalTime(),
                weekdays = setOf(first.dayOfWeek),
                monthDay = first.dayOfMonth.toString(),
            )
            return when (val r = schedule.rule) {
                Recurrence.Once -> base
                Recurrence.Daily -> base.copy(ruleKind = RuleKind.DAILY)
                is Recurrence.Weekly -> base.copy(ruleKind = RuleKind.WEEKLY, weekdays = r.days)
                is Recurrence.Monthly -> base.copy(ruleKind = RuleKind.MONTHLY, monthDay = r.dayOfMonth.toString())
                is Recurrence.EveryNHours -> base.copy(ruleKind = RuleKind.HOURS, every = r.hours.toString())
                is Recurrence.EveryNDays -> base.copy(ruleKind = RuleKind.DAYS, every = r.days.toString())
            }
        }

        /** Bundle-safe values only: names, epoch day, second of day, day numbers. */
        val Saver = listSaver<EditorForm, Any>(
            save = {
                listOf(
                    it.kind.name, it.name, it.phone, it.locked, it.message, it.date.toEpochDay(), it.time.toSecondOfDay(),
                    it.ruleKind.name, ArrayList(it.weekdays.map(DayOfWeek::getValue)), it.monthDay, it.every, it.pickedName ?: "",
                    it.lockedTouched, it.nameTyped,
                )
            },
            restore = {
                @Suppress("UNCHECKED_CAST")
                EditorForm(
                    kind = TargetKind.valueOf(it[0] as String),
                    name = it[1] as String,
                    phone = it[2] as String,
                    locked = it[3] as Boolean,
                    message = it[4] as String,
                    date = LocalDate.ofEpochDay(it[5] as Long),
                    time = LocalTime.ofSecondOfDay((it[6] as Int).toLong()),
                    ruleKind = RuleKind.valueOf(it[7] as String),
                    weekdays = (it[8] as List<Int>).map(DayOfWeek::of).toSet(),
                    monthDay = it[9] as String,
                    every = it[10] as String,
                    pickedName = (it[11] as String).ifEmpty { null },
                    lockedTouched = it[12] as Boolean,
                    nameTyped = it[13] as Boolean,
                )
            },
        )
    }
}

/**
 * The editor's state and actions (T2). The form lives in the [SavedStateHandle], so rotation, a
 * theme change at sunset or process death keep what was typed, and an existing schedule is only
 * loaded into it once (B17). Saving is a single in-flight job (B05).
 */
@OptIn(SavedStateHandleSaveableApi::class)
class ScheduleEditorViewModel(private val container: AppContainer, handle: SavedStateHandle) : ViewModel() {

    val scheduleId: Long = handle.get<Long>("id") ?: 0L
    private val zone: ZoneId = ZoneId.systemDefault()

    /** What the quick sheet's Repeat… handed over, for a new schedule. */
    private val prefill: EditorForm? = if (scheduleId != 0L) null else prefillFrom(handle, zone)

    var form: EditorForm by handle.saveable(stateSaver = EditorForm.Saver) { mutableStateOf(prefill ?: EditorForm.blank(ZonedDateTime.now(zone))) }
        private set

    /** Whether [form] already holds the stored schedule; saved with it, so a recreate never reloads over edits. */
    private var formLoaded: Boolean by handle.saveable { mutableStateOf(scheduleId == 0L) }

    /** The stored row being edited: kept fields (enabled, recipient, …) come from here. Reloaded, not saved. */
    private var existing: Schedule? = null

    var ready by mutableStateOf(false)
        private set
    var countryIso by mutableStateOf("GB")
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val doneEvents = Channel<Unit>(Channel.CONFLATED)

    /** Emits once the schedule has been saved and its alarm armed. */
    val done: Flow<Unit> = doneEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            countryIso = container.settings.current().defaultCountryIso
            if (scheduleId != 0L) {
                existing = container.repository.get(scheduleId)
                if (!formLoaded) {
                    existing?.let { form = EditorForm.from(it) }
                    formLoaded = true
                }
            }
            ready = true
        }
    }

    fun update(transform: EditorForm.() -> EditorForm) {
        form = form.transform()
    }

    /** Fills in a contact picked from the system picker. */
    fun contactPicked(rawNumber: String, displayName: String) = update {
        // Kept as picked when it doesn't parse, so the field shows why it can't be saved.
        picked(PhoneNormaliser.toE164(rawNumber, countryIso) ?: rawNumber, displayName, countryIso)
    }

    fun save() {
        if (saving) return
        val f = form
        val rule = f.rule()
        val firstAt = ZonedDateTime.of(f.date, f.time, zone).toInstant()
        val phoneE164 = if (f.kind == TargetKind.CONTACT && f.phone.isNotBlank()) PhoneNormaliser.toE164(f.phone, countryIso) else null
        error = validate(f, phoneE164, rule, firstAt)
        if (error != null) return
        val schedule = (existing ?: Schedule(
            message = "", targetKind = f.kind, targetName = "", phoneE164 = null, isLocked = false,
            firstAtMillis = 0L, zoneId = zone.id, recurrence = "ONCE", nextAtMillis = null,
        )).copy(
            message = f.message.trim(),
            targetKind = f.kind,
            targetName = f.name.trim(),
            phoneE164 = phoneE164,
            isLocked = f.locked,
            firstAtMillis = firstAt.toEpochMilli(),
            zoneId = zone.id,
            recurrence = RecurrenceCodec.encode(rule),
        )
        saving = true
        viewModelScope.launch {
            // Not cancelled if the screen closes mid-save, which would leave the row without its alarm.
            val outcome = withContext(NonCancellable) {
                runCatching {
                    // Linked to a recipient so it shows in the quick sheet's recents; Chat Lock switched here is
                    // remembered for that chat (quick-schedule.md §3).
                    val resolved = container.recipients.resolve(RecipientDraft(f.kind, f.name.trim(), phoneE164 = phoneE164, isLocked = f.locked))
                    var recipient = resolved
                    typedChatName(f, recipient)?.let { name -> recipient = recipient.copy(whatsappName = name.takeIf { it != recipient.displayName }) }
                    if (f.lockedTouched) recipient = recipient.copy(isLocked = f.locked)
                    // Its other pending messages change target too: re-armed, and stale Tap to send prompts cleared.
                    if (recipient != resolved) {
                        container.recipients.updateDetails(recipient).forEach { changed ->
                            runCatching { container.fallbackNotifier.cancel(changed.id) }.onFailure { container.log.error("Clearing a stale prompt failed", it) }
                            runCatching { container.alarmScheduler.sync(changed) }.onFailure { container.log.error("Re-arming after a details edit failed", it) }
                        }
                    }
                    // The chat's own name, as the quick sheet uses: what WhatsApp shows, which the search needs.
                    val saved = container.repository.save(
                        schedule.copy(recipientId = recipient.id, targetName = recipient.chatName, isLocked = lockFor(f, recipient, existing)),
                    )
                    // If a later step fails, Save again updates this row rather than inserting another.
                    existing = saved
                    container.recipients.markUsed(listOf(recipient.id))
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
            return blank.copy(
                kind = handle.get<String>("kind")?.let { k -> TargetKind.entries.firstOrNull { it.name == k } } ?: TargetKind.CONTACT,
                name = name ?: "",
                phone = handle.get<String>("phone") ?: "",
                locked = handle.get<Boolean>("locked") ?: false,
                message = message ?: "",
                date = first?.toLocalDate() ?: blank.date,
                time = first?.toLocalTime() ?: blank.time,
                ruleKind = RuleKind.DAILY,
                weekdays = setOf((first ?: now).dayOfWeek),
                monthDay = (first ?: now).dayOfMonth.toString(),
            )
        }

        /**
         * Chat Lock belongs to the chat. A switch flipped here decides (and is remembered for the chat);
         * otherwise a chat that is already known keeps its remembered setting, so retargeting to a locked
         * chat can't quietly drop the lock. The schedule's own chat keeps the schedule's setting.
         */
        fun lockFor(form: EditorForm, recipient: Recipient, existing: Schedule?): Boolean = when {
            form.lockedTouched -> form.locked
            existing != null && existing.recipientId == recipient.id -> form.locked
            else -> recipient.isLocked
        }

        /**
         * The chat's name belongs to the chat too. One the contact picker filled in is the address book's,
         * so a chat already known by that number keeps the WhatsApp name remembered for it. One typed
         * here is what the user says WhatsApp shows (the field asks for that), so it is remembered for
         * the chat. Returns the name to remember, or null when the stored one stands.
         */
        fun typedChatName(form: EditorForm, recipient: Recipient): String? {
            // Only what the user typed: a name loaded with the schedule or filled in by the picker isn't
            // a statement about what WhatsApp shows for this chat.
            if (!form.nameTyped) return null
            val typed = form.name.trim()
            return typed.takeIf { it.isNotEmpty() && it != form.pickedName?.trim() && it != recipient.chatName }
        }

        fun validate(form: EditorForm, phoneE164: String?, rule: Recurrence, firstAt: Instant, now: Instant = Instant.now()): String? = when {
            form.name.isBlank() -> "Enter the chat name exactly as WhatsApp shows it."
            form.message.isBlank() -> "Enter a message."
            form.kind == TargetKind.CONTACT && form.phone.isNotBlank() && phoneE164 == null ->
                "That phone number isn't valid. Include the country code, or leave it empty to find the chat by name."
            rule is Recurrence.Weekly && rule.days.isEmpty() -> "Pick at least one weekday."
            rule is Recurrence.Monthly && rule.dayOfMonth !in 1..31 -> "Day of month must be 1–31."
            rule is Recurrence.EveryNHours && rule.hours < 1 -> "Interval must be at least 1 hour."
            rule is Recurrence.EveryNDays && rule.days < 1 -> "Interval must be at least 1 day."
            rule == Recurrence.Once && !firstAt.isAfter(now) -> "A one-off message must be in the future."
            else -> null
        }
    }
}
