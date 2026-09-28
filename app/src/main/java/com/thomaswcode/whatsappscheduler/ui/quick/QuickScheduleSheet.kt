package com.thomaswcode.whatsappscheduler.ui.quick

import android.Manifest
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.whatsappscheduler.data.RecipientDraft
import com.thomaswcode.whatsappscheduler.data.contacts.ContactHit
import com.thomaswcode.whatsappscheduler.domain.FriendlyTime
import com.thomaswcode.whatsappscheduler.domain.PhoneNormaliser
import com.thomaswcode.whatsappscheduler.domain.PinCircuitBreaker
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.ui.health.HealthCheck
import java.time.Duration

/**
 * The quick sheet (quick-schedule.md §5) over whatever hosts it: the list's + and edit, or Share.
 * Swiping it away keeps the draft; Discard clears it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickScheduleSheet(vm: QuickScheduleViewModel) {
    if (!vm.visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = vm::hide, sheetState = sheetState) {
        QuickScheduleContent(vm)
    }
}

@Composable
private fun QuickScheduleContent(vm: QuickScheduleViewModel) {
    val context = LocalContext.current
    val now by vm.now.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val serviceConnected by vm.serviceConnected.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val pinFailures by vm.pinFailures.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val draft = vm.draft
    val timeFormat = rememberTimeFormat()
    val searchFocus = remember { FocusRequester() }
    val messageFocus = remember { FocusRequester() }
    var details by remember { mutableStateOf<RecipientDraft?>(null) }
    var askedForContacts by rememberSaveable { mutableStateOf(false) }

    val requestContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.contactsPermissionChanged() }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                vm.add(ContactHit(0, c.getString(1) ?: "", c.getString(0) ?: "", PhoneNormaliser.toE164(c.getString(0) ?: "", settings.defaultCountryIso), false))
            }
        }
    }

    // From +, the recipient comes first; from Share the message is already there, so also the recipient.
    // Once someone is chosen, the message is next.
    LaunchedEffect(Unit) { if (draft.recipients.isEmpty()) searchFocus.requestFocus() }
    var lastCount by remember { mutableStateOf(draft.recipients.size) }
    LaunchedEffect(draft.recipients.size) {
        if (draft.recipients.size > lastCount && draft.message.isBlank()) messageFocus.requestFocus()
        lastCount = draft.recipients.size
    }

    val chips = vm.chips(now, timeFormat)
    val at = vm.resolvedTime(now)
    // Checked again on every return, e.g. from the settings page a Fix opened: those settings aren't observed.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val pinBlock = PinCircuitBreaker.block(pinFailures, now.toInstant().toEpochMilli())
    val health = remember(resumes, serviceConnected, hasPin, pinBlock, settings.dryRun) {
        HealthCheck.evaluate(context, serviceConnected, hasPin, pinBlock, settings.dryRun)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            // The keyboard never covers Schedule (B16).
            .imePadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (draft.isEditing) "Edit message" else "New message", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (draft.isEditing) {
                TextButton(onClick = vm::delete, enabled = !vm.saving) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            } else if (draft.recipients.isNotEmpty() || draft.message.isNotBlank() || draft.time != null) {
                TextButton(onClick = vm::discard, enabled = !vm.saving) { Text("Discard") }
            }
        }
        // One warning at most: the most serious thing that would stop or change the send (sorted by severity).
        health.firstOrNull()?.let { item ->
            // Red only for what stops the send; dry run still types the message, so it is a notice.
            val blocking = item.problem.blocksSending
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (blocking) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                    Text(
                        item.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (blocking) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    )
                    item.fix?.let { intent ->
                        TextButton(onClick = { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text(item.fixLabel ?: "Fix") }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        RecipientField(
            chosen = draft.recipients,
            query = vm.query,
            recents = recents,
            me = me,
            contactHits = vm.contactHits,
            savedHits = vm.savedHits,
            contactsAllowed = vm.contactsAllowed,
            enabled = !vm.saving,
            searchFocus = searchFocus,
            onQuery = vm::updateQuery,
            onSearchFocused = {
                // Asked the first time the search is used, not on opening the sheet.
                if (!vm.contactsAllowed && !askedForContacts) {
                    askedForContacts = true
                    requestContacts.launch(Manifest.permission.READ_CONTACTS)
                }
            },
            onRemove = vm::remove,
            onDetails = { details = it },
            onToggleRecent = vm::toggle,
            onAddDraft = vm::add,
            onAddContact = vm::add,
            onAddSaved = vm::add,
            onAddChatName = vm::addChatName,
            onAllowContacts = { requestContacts.launch(Manifest.permission.READ_CONTACTS) },
            onPickContact = { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.message,
            onValueChange = vm::setMessage,
            // Frozen while saving: what shows is what is being saved.
            enabled = !vm.saving,
            label = { Text("Message") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 3,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).focusRequester(messageFocus),
        )
        Spacer(Modifier.height(8.dp))
        Text("When", style = MaterialTheme.typography.titleSmall)
        TimeChips(chips, draft.time, now, vm::choose, enabled = !vm.saving)
        val sendable = at != null && QuickScheduleViewModel.isSendable(at, now)
        at?.let {
            Text(resolvedLine(it, now, timeFormat), style = MaterialTheme.typography.bodyMedium)
            when {
                !sendable -> Text("That time has passed. Pick a later one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Duration.between(now, it) < Duration.ofMinutes(2) ->
                    Text("Sends in under 2 minutes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Off while saving: the draft would otherwise become both this one-off and a series.
            if (!draft.isEditing) TextButton(onClick = vm::repeat, enabled = !vm.saving) { Text("Repeat…") }
            Spacer(Modifier.weight(1f).width(8.dp))
            Button(
                onClick = vm::schedule,
                enabled = !vm.saving && draft.recipients.isNotEmpty() && draft.message.isNotBlank() && sendable,
            ) {
                Text(
                    when {
                        vm.saving -> "Saving…"
                        draft.isEditing -> "Save"
                        at != null -> "Schedule · " + FriendlyTime.dayAndTime(at, now, timeFormat)
                        else -> "Schedule"
                    },
                )
            }
        }
    }

    details?.let { recipient ->
        RecipientDetailsDialog(
            recipient = recipient,
            countryIso = settings.defaultCountryIso,
            onDismiss = { details = null },
            onSave = { updated -> vm.saveDetails(recipient, updated); details = null },
            onForget = { vm.forget(recipient); details = null },
        )
    }
}

/**
 * Recipient details (quick-schedule.md §3): the name WhatsApp shows, the number and Chat Lock, set
 * once and remembered. Changes also reach this recipient's messages that haven't gone out.
 */
