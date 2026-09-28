package com.thomaswcode.whatsappscheduler.ui.schedules

import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.PhoneNormaliser
import com.thomaswcode.whatsappscheduler.domain.PhoneParse
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.RecurrenceCodec
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.ui.BackBar
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class RuleKind { ONCE, DAILY, WEEKLY, MONTHLY, HOURS, DAYS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(container: AppContainer, scheduleId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    var loaded by remember { mutableStateOf(scheduleId == 0L) }
    var existing by remember { mutableStateOf<Schedule?>(null) }
    var kind by remember { mutableStateOf(TargetKind.CONTACT) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var locked by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now(zone)) }
    var time by remember { mutableStateOf(LocalTime.now(zone).plusMinutes(5).withSecond(0).withNano(0)) }
    var ruleKind by remember { mutableStateOf(RuleKind.ONCE) }
    var weekdays by remember { mutableStateOf(setOf(LocalDate.now(zone).dayOfWeek)) }
    var monthDay by remember { mutableStateOf(LocalDate.now(zone).dayOfMonth.toString()) }
    var every by remember { mutableStateOf("1") }
    var error by remember { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var countryIso by remember { mutableStateOf("GB") }
    // Set by the first tap on Save, so a second tap can't insert the schedule again (B05).
    var saving by remember { mutableStateOf(false) }
    // The name the contact picker last filled in, so picking someone else replaces it (B22).
    var pickedName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(scheduleId) {
        countryIso = container.settings.current().defaultCountryIso
        if (scheduleId != 0L) {
            container.repository.get(scheduleId)?.let { s ->
                existing = s
                kind = s.targetKind
                name = s.targetName
                phone = s.phoneE164 ?: ""
                locked = s.isLocked
                message = s.message
                val first = Instant.ofEpochMilli(s.firstAtMillis).atZone(ZoneId.of(s.zoneId))
                date = first.toLocalDate()
                time = first.toLocalTime()
                when (val r = s.rule) {
                    Recurrence.Once -> ruleKind = RuleKind.ONCE
                    Recurrence.Daily -> ruleKind = RuleKind.DAILY
                    is Recurrence.Weekly -> { ruleKind = RuleKind.WEEKLY; weekdays = r.days }
                    is Recurrence.Monthly -> { ruleKind = RuleKind.MONTHLY; monthDay = r.dayOfMonth.toString() }
                    is Recurrence.EveryNHours -> { ruleKind = RuleKind.HOURS; every = r.hours.toString() }
                    is Recurrence.EveryNDays -> { ruleKind = RuleKind.DAYS; every = r.days.toString() }
                }
            }
            loaded = true
        }
    }

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val raw = cursor.getString(0) ?: ""
                val display = cursor.getString(1) ?: ""
                // Kept as picked when it doesn't parse, so the field shows why it can't be saved.
                phone = PhoneNormaliser.toE164(raw, countryIso) ?: raw
                if (name.isBlank() || name == pickedName) name = display
                pickedName = display
            }
        }
    }

    if (!loaded) return

    Scaffold(topBar = { BackBar(if (scheduleId == 0L) "New message" else "Edit message", onDone) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TargetKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(i, TargetKind.entries.size),
                    ) { Text(if (k == TargetKind.CONTACT) "Contact" else "Group") }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(if (kind == TargetKind.CONTACT) "Contact name (as shown in WhatsApp)" else "Group name (exact, as shown in WhatsApp)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (kind == TargetKind.CONTACT) {
                Spacer(Modifier.height(8.dp))
                val parsed = remember(phone, countryIso) { phone.takeIf { it.isNotBlank() }?.let { PhoneNormaliser.parse(it, countryIso) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        isError = parsed == PhoneParse.Invalid,
                        supportingText = parsed?.let { { Text(phoneHint(it, countryIso)) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }) {
                        Text("Pick")
                    }
                }
                Text(
                    "Leave the phone empty to find the chat by name instead (slower, needed for 'message yourself' if the link fails).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = locked, onCheckedChange = { locked = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("This chat is behind WhatsApp Chat Lock")
                    Text("Adds the folder + PIN steps. Leave off for normal chats.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("First send", style = MaterialTheme.typography.titleSmall)
            Row {
                OutlinedButton(onClick = { showDate = true }) { Text(date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showTime = true }) { Text(time.format(DateTimeFormatter.ofPattern("HH:mm"))) }
            }
            Spacer(Modifier.height(12.dp))
            Text("Repeat", style = MaterialTheme.typography.titleSmall)
            Row(modifier = Modifier.fillMaxWidth()) {
                RuleKind.entries.forEach { rk ->
                    FilterChip(
                        selected = ruleKind == rk,
                        onClick = { ruleKind = rk },
                        label = { Text(rk.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            when (ruleKind) {
                RuleKind.WEEKLY -> Row(modifier = Modifier.fillMaxWidth()) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(
                            selected = d in weekdays,
                            onClick = { weekdays = if (d in weekdays) weekdays - d else weekdays + d },
                            label = { Text(d.name.take(2)) },
                            modifier = Modifier.padding(end = 2.dp),
                        )
                    }
                }
                RuleKind.MONTHLY -> OutlinedTextField(value = monthDay, onValueChange = { monthDay = it.filter(Char::isDigit) }, label = { Text("Day of month (1–31)") }, singleLine = true)
                RuleKind.HOURS -> OutlinedTextField(value = every, onValueChange = { every = it.filter(Char::isDigit) }, label = { Text("Every N hours") }, singleLine = true)
                RuleKind.DAYS -> OutlinedTextField(value = every, onValueChange = { every = it.filter(Char::isDigit) }, label = { Text("Every N days") }, singleLine = true)
                else -> Unit
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
                onClick = {
                    if (saving) return@Button
                    val rule = when (ruleKind) {
                        RuleKind.ONCE -> Recurrence.Once
                        RuleKind.DAILY -> Recurrence.Daily
                        RuleKind.WEEKLY -> Recurrence.Weekly(weekdays)
                        RuleKind.MONTHLY -> Recurrence.Monthly(monthDay.toIntOrNull() ?: 0)
                        RuleKind.HOURS -> Recurrence.EveryNHours(every.toIntOrNull() ?: 0)
                        RuleKind.DAYS -> Recurrence.EveryNDays(every.toIntOrNull() ?: 0)
                    }
                    val firstAt = ZonedDateTime.of(date, time, zone).toInstant()
                    val phoneE164 = if (kind == TargetKind.CONTACT && phone.isNotBlank()) PhoneNormaliser.toE164(phone, countryIso) else null
                    error = validate(name, phone, phoneE164, kind, message, rule, firstAt)
                    if (error != null) return@Button
                    val schedule = (existing ?: Schedule(
                        message = "", targetKind = kind, targetName = "", phoneE164 = null, isLocked = false,
                        firstAtMillis = 0L, zoneId = zone.id, recurrence = "ONCE", nextAtMillis = null,
                    )).copy(
                        message = message.trim(),
                        targetKind = kind,
                        targetName = name.trim(),
                        phoneE164 = phoneE164,
                        isLocked = locked,
                        firstAtMillis = firstAt.toEpochMilli(),
                        zoneId = zone.id,
                        recurrence = RecurrenceCodec.encode(rule),
                    )
                    saving = true
                    scope.launch {
                        // Not cancelled if the screen closes mid-save, which would leave the row without its alarm.
                        val outcome = withContext(NonCancellable) {
                            runCatching {
                                val saved = container.repository.save(schedule)
                                // If a later step fails, Save again updates this row rather than inserting another.
                                existing = saved
                                container.alarmScheduler.sync(saved)
                                // A prompt for the old version of the message is stale now (B10).
                                container.fallbackNotifier.cancel(saved.id)
                            }
                        }
                        outcome
                            .onSuccess { onDone() }
                            .onFailure {
                                container.log.error("Saving schedule failed", it)
                                error = "Couldn't save (${it.message ?: it.javaClass.simpleName}). Try again."
                                saving = false
                            }
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save") }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate() }
                    showDate = false
                }) { Text("OK") }
            },
        ) { DatePicker(state = state) }
    }
    if (showTime) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); showTime = false }) { Text("OK") } },
            text = { TimePicker(state = state) },
        )
    }
}

