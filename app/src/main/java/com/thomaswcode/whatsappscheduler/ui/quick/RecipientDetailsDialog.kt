package com.thomaswcode.whatsappscheduler.ui.quick

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thomaswcode.whatsappscheduler.data.RecipientDraft
import com.thomaswcode.whatsappscheduler.domain.PhoneNormaliser
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.ui.formatStamp

/**
 * Recipient details (quick-schedule.md §3): the name WhatsApp shows, the number and Chat Lock, set
 * once and remembered. Changes also reach this recipient's messages that haven't gone out.
 */
@Composable
internal fun RecipientDetailsDialog(
    recipient: RecipientDraft,
    countryIso: String,
    checkedAtMillis: Long?,
    onDismiss: () -> Unit,
    onSave: (RecipientDraft) -> Unit,
    onForget: () -> Unit,
    onCheck: (() -> Unit)?,
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
                onCheck?.let { check ->
                    checkedAtMillis?.let { Text("✓ Found in WhatsApp's search ${formatStamp(it)}", style = MaterialTheme.typography.bodySmall) }
                    // It checks the details as saved: after an edit, those aren't what the fields show yet.
                    val edited = whatsappName != (recipient.whatsappName ?: "") || number != (recipient.phoneE164 ?: "") || locked != recipient.isLocked
                    if (edited) Text("Save to check the new details.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = check, enabled = !edited) { Text("Check this chat now") }
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
