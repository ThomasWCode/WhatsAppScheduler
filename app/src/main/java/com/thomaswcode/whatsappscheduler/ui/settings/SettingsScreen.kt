package com.thomaswcode.whatsappscheduler.ui.settings

import android.app.Activity
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.settings.Settings
import com.thomaswcode.whatsappscheduler.domain.PinCircuitBreaker
import com.thomaswcode.whatsappscheduler.ui.BackBar
import com.thomaswcode.whatsappscheduler.ui.authenticateThen

@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onSetup: () -> Unit,
    onInspector: () -> Unit,
    onLogs: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container)),
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val hasSecret by vm.hasSecretCode.collectAsStateWithLifecycle()
    val pinFailures by vm.pinFailures.collectAsStateWithLifecycle()
    val pinBlock = remember(pinFailures) { PinCircuitBreaker.block(pinFailures, System.currentTimeMillis()) }
    // Plain remember on purpose: a rotation closes these, so reopening asks to authenticate again, and
    // nothing typed into them is written to saved state.
    var pinDialog by remember { mutableStateOf(false) }
    var secretDialog by remember { mutableStateOf(false) }
    val status = vm.status

    fun update(transform: (Settings) -> Settings) = vm.update(transform)

    Scaffold(topBar = { BackBar("Settings", onBack) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Secrets", style = MaterialTheme.typography.titleMedium)
            Text(
                "Stored encrypted with a hardware-backed key. This app decrypts them at send time, so anyone who controls this app on this phone can too.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (hasPin) "Lock-screen PIN: stored" else "Lock-screen PIN: not stored", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    (context as Activity).authenticateThen("Confirm it's you", onFailure = vm::showStatus) { pinDialog = true }
                }) { Text(if (hasPin) "Change" else "Store") }
            }
            pinBlock?.let { Text(it.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (hasSecret) "Chat Lock secret code: stored" else "Chat Lock secret code: none", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    (context as Activity).authenticateThen("Confirm it's you", onFailure = vm::showStatus) { secretDialog = true }
                }) { Text(if (hasSecret) "Change" else "Store") }
            }
            Text("Only needed if you hid the Locked chats folder behind a secret code.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text("Sending", style = MaterialTheme.typography.titleMedium)
            ToggleRow("Dry run (never tap Send)", "Runs every step and clears the composer instead of sending. Use to calibrate.", settings.dryRun) { v -> update { it.copy(dryRun = v) } }
            ToggleRow("Re-lock the phone after sending", "Only when it was locked before.", settings.relockAfterSend) { v -> update { it.copy(relockAfterSend = v) } }
            ToggleRow("Use phone-number link for locked contacts", "Faster path for locked contact chats; enable only if a dry run shows it works.", settings.deepLinkForLockedChats) { v -> update { it.copy(deepLinkForLockedChats = v) } }
            NumberRow("Retries per message", settings.retryCount) { v -> update { it.copy(retryCount = v.coerceIn(0, 5)) } }
            NumberRow("Seconds between retries", settings.retryDelaySeconds) { v -> update { it.copy(retryDelaySeconds = v.coerceIn(5, 600)) } }
            NumberRow("Step timeout (ms)", settings.stepTimeoutMs.toInt()) { v -> update { it.copy(stepTimeoutMs = v.coerceIn(2000, 60000).toLong()) } }
            NumberRow("Whole-send timeout (ms)", settings.jobTimeoutMs.toInt()) { v -> update { it.copy(jobTimeoutMs = v.coerceIn(20000, 600000).toLong()) } }
            NumberRow("Missed-send grace (minutes)", settings.missedGraceMinutes) { v -> update { it.copy(missedGraceMinutes = v.coerceIn(0, 24 * 60)) } }
            TextRow("Default country for phone numbers (ISO)", settings.defaultCountryIso) { v -> update { it.copy(defaultCountryIso = v.uppercase().take(2)) } }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text("Tools", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) { Text("Setup checklist") }
            OutlinedButton(onClick = onInspector, modifier = Modifier.fillMaxWidth()) { Text("UI inspector (capture WhatsApp view ids)") }
            OutlinedButton(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text("Automation log") }
            OutlinedButton(
                onClick = vm::rearmAlarms,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Re-arm all alarms") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (pinDialog) {
        PinDialog(
            hasPin = hasPin,
            onDismiss = { pinDialog = false },
            onSave = { value -> vm.savePin(value) { pinDialog = false } },
            onRemove = { vm.removePin { pinDialog = false } },
        )
    }
    if (secretDialog) {
        SecretDialog(
            title = "Chat Lock secret code",
            hint = "Leave empty to remove",
            onDismiss = { secretDialog = false },
            onSave = { value -> vm.saveSecretCode(value) { secretDialog = false } },
        )
    }
}

/**
 * Stores the lock-screen PIN or password (B14). It is entered twice, because a typo here is a
 * wrong PIN typed on the lock screen later, and removing it is its own button rather than saving
 * an empty field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinDialog(hasPin: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit, onRemove: () -> Unit) {
    var password by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val noun = if (password) "password" else "PIN"
    val tooShort = first.isNotEmpty() && first.length < MIN_LOCK_LENGTH
    val mismatch = second.isNotEmpty() && second != first
    val valid = first.length >= MIN_LOCK_LENGTH && second == first
    val keyboard = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.NumberPassword)
    fun accept(text: String) = if (password) text else text.filter(Char::isDigit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPin) "Change lock-screen $noun" else "Store lock-screen $noun") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(false, true).forEachIndexed { i, isPassword ->
                        SegmentedButton(
                            selected = password == isPassword,
                            onClick = { if (password != isPassword) { password = isPassword; first = ""; second = "" } },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(if (isPassword) "Password" else "PIN") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = accept(it) },
                    label = { Text(noun.replaceFirstChar(Char::uppercase)) },
                    singleLine = true,
                    isError = tooShort,
                    supportingText = { Text(if (tooShort) "At least $MIN_LOCK_LENGTH characters" else "Exactly what you type on the lock screen") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = keyboard,
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = accept(it) },
                    label = { Text("Enter it again") },
                    singleLine = true,
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text("Doesn't match") }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = keyboard,
                )
                Text(
                    "If the phone rejects it, automatic unlocking pauses until you store it again, so a wrong $noun is never typed twice.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (hasPin) {
                    TextButton(onClick = onRemove) { Text("Remove stored PIN", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(first) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Android's minimum length for a PIN or password. */
private const val MIN_LOCK_LENGTH = 4

@Composable
private fun SecretDialog(title: String, hint: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(hint) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberRow(title: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { t -> text = t.filter(Char::isDigit); t.toIntOrNull()?.let(onChange) },
            modifier = Modifier.width(120.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
private fun TextRow(title: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { t -> text = t; if (t.length == 2) onChange(t) },
            modifier = Modifier.width(120.dp),
            singleLine = true,
        )
    }
}
