package com.thomaswcode.whatsappscheduler.ui.inspector

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.sending.accessibility.WaAccessibilityService
import com.thomaswcode.whatsappscheduler.ui.BackBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calibration aid: after a countdown, dumps every visible window so the user can switch to
 * WhatsApp (or the lock screen prompt) and read the real view ids on their device.
 */
@Composable
fun InspectorScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service by WaAccessibilityService.instance.collectAsState()
    var countdown by remember { mutableStateOf<Int?>(null) }
    var dump by remember { mutableStateOf(container.log.lastDump()) }

    val shareDump = stringResource(R.string.inspector_share_dump)
    Scaffold(topBar = { BackBar(stringResource(R.string.inspector_title), onBack) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(stringResource(R.string.inspector_help), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                Button(
                    enabled = service != null && countdown == null,
                    onClick = {
                        scope.launch {
                            for (i in 8 downTo 1) { countdown = i; delay(1000) }
                            countdown = null
                            val s = WaAccessibilityService.instance.value ?: return@launch
                            val text = runCatching { UiDriver(s, container.log, 1000).dump() }.getOrElse { "dump failed: $it" }
                            container.log.saveDump("manual capture", text)
                            dump = container.log.lastDump()
                        }
                    },
                ) { Text(countdown?.let { stringResource(R.string.inspector_capturing, it) } ?: stringResource(R.string.inspector_capture)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = dump.isNotBlank(),
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, dump)
                        context.startActivity(Intent.createChooser(send, shareDump))
                    },
                ) { Text(stringResource(R.string.action_share)) }
            }
            Text(stringResource(R.string.inspector_warning), style = MaterialTheme.typography.bodySmall)
            if (service == null) Text(stringResource(R.string.inspector_no_service), color = MaterialTheme.colorScheme.error)
            SelectionContainer {
                Text(
                    dump.ifBlank { stringResource(R.string.inspector_no_capture) },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}
