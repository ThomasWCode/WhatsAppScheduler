package com.thomaswcode.whatsappscheduler.ui.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.ui.BackBar
import com.thomaswcode.whatsappscheduler.ui.formatStamp

@Composable
fun LogsScreen(container: AppContainer, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val sends by container.repository.observeLogs().collectAsState(initial = emptyList())
    val trace by container.log.lines.collectAsState()

    Scaffold(topBar = { BackBar("Logs", onBack) { TextButton(onClick = { container.log.clear() }) { Text("Clear trace") } } }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sends") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Automation trace") })
            }
            if (tab == 0) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(sends, key = { it.id }) { log ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("${log.status.name} · ${log.targetName}", style = MaterialTheme.typography.titleSmall)
                            Text(formatStamp(log.atMillis), style = MaterialTheme.typography.labelSmall)
                            log.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(trace.asReversed()) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
