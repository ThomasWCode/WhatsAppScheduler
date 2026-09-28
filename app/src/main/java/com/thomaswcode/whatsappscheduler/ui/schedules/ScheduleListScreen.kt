package com.thomaswcode.whatsappscheduler.ui.schedules

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.db.Attention
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.FriendlyTime
import com.thomaswcode.whatsappscheduler.domain.PinCircuitBreaker
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.sending.FallbackActivity
import com.thomaswcode.whatsappscheduler.sending.SendKind
import com.thomaswcode.whatsappscheduler.sending.SendPhase
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import com.thomaswcode.whatsappscheduler.sending.SendState
import com.thomaswcode.whatsappscheduler.ui.formatStamp
import com.thomaswcode.whatsappscheduler.ui.health.HealthStrip
import com.thomaswcode.whatsappscheduler.ui.health.rememberHealth
import com.thomaswcode.whatsappscheduler.ui.quick.QuickEvent
import com.thomaswcode.whatsappscheduler.ui.quick.QuickScheduleSheet
import com.thomaswcode.whatsappscheduler.ui.quick.QuickScheduleViewModel
import com.thomaswcode.whatsappscheduler.ui.quick.rememberTimeFormat
import kotlinx.coroutines.launch

/**
 * The main list (quick-schedule.md §7): a health strip, then Needs attention, Upcoming, Repeating and
 * a collapsed Sent section. Rows swipe away with Undo; a row being sent shows so and can't be changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleListScreen(
    container: AppContainer,
    onEdit: (Long) -> Unit,
    onRepeat: (QuickEvent.OpenFullEditor) -> Unit,
    onSettings: () -> Unit,
    onSetup: () -> Unit,
    vm: ScheduleListViewModel = viewModel(factory = ScheduleListViewModel.factory(container)),
    quick: QuickScheduleViewModel = viewModel(factory = QuickScheduleViewModel.factory(container)),
) {
    val context = LocalContext.current
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val sections = remember(schedules) { ListSections.of(schedules) }
    val dueAt by vm.dueAt.collectAsStateWithLifecycle()
    val sendState by vm.sendState.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val pinFailures by vm.pinFailures.collectAsStateWithLifecycle()
    val serviceConnected by vm.serviceConnected.collectAsStateWithLifecycle()
    val health = rememberHealth(serviceConnected, hasPin, PinCircuitBreaker.block(pinFailures, now.toInstant().toEpochMilli()), settings?.dryRun == true)
    val timeFormat = rememberTimeFormat()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Held by id and saved, so an open dialog survives rotation and always shows the current rows.
    var sendIds by rememberSaveable { mutableStateOf<LongArray?>(null) }
    var sendCopyOnly by rememberSaveable { mutableStateOf(false) }
    var postponeIds by rememberSaveable { mutableStateOf<LongArray?>(null) }
    var sentOpen by rememberSaveable { mutableStateOf(false) }

    fun announce(message: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            // A newer message replaces the one showing, so its Undo isn't queued behind the old one.
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message,
                actionLabel = action,
                withDismissAction = action == null,
                duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    LaunchedEffect(quick) {
        quick.eventFlow.collect { event ->
            when (event) {
                is QuickEvent.Scheduled -> {
                    val undo = event.createdIds
                    if (undo != null) announce(event.summary, "Undo") { quick.undo(undo) } else announce(event.summary)
                }
                is QuickEvent.Deleted -> announce(event.summary)
                is QuickEvent.OpenFullEditor -> onRepeat(event)
            }
        }
    }
    LaunchedEffect(vm) {
        vm.eventFlow.collect { event ->
            when (event) {
                is ListEvent.Deleted -> announce(event.summary, "Undo") { vm.undoDelete(event.removed) }
                is ListEvent.Dismissed -> announce(event.summary, "Undo") { vm.undoDismiss(event.previous) }
                is ListEvent.Postponed -> announce(event.summary, "Undo") { vm.undoPostpone(event.before) }
                is ListEvent.Failed -> announce(event.message)
            }
        }
    }

    fun askToSend(group: List<Schedule>, copyOnly: Boolean = false) {
        sendIds = group.map { it.id }.toLongArray()
        sendCopyOnly = copyOnly
    }

    val row: @Composable (Section, List<Schedule>, Modifier) -> Unit = { section, group, modifier ->
        val first = group.first()
        val once = first.rule == Recurrence.Once
        val busy = busyText(group, sendState)
        val test = { group.forEach { vm.send(SendRequest(it.id, SendKind.TEST)) } }
        val postpone = { postponeIds = group.map { it.id }.toLongArray() }
        // A series moves its next occurrence; one that is paused or finished has none to move.
        val canPostpone = once || (first.enabled && first.nextAtMillis != null)
        ScheduleRow(
            group = group,
            line = busy ?: statusLine(section, group, now, dueAt, timeFormat),
            busy = busy != null,
            needsYou = section == Section.ATTENTION,
            switchedOn = if (section == Section.REPEATING) first.enabled else null,
            onSwitch = { vm.setEnabled(group, it) },
            onOpen = when {
                // B08: what is being sent can't be changed under the send.
                busy != null -> { { announce("It's being sent now. You can change it once that has finished.") } }
                // Sent: the main action is to send it again (§8). A one-off opens the quick sheet (§6).
                section == Section.SENT -> { { quick.openSendAgain(group) } }
                once -> { { quick.openEdit(first) } }
                else -> { { onEdit(first.id) } }
            },
            menu = when (section) {
                Section.ATTENTION -> listOfNotNull(
                    // A series' missed occurrence can't be moved yet: it would move the series' next one.
                    if (once) "Postpone…" to postpone else null,
                    "Send now…" to { askToSend(group, copyOnly = !once) },
                    "Test (dry run)" to test,
                    if (once) "Delete" to { vm.delete(group) } else null,
                )
                Section.UPCOMING -> listOfNotNull(
                    "Postpone…" to postpone,
                    "Send now…" to { askToSend(group) },
                    "Test (dry run)" to test,
                    // One-offs have no switch (R4); one switched off before that can still be resumed.
                    if (!first.enabled) "Resume" to { vm.setEnabled(group, true) } else null,
                    "Delete" to { vm.delete(group) },
                )
                Section.REPEATING -> listOfNotNull(
                    if (canPostpone) "Postpone…" to postpone else null,
                    "Send now…" to { askToSend(group) },
                    "Test (dry run)" to test,
                    "Delete" to { vm.delete(group) },
                )
                Section.SENT -> listOf("Send again…" to { quick.openSendAgain(group) }, "Delete" to { vm.delete(group) })
            },
            actions = when {
                section == Section.SENT -> listOf("Send again…" to { quick.openSendAgain(group) })
                section != Section.ATTENTION -> emptyList()
                first.attention == Attention.UNCERTAIN -> listOf(
                    "Open chat" to { runCatching { context.startActivity(FallbackActivity.checkChat(context, first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    "Mark as sent" to { vm.markSent(group) },
                    "Send again" to { askToSend(group, copyOnly = !once) },
                )
                else -> listOfNotNull(
                    "Send now" to { askToSend(group, copyOnly = !once) },
                    if (once) "Postpone…" to postpone else null,
                    "Discard" to { vm.discard(group) },
                )
            },
            // In Needs attention a swipe discards: for a series that means this occurrence, not the series.
            onSwipe = when {
                busy != null -> null
                section == Section.ATTENTION -> { { vm.discard(group) } }
                else -> { { vm.delete(group) } }
            },
            modifier = modifier,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WhatsApp Scheduler") },
                // Logs live under Settings → Diagnostics now (R7); failures surface in Needs attention.
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") } },
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        ) {
            item(key = "health") {
                HealthStrip(health, showReady = true, onOpenSettings = onSettings, onClick = onSetup, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (sections.isEmpty) {
                item(key = "empty") {
                    Text(
                        "No scheduled messages yet. Tap New message to add one.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    )
                }
            }
            listOf(
                Triple(Section.ATTENTION, "Needs attention", sections.attention),
                Triple(Section.UPCOMING, "Upcoming", sections.upcoming),
                Triple(Section.REPEATING, "Repeating", sections.repeating),
            ).forEach { (section, title, groups) ->
                if (groups.isNotEmpty()) {
                    item(key = "header-$section") { SectionHeader(title) }
                    // Keyed per section: a series that needs attention is in two sections at once.
                    items(groups, key = { "$section-${it.first().id}" }) { group -> row(section, group, Modifier.animateItem()) }
                }
            }
            if (sections.sent.isNotEmpty()) {
                item(key = "header-SENT") { SectionHeader("Sent (${sections.sent.size})", expanded = sentOpen) { sentOpen = !sentOpen } }
                if (sentOpen) items(sections.sent, key = { "SENT-${it.first().id}" }) { group -> row(Section.SENT, group, Modifier.animateItem()) }
            }
        }
    }

    sendIds?.let { ids -> schedules.filter { it.id in ids } }?.takeIf { it.isNotEmpty() }?.let { group ->
        SendNowDialog(
            group = group,
            copyOnly = sendCopyOnly,
            onDismiss = { sendIds = null },
            onSend = { requests ->
                sendIds = null
                requests.forEach(vm::send)
            },
        )
    }

    postponeIds?.let { ids -> schedules.filter { it.id in ids } }?.takeIf { it.isNotEmpty() }?.let { group ->
        PostponeDialog(
            schedule = group.first(),
            now = now,
            onDismiss = { postponeIds = null },
            onMove = { at ->
                postponeIds = null
                vm.postpone(group, at, "Moved to " + FriendlyTime.dayAndTime(at.atZone(now.zone), now, timeFormat))
            },
        )
    }

    QuickScheduleSheet(quick)
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean? = null, onToggle: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
            .padding(top = 16.dp, bottom = 6.dp),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (expanded != null) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Hide sent" else "Show sent")
        }
    }
}

/**
 * One entry on the list: an initials avatar, the recipients, when it goes (or why it needs you) and
 * two lines of the message. [switchedOn] is set for repeating rows only (R4); [actions] are the
 * inline buttons under it, such as Send now and Discard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleRow(
    group: List<Schedule>,
    line: String,
    busy: Boolean,
    needsYou: Boolean,
    switchedOn: Boolean?,
    onSwitch: (Boolean) -> Unit,
    onOpen: () -> Unit,
    menu: List<Pair<String, () -> Unit>>,
    actions: List<Pair<String, () -> Unit>>,
    onSwipe: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val first = group.first()
    val scope = rememberCoroutineScope()
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    // Not saveable: a row brought back by Undo must come back in place, not swiped away.
    val swipe = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    var menuOpen by remember { mutableStateOf(false) }
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.padding(vertical = 4.dp),
        gesturesEnabled = onSwipe != null,
        onDismiss = {
            onSwipe?.invoke()
            // Normally the row is gone by now; if it stays (the delete failed), it comes back into place.
            scope.launch { swipe.reset() }
        },
        backgroundContent = { SwipeBackground(swipe.dismissDirection, if (needsYou) "Discard" else "Delete") },
    ) {
        Card(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            colors = if (needsYou) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = if (actions.isEmpty()) 12.dp else 2.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Avatar(first.targetName)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (group.any { it.isLocked }) Icon(Icons.Default.Lock, contentDescription = "Chat Lock", modifier = Modifier.size(16.dp).padding(end = 2.dp))
                            if (group.size == 1 && first.targetKind == TargetKind.GROUP) {
                                Icon(Icons.Default.Group, contentDescription = "Group", modifier = Modifier.size(16.dp).padding(end = 2.dp))
                            }
                            Text(groupTitle(group), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (busy) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                line,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (needsYou) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(first.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (switchedOn != null) {
                        Switch(checked = switchedOn, onCheckedChange = if (busy) null else onSwitch, modifier = Modifier.padding(start = 4.dp))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            menu.forEach { (label, action) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })
                            }
                        }
                    }
                }
                if (actions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 44.dp)) {
                        actions.forEach { (label, action) -> TextButton(onClick = action, enabled = !busy) { Text(label) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, label: String) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (direction == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
        modifier = Modifier
            .fillMaxSize()
            .clip(CardDefaults.shape)
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 20.dp),
    ) {
        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
        Spacer(Modifier.width(8.dp))
        Text(label, color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Avatar(name: String) {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
    )
    val (background, content) = palette[(name.hashCode() and Int.MAX_VALUE) % palette.size]
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(CircleShape).background(background)) {
        Text(initials(name), color = content, style = MaterialTheme.typography.titleSmall)
    }
}

/** "Sending…" while any member is being sent, "Queued to send" while waiting its turn; null otherwise. */
internal fun busyText(group: List<Schedule>, state: SendState): String? = when {
    group.any { it.id == state.currentId } -> if (state.phase == SendPhase.WAITING_TO_RETRY) "Waiting to try again…" else "Sending…"
    group.any { state.isBusy(it.id) } -> "Queued to send"
    else -> null
}

