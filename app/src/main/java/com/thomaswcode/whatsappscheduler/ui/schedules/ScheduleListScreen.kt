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
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.thomaswcode.whatsappscheduler.ui.quick.QuickEvent
import com.thomaswcode.whatsappscheduler.ui.quick.QuickScheduleSheet
import com.thomaswcode.whatsappscheduler.ui.quick.QuickScheduleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleListScreen(
    container: AppContainer,
    onEdit: (Long) -> Unit,
    onRepeat: (QuickEvent.OpenFullEditor) -> Unit,
    onSettings: () -> Unit,
    onLogs: () -> Unit,
    onSetup: () -> Unit,
    vm: ScheduleListViewModel = viewModel(factory = ScheduleListViewModel.factory(container)),
    quick: QuickScheduleViewModel = viewModel(factory = QuickScheduleViewModel.factory(container)),
) {
    val context = LocalContext.current
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    // A batch (one message to several people) is one row (quick-schedule.md §10).
    val groups = remember(schedules) { schedules.groupBy { it.batchId ?: "#${it.id}" }.values.toList() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(quick) {
        quick.eventFlow.collect { event ->
            when (event) {
                is QuickEvent.Scheduled -> {
                    val undo = event.createdIds
                    val result = snackbar.showSnackbar(event.summary, actionLabel = if (undo != null) "Undo" else null, withDismissAction = undo == null)
                    if (result == SnackbarResult.ActionPerformed && undo != null) quick.undo(undo)
                }
                is QuickEvent.Deleted -> snackbar.showSnackbar(event.summary, withDismissAction = true)
                is QuickEvent.OpenFullEditor -> onRepeat(event)
            }
        }
    }
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val setupOk = remember(hasPin, schedules) { PermissionChecks.allRequiredOk(PermissionChecks.evaluate(context, hasPin)) }
    // Held by id and saved, so an open dialog survives rotation and always shows the current row.
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingSendId by rememberSaveable { mutableStateOf<Long?>(null) }
    fun groupOf(id: Long?) = groups.firstOrNull { group -> group.any { it.id == id } }

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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = quick::openNew,
                // Material's extended FAB takes its accessible name from the icon, not the text (I11).
                icon = { Icon(Icons.Default.Edit, contentDescription = "New message") },
                text = { Text("New message") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                    Text("No scheduled messages yet. Tap New message to add one.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(groups, key = { it.first().id }) { group ->
                        val first = group.first()
                        // One-offs open the quick sheet; only repeating schedules use the full editor (§6).
                        val open = { if (first.rule == Recurrence.Once) quick.openEdit(first) else onEdit(first.id) }
                        ScheduleRow(
                            group = group,
                            onToggle = { enabled -> group.forEach { vm.setEnabled(it, enabled) } },
                            onEdit = open,
                            onSendNow = { pendingSendId = first.id },
                            onDryRun = { group.forEach { vm.send(SendRequest(it.id, SendKind.TEST)) } },
                            onDelete = { pendingDeleteId = first.id },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    groupOf(pendingDeleteId)?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete schedule?") },
            text = { Text("\"${group.first().message.take(80)}\" to ${groupTitle(group)}") },
            confirmButton = {
                TextButton(onClick = {
                    group.forEach(vm::delete)
                    pendingDeleteId = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") } },
        )
    }

    groupOf(pendingSendId)?.let { group ->
        SendNowDialog(
            group = group,
            onDismiss = { pendingSendId = null },
            onSend = { requests ->
                pendingSendId = null
                requests.forEach(vm::send)
            },
        )
    }

    QuickScheduleSheet(quick)
}

/** "Mum", or "Mum, Dad +1" for a batch. */
private fun groupTitle(group: List<Schedule>): String = when (group.size) {
    1 -> group[0].targetName
    2 -> "${group[0].targetName} and ${group[1].targetName}"
    else -> "${group[0].targetName}, ${group[1].targetName} +${group.size - 2}"
}

/**
 * Confirms a manual send and says what it does to the schedule (B07). Sending a pending one-off
 * early uses up its occurrence, so it isn't sent again at its time; a recurring schedule can
 * either bring its next occurrence forward or send an extra copy that leaves the series alone.
 */
@Composable
private fun SendNowDialog(group: List<Schedule>, onDismiss: () -> Unit, onSend: (List<SendRequest>) -> Unit) {
    val schedule = group.first()
    val recurring = schedule.rule != Recurrence.Once
    // A switched-off one-off still has its time, and switching it back on would send it then, so
    // sending it now uses that time up. A paused series only gets extra copies.
    val next = if (recurring) schedule.nextAtMillis?.takeIf { schedule.enabled } else schedule.nextAtMillis
    // Every member of a batch goes, each using up its own occurrence.
    val early = next?.let { group.mapNotNull { m -> m.nextAtMillis?.let { at -> SendRequest(m.id, SendKind.SEND_NOW, at) } } }
    val copy = group.map { SendRequest(it.id, SendKind.EXTRA_COPY) }
    val explanation = when {
        next == null && recurring -> "This sends an extra copy. The schedule itself is not changed."
        next == null -> "It has already been sent or its time has passed, so this sends it again."
        recurring -> "Send the ${formatStamp(next)} message early, or send an extra copy and keep that one as it is."
        else -> "It will be sent now instead of at ${formatStamp(next)}."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to ${groupTitle(group)} now?") },
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
    group: List<Schedule>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onSendNow: () -> Unit,
    onDryRun: () -> Unit,
    onDelete: () -> Unit,
) {
    val schedule = group.first()
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onEdit)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (schedule.isLocked) {
                        Icon(Icons.Default.Lock, contentDescription = "Locked chat", modifier = Modifier.width(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(groupTitle(group), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