@Composable
private fun RecipientDetailsDialog(
    recipient: RecipientDraft,
    countryIso: String,
    onDismiss: () -> Unit,
    onSave: (RecipientDraft) -> Unit,
    onForget: () -> Unit,
) {
    var whatsappName by rememberSaveable { mutableStateOf(recipient.whatsappName ?: "") }
    var number by rememberSaveable { mutableStateOf(recipient.phoneE164 ?: "") }
    var locked by rememberSaveable { mutableStateOf(recipient.isLocked) }
    val parsed = number.takeIf { it.isNotBlank() }?.let { PhoneNormaliser.parse(it, countryIso) }
    val numberOk = number.isBlank() || parsed?.e164OrNull != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(recipient.displayName) },
        text = {
            Column {
                OutlinedTextField(
                    value = whatsappName,
                    onValueChange = { whatsappName = it },
                    label = { Text("Name in WhatsApp") },
                    placeholder = { Text(recipient.displayName) },
                    supportingText = { Text("Only if WhatsApp shows the chat under another name; used to find it without a number.") },
                    singleLine = true,
                )
                if (recipient.kind == TargetKind.CONTACT) {
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it },
                        label = { Text("Number") },
                        isError = !numberOk,
                        supportingText = { Text(if (numberOk) "Leave empty to find the chat by name." else "Not a valid number.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Behind Chat Lock")
                        Text("Adds the Locked chats steps when sending.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = locked, onCheckedChange = { locked = it })
                }
                if (recipient.recipientId != null) {
                    TextButton(onClick = onForget) { Text("Remove from recents") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = numberOk,
                onClick = {
                    onSave(
                        recipient.copy(
                            whatsappName = whatsappName.trim().takeIf { it.isNotEmpty() && it != recipient.displayName },
                            phoneE164 = if (recipient.kind == TargetKind.CONTACT) parsed?.e164OrNull else recipient.phoneE164,
                            isLocked = locked,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
