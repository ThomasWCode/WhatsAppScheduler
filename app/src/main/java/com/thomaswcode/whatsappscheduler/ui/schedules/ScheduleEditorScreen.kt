package com.thomaswcode.whatsappscheduler.ui.schedules

import android.content.Intent
import android.provider.ContactsContract
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.domain.PhoneNormaliser
import com.thomaswcode.whatsappscheduler.domain.PhoneParse
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.ui.BackBar
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(
    container: AppContainer,
    onDone: () -> Unit,
    vm: ScheduleEditorViewModel = viewModel(factory = ScheduleEditorViewModel.factory(container)),
) {
    val context = LocalContext.current
    // Observed, so the weekday names follow a language change without a restart.
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(vm) { vm.done.collect { onDone() } }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) vm.contactPicked(cursor.getString(0) ?: "", cursor.getString(1) ?: "")
        }
    }

    if (!vm.ready) return
    val form = vm.form
    val countryIso = vm.countryIso

    Scaffold(topBar = { BackBar(if (vm.scheduleId == 0L) "New message" else "Edit message", onDone) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TargetKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = form.kind == k,
                        onClick = { vm.update { copy(kind = k) } },
                        shape = SegmentedButtonDefaults.itemShape(i, TargetKind.entries.size),
                    ) { Text(if (k == TargetKind.CONTACT) "Contact" else "Group") }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = form.name,
                onValueChange = { vm.update { copy(name = it) } },
                label = { Text(if (form.kind == TargetKind.CONTACT) "Contact name (as shown in WhatsApp)" else "Group name (exact, as shown in WhatsApp)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (form.kind == TargetKind.CONTACT) {
                Spacer(Modifier.height(8.dp))
                val parsed = remember(form.phone, countryIso) { form.phone.takeIf { it.isNotBlank() }?.let { PhoneNormaliser.parse(it, countryIso) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = form.phone,
                        onValueChange = { vm.update { copy(phone = it) } },
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
                Switch(checked = form.locked, onCheckedChange = { locked -> vm.update { copy(locked = locked, lockedTouched = true) } })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("This chat is behind WhatsApp Chat Lock")
                    Text("Adds the folder + PIN steps. Leave off for normal chats.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = form.message,
                onValueChange = { vm.update { copy(message = it) } },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("First send", style = MaterialTheme.typography.titleSmall)
            Row {
                OutlinedButton(onClick = { showDate = true }) { Text(form.date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showTime = true }) { Text(form.time.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"))) }
            }
            Spacer(Modifier.height(12.dp))
            Text("Repeat", style = MaterialTheme.typography.titleSmall)
            // Wraps instead of squeezing the last chips to nothing on a normal-width phone (B15).
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RuleKind.entries.forEach { rk ->
                    FilterChip(
                        selected = form.ruleKind == rk,
                        onClick = { vm.update { copy(ruleKind = rk) } },
                        label = { Text(rk.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
            when (form.ruleKind) {
                RuleKind.WEEKLY -> FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(
                            selected = d in form.weekdays,
                            onClick = { vm.update { copy(weekdays = if (d in weekdays) weekdays - d else weekdays + d) } },
                            label = { Text(d.getDisplayName(TextStyle.SHORT, locale)) },
                        )
                    }
                }
                RuleKind.MONTHLY -> OutlinedTextField(value = form.monthDay, onValueChange = { vm.update { copy(monthDay = it.filter(Char::isDigit)) } }, label = { Text("Day of month (1–31)") }, singleLine = true)
                RuleKind.HOURS -> OutlinedTextField(value = form.every, onValueChange = { vm.update { copy(every = it.filter(Char::isDigit)) } }, label = { Text("Every N hours") }, singleLine = true)
                RuleKind.DAYS -> OutlinedTextField(value = form.every, onValueChange = { vm.update { copy(every = it.filter(Char::isDigit)) } }, label = { Text("Every N days") }, singleLine = true)
                else -> Unit
            }
            vm.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(modifier = Modifier.fillMaxWidth(), enabled = !vm.saving, onClick = vm::save) {
                Text(if (vm.saving) "Saving…" else "Save")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = form.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis -> vm.update { copy(date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()) } }
                    showDate = false
                }) { Text("OK") }
            },
        ) { DatePicker(state = state) }
    }
    if (showTime) {
        val state = rememberTimePickerState(initialHour = form.time.hour, initialMinute = form.time.minute, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton(onClick = { vm.update { copy(time = LocalTime.of(state.hour, state.minute)) }; showTime = false }) { Text("OK") } },
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
