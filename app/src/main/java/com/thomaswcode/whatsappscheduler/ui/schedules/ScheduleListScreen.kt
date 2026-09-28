package com.thomaswcode.whatsappscheduler.ui.schedules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.sending.SendKind
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import com.thomaswcode.whatsappscheduler.ui.formatStamp
import com.thomaswcode.whatsappscheduler.ui.onboarding.PermissionChecks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleListScreen(
    container: AppContainer,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onSettings: () -> Unit,
    onLogs: () -> Unit,
    onSetup: () -> Unit,
    vm: ScheduleListViewModel = viewModel(factory = ScheduleListViewModel.factory(container)),
) {
    val context = LocalContext.current
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val setupOk = remember(hasPin, schedules) { PermissionChecks.allRequiredOk(PermissionChecks.evaluate(context, hasPin)) }
    // Held by id and saved, so an open dialog survives rotation and always shows the current row.
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingSendId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WhatsApp Scheduler") },
                actions = {
                    IconButton(onClick = onLogs) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Logs") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add") } },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!setupOk) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).clickable(onClick = onSetup),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        "Setup incomplete: automatic sending will fail. Tap to open the checklist.",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (settings?.dryRun == true) {
                Text(
                    "Dry-run mode is on: messages are typed but Send is never tapped.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (schedules.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No scheduled messages yet. Tap + to add one.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(schedules, key = { it.id }) { schedule ->
                        ScheduleRow(
                            schedule = schedule,
                            onToggle = { enabled -> vm.setEnabled(schedule, enabled) },
                            onEdit = { onEdit(schedule.id) },
                            onSendNow = { pendingSendId = schedule.id },
                            onDryRun = { vm.send(SendRequest(schedule.id, SendKind.TEST)) },
                            onDelete = { pendingDeleteId = schedule.id },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    schedules.firstOrNull { it.id == pendingDeleteId }?.let { schedule ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete schedule?") },
            text = { Text("\"${schedule.message.take(80)}\" to ${schedule.targetName}") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(schedule)
                    pendingDeleteId = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") } },
        )
    }

    schedules.firstOrNull { it.id == pendingSendId }?.let { schedule ->
        SendNowDialog(
            schedule = schedule,
            onDismiss = { pendingSendId = null },
            onSend = { request ->
                pendingSendId = null
                vm.send(request)
            },
        )
    }
}

/**
 * Confirms a manual send and says what it does to the schedule (B07). Sending a pending one-off
 * early uses up its occurrence, so it isn't sent again at its time; a recurring schedule can
 * either bring its next occurrence forward or send an extra copy that leaves the series alone.
 */
@Composable
private fun SendNowDialog(schedule: Schedule, onDismiss: () -> Unit, onSend: (SendRequest) -> Unit) {
    val recurring = schedule.rule != Recurrence.Once
    // A switched-off one-off still has its time, and switching it back on would send it then, so
    // sending it now uses that time up. A paused series only gets extra copies.
    val next = if (recurring) schedule.nextAtMillis?.takeIf { schedule.enabled } else schedule.nextAtMillis
    val early = next?.let { SendRequest(schedule.id, SendKind.SEND_NOW, it) }
    val copy = SendRequest(schedule.id, SendKind.EXTRA_COPY)
    val explanation = when {
        next == null && recurring -> "This sends an extra copy. The schedule itself is not changed."
        next == null -> "It has already been sent or its time has passed, so this sends it again."
        recurring -> "Send the ${formatStamp(next)} message early, or send an extra copy and keep that one as it is."
        else -> "It will be sent now instead of at ${formatStamp(next)}."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to ${schedule.targetName} now?") },
        text = {
            Column {
                Text("\"${schedule.message.take(120)}${if (schedule.message.length > 120) "…" else ""}\"", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(explanation, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            when {
                early != null && recurring -> TextButton(onClick = { onSend(early) }) { Text("Send early") }
                early != null -> TextButton(onClick = { onSend(early) }) { Text("Send now") }
                else -> TextButton(onClick = { onSend(copy) }) { Text(if (recurring) "Send a copy" else "Send again") }
            }
        },
        dismissButton = {
            Row {
                if (early != null && recurring) TextButton(onClick = { onSend(copy) }) { Text("Send a copy") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ScheduleRow(
    schedule: Schedule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onSendNow: () -> Unit,
    onDryRun: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onEdit)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (schedule.isLocked) {
                        Icon(Icons.Default.Lock, contentDescription = "Locked chat", modifier = Modifier.width(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(schedule.targetName, style = MaterialTheme.typography.titleMedium)
                }
                Text(schedule.message, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (schedule.nextAtMillis == null) "Finished" else "Next: " + formatStamp(schedule.nextAtMillis)) + " · " + schedule.rule.label(),
                    style = MaterialTheme.typography.labelMedium,
                )
                schedule.lastResult?.let {
                    Text("Last: $it", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Switch(checked = schedule.enabled, onCheckedChange = onToggle)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Send now…") }, onClick = { menu = false; onSendNow() })
                    DropdownMenuItem(text = { Text("Dry run now") }, onClick = { menu = false; onDryRun() })
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