/** What the chat link will open, under the phone field (B06). */
private fun phoneHint(parsed: PhoneParse, countryIso: String): String = when (parsed) {
    is PhoneParse.Valid -> "Opens chat with ${PhoneNormaliser.display(parsed.e164, countryIso)}"
    is PhoneParse.Unverified -> "Opens chat with ${parsed.e164}. This phone doesn't recognise the number, so check it."
    PhoneParse.Invalid -> "Not a valid number. Type it as saved in Contacts, or with the country code (+44…)."
}

private fun validate(name: String, phone: String, phoneE164: String?, kind: TargetKind, message: String, rule: Recurrence, firstAt: Instant): String? = when {
    name.isBlank() -> "Enter the chat name exactly as WhatsApp shows it."
    message.isBlank() -> "Enter a message."
    kind == TargetKind.CONTACT && phone.isNotBlank() && phoneE164 == null -> "That phone number isn't valid. Include the country code, or leave it empty to find the chat by name."
    rule is Recurrence.Weekly && rule.days.isEmpty() -> "Pick at least one weekday."
    rule is Recurrence.Monthly && rule.dayOfMonth !in 1..31 -> "Day of month must be 1–31."
    rule is Recurrence.EveryNHours && rule.hours < 1 -> "Interval must be at least 1 hour."
    rule is Recurrence.EveryNDays && rule.days < 1 -> "Interval must be at least 1 day."
    rule == Recurrence.Once && !firstAt.isAfter(Instant.now()) -> "A one-off message must be in the future."
    else -> null
}