/**
 * Confirms a manual send and says what it does to the schedule (B07). Sending a pending one-off
 * early uses up its occurrence, so it isn't sent again at its time; a recurring schedule can
 * either bring its next occurrence forward or send an extra copy that leaves the series alone.
 * [copyOnly] is for an occurrence that needs attention: that message goes now, the series carries on.
 */
@Composable
private fun SendNowDialog(group: List<Schedule>, copyOnly: Boolean, onDismiss: () -> Unit, onSend: (List<SendRequest>) -> Unit) {
    val schedule = group.first()
    val recurring = schedule.rule != Recurrence.Once
    val plan = sendNowPlan(group)
    val early = plan.early.takeIf { it.isNotEmpty() && !copyOnly }
    val next = early?.minOf { it.occurrenceAtMillis ?: Long.MAX_VALUE }
    val sentBefore = schedule.attention == Attention.UNCERTAIN || (schedule.attention == null && schedule.shownStatus == SendStatus.SENT)
    val explanation = when {
        copyOnly && recurring -> "This sends that message now. The series carries on as scheduled."
        next == null && recurring -> "This sends an extra copy. The schedule itself is not changed."
        next == null && schedule.attention == Attention.UNCERTAIN -> "It may already have gone out, so check the chat first. This sends it again."
        next == null && sentBefore -> "It has already been sent, so this sends it again."
        next == null -> "It wasn't sent at its time. This sends it now."
        recurring -> "Send the ${formatStamp(next)} message early, or send an extra copy and keep that one as it is."
        (early?.size ?: 0) < group.size -> "It will be sent now instead of at ${formatStamp(next)}, to the recipients it hasn't gone to yet."
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
                else -> TextButton(onClick = { onSend(plan.copies) }) {
                    Text(if (recurring && !copyOnly) "Send a copy" else if (sentBefore) "Send again" else "Send now")
                }
            }
        },
        dismissButton = {
            Row {
                if (early != null && recurring) TextButton(onClick = { onSend(plan.copies) }) { Text("Send a copy") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** What Send now… can do for a row: bring pending occurrences forward, or send everyone a copy. */
internal data class SendNowPlan(val early: List<SendRequest>, val copies: List<SendRequest>)

/**
 * Decided per member, since a batch can be part-way through: a member that is still pending uses up
 * its own occurrence (so its alarm doesn't send it again later), and one that has already gone isn't
 * sent again unless nothing in the row is pending. A switched-off one-off still has its time, and
 * switching it back on would send it then, so sending it now uses that time up; a paused series only
 * gets extra copies.
 */
internal fun sendNowPlan(group: List<Schedule>): SendNowPlan {
    val early = group.mapNotNull { member ->
        val pending = member.nextAtMillis?.takeIf { member.rule == Recurrence.Once || member.enabled }
        pending?.let { SendRequest(member.id, SendKind.SEND_NOW, it) }
    }
    return SendNowPlan(early, group.map { SendRequest(it.id, SendKind.EXTRA_COPY) })
}
