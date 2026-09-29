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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.whatsappscheduler.data.RecipientDraft
import com.thomaswcode.whatsappscheduler.data.contacts.ContactHit
import com.thomaswcode.whatsappscheduler.domain.FriendlyTime
import com.thomaswcode.whatsappscheduler.domain.PhoneNormaliser
import com.thomaswcode.whatsappscheduler.domain.PinCircuitBreaker
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.ui.formatStamp
import com.thomaswcode.whatsappscheduler.ui.health.HealthStrip
import com.thomaswcode.whatsappscheduler.ui.health.rememberHealth
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
    val nowMillis = now.toInstant().toEpochMilli()
    val health = rememberHealth(serviceConnected, hasPin, PinCircuitBreaker.block(pinFailures, nowMillis), settings.dryRunUntilMillis, nowMillis)

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
        // One warning at most: the most serious thing that would stop or change the send.
        HealthStrip(health, showReady = false, onOpenSettings = null, onTurnOffDryRun = vm::turnOffDryRun, modifier = Modifier.padding(vertical = 6.dp))
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
            onAddTyped = vm::addTyped,
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

    // Looked up for the recipient the details are for, not just the recent chips.
    var checkedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(details) { checkedAt = details?.let { vm.checkedAt(it) } }
    details?.let { recipient ->
        RecipientDetailsDialog(
            recipient = recipient,
            countryIso = settings.defaultCountryIso,
            checkedAtMillis = checkedAt,
            onDismiss = { details = null },
            onSave = { updated -> vm.saveDetails(recipient, updated); details = null },
            onForget = { vm.forget(recipient); details = null },
            // Only for a chat found by name; a locked one needs the Chat Lock steps, which a test send covers.
            onCheck = if (recipient.phoneE164 == null && !recipient.isLocked) {
                { vm.checkChat(recipient); details = null }
            } else {
                null
            },
        )
    }
}
