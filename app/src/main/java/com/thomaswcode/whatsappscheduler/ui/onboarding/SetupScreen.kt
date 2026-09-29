package com.thomaswcode.whatsappscheduler.ui.onboarding

import android.content.Intent
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.ui.BackBar

@Composable
fun SetupScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val hasPin by container.secrets.hasPin().collectAsState(initial = false)
    var items by remember { mutableStateOf(PermissionChecks.evaluate(context, hasPin)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(hasPin) { items = PermissionChecks.evaluate(context, hasPin) }
    // Looked at again on each return from a settings screen. Keyed on hasPin, so the observer isn't
    // left with the value it started with, and removed when the screen goes rather than left behind.
    DisposableEffect(lifecycleOwner, hasPin) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) items = PermissionChecks.evaluate(context, hasPin)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(topBar = { BackBar("Setup checklist", onBack) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Text(
                    "Every item marked red blocks automatic sending. Items marked grey cannot be verified by the app; do them once by hand.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            items(items, key = { it.key }) { item ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val (icon, tint) = when (item.state) {
                            CheckState.OK -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                            CheckState.MISSING -> Icons.Default.Error to MaterialTheme.colorScheme.error
                            CheckState.MANUAL -> Icons.Default.Info to MaterialTheme.colorScheme.outline
                        }
                        Icon(icon, contentDescription = item.state.name, tint = tint)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            Text(item.why, style = MaterialTheme.typography.bodySmall)
                            item.action?.let { intent ->
                                TextButton(onClick = { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                                    Text(item.actionLabel)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
