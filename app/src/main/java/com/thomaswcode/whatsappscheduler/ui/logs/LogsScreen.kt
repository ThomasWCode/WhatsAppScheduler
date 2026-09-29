package com.thomaswcode.whatsappscheduler.ui.logs

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.db.SendLog
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.PlainResult
import com.thomaswcode.whatsappscheduler.ui.BackBar
import com.thomaswcode.whatsappscheduler.ui.formatStamp

/** The Sends tab's filters (improvements I8). */
enum class LogFilter(val label: String, val statuses: Set<SendStatus>?) {
    ALL("All", null),
    SENT("Sent", setOf(SendStatus.SENT)),

    // With the uncertain ones: both are waiting on the user.
    FAILED("Failed", setOf(SendStatus.FAILED, SendStatus.UNCERTAIN)),
    MISSED("Missed", setOf(SendStatus.MISSED)),
    ;

    /** What the tab says when nothing is left to show. */
    val empty: String
        get() = when (this) {
            ALL -> "Nothing has been sent yet. Every send and test run is recorded here."
            SENT -> "Nothing has been sent yet."
            FAILED -> "No failed sends."
            MISSED -> "No missed sends."
        }
}

/** A trace line's level: "09-29 08:00:05.123 W …" gives 'W'. */
internal fun traceLevel(line: String): Char? = line.split(' ', limit = 4).getOrNull(2)?.singleOrNull()

/**
 * Logs (improvements I8), under Settings → Diagnostics: each send in words, with the technical detail
 * beneath, and the automation trace for finding out why one failed.
 */
@Composable
fun LogsScreen(container: AppContainer, onBack: () -> Unit, onOpenSchedule: (Long) -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    // Filtered in the query, before its limit, so older matching runs aren't cut off. Remembered per
    // filter; until a new one's first result the list is blank rather than saying there are none.
    val sendsFlow = remember(filter) { container.repository.observeLogs(filter.statuses) }
    val sends by sendsFlow.collectAsState(initial = null)
    val trace by container.log.lines.collectAsState()

    Scaffold(
        topBar = {
            BackBar("Logs", onBack) {
                // Only with the trace in view, so it's plain what they act on.
                if (tab == 1) {
                    TextButton(onClick = { shareTrace(context, trace) }, enabled = trace.isNotEmpty()) { Text("Share") }
                    // Always: it also deletes a window dump the UI inspector saved, which the trace doesn't show.
                    TextButton(onClick = { container.log.clear() }) { Text("Clear") }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sends") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Automation trace") })
            }
            if (tab == 0) {
                SendsTab(sends, filter, onFilter = { chosen -> filter = chosen }, onOpenSchedule)
            } else {
                TraceTab(trace)
            }
        }
    }
}

@Composable
private fun SendsTab(sends: List<SendLog>?, filter: LogFilter, onFilter: (LogFilter) -> Unit, onOpenSchedule: (Long) -> Unit) {
    // Wraps, so none is cut off on a narrow window or with large text.
    FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LogFilter.entries.forEach { choice ->
            FilterChip(selected = filter == choice, onClick = { onFilter(choice) }, label = { Text(choice.label) })
        }
    }
    val shown = sends ?: return
    if (shown.isEmpty()) {
        EmptyState(filter.empty)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(shown, key = { it.id }) { log ->
            SendEntry(log, onOpenSchedule)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

/**
 * One run: what happened and when, why in words where that is known (I2), and the technical detail
 * beneath. Tapping it shows its schedule in the list, while there is one.
 */
@Composable
private fun SendEntry(log: SendLog, onOpenSchedule: (Long) -> Unit) {
    val manual = log.detail?.startsWith(PlainResult.MANUAL) == true
    val why = PlainResult.reason(log.detail, log.targetName)
    val detail = log.detail?.removePrefix(PlainResult.MANUAL)?.takeIf { it.isNotBlank() }
    val scheduleId = log.scheduleId
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (scheduleId != null) Modifier.clickable(onClickLabel = "Show in the list") { onOpenSchedule(scheduleId) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(PlainResult.headline(log.status.name, log.targetName), style = MaterialTheme.typography.titleSmall)
        Text(formatStamp(log.atMillis) + if (manual) " · by hand" else "", style = MaterialTheme.typography.labelSmall)
        why?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun TraceTab(trace: List<String>) {
    Text(
        "The trace names chats: think before sharing it.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (trace.isEmpty()) {
        EmptyState("The trace is empty. Sends and chat checks write to it.")
        return
    }
    // Amber for warnings: the scheme has no warning colour, and its pastel tertiary reads as white on dark.
    val warning = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFFFB74D) else Color(0xFF8A5300)
    val error = MaterialTheme.colorScheme.error
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(trace.asReversed()) { line ->
            val colour = when (traceLevel(line)) {
                'E' -> error
                'W' -> warning
                else -> Color.Unspecified
            }
            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colour)
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Shares the trace as text, oldest line first. Only its latest part when it is long: an Intent can't
 * carry the half megabyte the trace may grow to.
 */
private fun shareTrace(context: Context, trace: List<String>) {
    val all = trace.joinToString("\n")
    val text = if (all.length <= SHARE_MAX_CHARS) all else all.takeLast(SHARE_MAX_CHARS).substringAfter('\n')
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "WhatsApp Scheduler trace")
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share trace")) }
}

private const val SHARE_MAX_CHARS = 100_000
