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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.sending.SendService
import com.thomaswcode.whatsappscheduler.ui.formatStamp
import com.thomaswcode.whatsappscheduler.ui.onboarding.PermissionChecks
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleListScreen(
    container: AppContainer,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onSettings: () -> Unit,
    onLogs: () -> Unit,
    onSetup: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val schedules by container.repository.observeSchedules().collectAsState(initial = emptyList())
    val hasPin by container.secrets.hasPin().collectAsState(initial = true)
    val settings by container.settings.settings.collectAsState(initial = null)
    val setupOk = remember(hasPin, schedules) { PermissionChecks.allRequiredOk(PermissionChecks.evaluate(context, hasPin)) }
    var pendingDelete by remember { mutableStateOf<Schedule?>(null) }

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
                            onToggle = { enabled ->
                                scope.launch {
                                    val updated = container.repository.setEnabled(schedule, enabled)
                                    container.alarmScheduler.sync(updated)
                                }
                            },
                            onEdit = { onEdit(schedule.id) },
                            onSendNow = { SendService.enqueue(context, schedule.id, advance = false) },
                            onDryRun = { SendService.enqueue(context, schedule.id, advance = false, forceDryRun = true) },
                            onDelete = { pendingDelete = schedule },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    pendingDelete?.let { schedule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete schedule?") },
            text = { Text("\"${schedule.message.take(80)}\" to ${schedule.targetName}") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.alarmScheduler.cancel(schedule.id)
                        container.repository.delete(schedule)
                    }
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
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
                    DropdownMenuItem(text = { Text("Send now") }, onClick = { menu = false; onSendNow() })
                    DropdownMenuItem(text = { Text("Dry run now") }, onClick = { menu = false; onDryRun() })
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
