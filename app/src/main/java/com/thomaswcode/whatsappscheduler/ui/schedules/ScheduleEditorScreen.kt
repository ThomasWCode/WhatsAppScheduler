package com.thomaswcode.whatsappscheduler.ui.schedules

import android.Manifest
import android.content.Intent
import android.provider.ContactsContract
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.RecipientDraft
import com.thomaswcode.whatsappscheduler.data.contacts.ContactHit
import com.thomaswcode.whatsappscheduler.domain.PhoneNormaliser
import com.thomaswcode.whatsappscheduler.domain.RuleSentence
import com.thomaswcode.whatsappscheduler.ui.BackBar
import com.thomaswcode.whatsappscheduler.ui.quick.RecipientDetailsDialog
import com.thomaswcode.whatsappscheduler.ui.quick.RecipientField
import com.thomaswcode.whatsappscheduler.ui.quick.rememberTimeFormat
import com.thomaswcode.whatsappscheduler.ui.quick.toDraft
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/**
 * The full editor, for repeating messages (improvements I7): who, with the quick sheet's To field and
 * recipient details; what; how it repeats, said back as a sentence; and when it starts. Leaving with
 * changes asks first; an existing one can be deleted here.
 */
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
    val timeFormat = rememberTimeFormat()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.done.collect { onDone() } }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<RecipientDraft?>(null) }
    val searchFocus = remember { FocusRequester() }

    val requestContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.search.permissionChanged() }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val number = c.getString(0) ?: ""
                vm.add(ContactHit(0, c.getString(1) ?: "", number, PhoneNormaliser.toE164(number, vm.countryIso), false))
            }
        }
    }

    // Anything not saved is asked about before it goes: Back and the arrow alike.
    fun leave() {
        if (vm.unsaved && !vm.saving) confirmDiscard = true else onDone()
    }
    BackHandler { leave() }

    if (!vm.ready) return
    val form = vm.form
    val problems = form.problems()

    /** A field's problem, once it should be shown: the rule's as it is typed, the rest after Save is tried. */
    fun problem(field: EditorForm.Field): String? = problems[field]?.takeIf { field == EditorForm.Field.RULE || vm.triedSaving }

    Scaffold(
        topBar = {
            BackBar(if (vm.scheduleId == 0L) "Repeating message" else "Edit repeating message", onBack = ::leave) {
                if (vm.scheduleId != 0L) TextButton(onClick = { confirmDelete = true }, enabled = !vm.saving) { Text("Delete") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            RecipientField(
                chosen = listOfNotNull(form.recipient),
                query = vm.query,
                recents = recents,
                me = null,
                contactHits = vm.search.contactHits,
                savedHits = vm.search.savedHits,
                contactsAllowed = vm.search.contactsAllowed,
                enabled = !vm.saving,
                searchFocus = searchFocus,
                onQuery = vm::updateQuery,
                onSearchFocused = {},
                onRemove = { vm.clearRecipient() },
                onDetails = { details = it },
                onToggleRecent = { vm.choose(it.toDraft()) },
                onAddDraft = vm::choose,
                onAddContact = vm::add,
                onAddSaved = { vm.choose(it.toDraft()) },
                onAddTyped = vm::addTyped,
                onAllowContacts = { requestContacts.launch(Manifest.permission.READ_CONTACTS) },
                onPickContact = { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
                single = true,
            )
            problem(EditorForm.Field.RECIPIENT)?.let { FieldError(it) }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = form.message,
                onValueChange = { vm.update { copy(message = it) } },
                label = { Text("Message") },
                isError = problem(EditorForm.Field.MESSAGE) != null,
                supportingText = problem(EditorForm.Field.MESSAGE)?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("Repeat", style = MaterialTheme.typography.titleSmall)
            // Wraps instead of squeezing the last chips to nothing on a normal-width phone (B15). Once
            // only for a one-off opened here: the quick sheet makes and edits those.
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RuleKind.entries.filter { it != RuleKind.ONCE || form.ruleKind == RuleKind.ONCE }.forEach { kind ->
                    FilterChip(selected = form.ruleKind == kind, onClick = { vm.update { copy(ruleKind = kind) } }, label = { Text(kind.label) })
                }
            }
            val ruleProblem = problem(EditorForm.Field.RULE)
            when (form.ruleKind) {
                RuleKind.WEEKLY -> {
                    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = day in form.weekdays,
                                onClick = { vm.update { copy(weekdays = if (day in weekdays) weekdays - day else weekdays + day) } },
                                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                            )
                        }
                    }
                    ruleProblem?.let { FieldError(it) }
                }
                RuleKind.MONTHLY -> NumberField("Day of the month", form.monthDay, ruleProblem) { vm.update { copy(monthDay = it) } }
                RuleKind.HOURS -> NumberField("Every how many hours", form.every, ruleProblem) { vm.update { copy(every = it) } }
                RuleKind.DAYS -> NumberField("Every how many days", form.every, ruleProblem) { vm.update { copy(every = it) } }
                RuleKind.ONCE, RuleKind.DAILY -> Unit
            }
            Spacer(Modifier.height(12.dp))
            Text(if (form.ruleKind == RuleKind.HOURS) "From" else "Starts", style = MaterialTheme.typography.titleSmall)
            Row {
                OutlinedButton(onClick = { showDate = true }, enabled = !vm.saving) {
                    Text(form.date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)))
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showTime = true }, enabled = !vm.saving) { Text(timeFormat(form.time)) }
            }
            // The rule said back, so a slip (Thu for Tue, say) shows before it is saved.
            RuleSentence.describe(form.rule(), ZonedDateTime.of(form.date, form.time, now.zone), now, locale, timeFormat)?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
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

    details?.let { chosen ->
        RecipientDetailsDialog(
            recipient = chosen,
            countryIso = vm.countryIso,
            checkedAtMillis = null,
            onDismiss = { details = null },
            onSave = { updated ->
                details = null
                vm.saveDetails(chosen, updated)
            },
            onForget = {
                details = null
                vm.forget(chosen)
            },
            onCheck = null,
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your changes?") },
            text = { Text("What you've entered here won't be saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this repeating message?") },
            text = { Text("It won't be sent again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (showDate) {
        // A new series can't start on a day already gone; an existing one keeps whatever it had.
        val today = now.toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = form.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    vm.scheduleId != 0L || !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis -> vm.update { copy(date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()) } }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
    if (showTime) {
        val state = rememberTimePickerState(initialHour = form.time.hour, initialMinute = form.time.minute, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.update { copy(time = LocalTime.of(state.hour, state.minute)) }
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}

/** A problem with a field that has no supporting text of its own, under it. */
@Composable
private fun FieldError(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}

/** A whole number for the rule, digits only, saying what is allowed while it isn't. */
@Composable
private fun NumberField(label: String, value: String, problem: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(3)) },
        label = { Text(label) },
        singleLine = true,
        isError = problem != null,
        supportingText = problem?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.padding(top = 6.dp),
    )
}
