package com.thomaswcode.whatsappscheduler.ui.health

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.thomaswcode.whatsappscheduler.domain.PinBlock

/**
 * [HealthCheck.evaluate], done again whenever an input changes and on every return to the screen,
 * e.g. from the settings page a Fix opened, or after One UI switched the service off (B24).
 */
@Composable
fun rememberHealth(serviceConnected: Boolean, hasPin: Boolean, pinBlock: PinBlock?, dryRun: Boolean): List<HealthItem> {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    return remember(resumes, serviceConnected, hasPin, pinBlock, dryRun) {
        HealthCheck.evaluate(context, serviceConnected, hasPin, pinBlock, dryRun)
    }
}

/**
 * One line about auto-send (quick-schedule.md §7): the most serious problem (the items come sorted by
 * severity), red when it stops sending, with a button to fix it. Otherwise, with [showReady], a
 * green "Auto-send ready".
 */
@Composable
fun HealthStrip(
    items: List<HealthItem>,
    showReady: Boolean,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val item = items.firstOrNull()
    if (item == null && !showReady) return
    val blocking = item?.problem?.blocksSending == true
    val container = when {
        item == null -> MaterialTheme.colorScheme.secondaryContainer
        blocking -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = when {
        item == null -> MaterialTheme.colorScheme.onSecondaryContainer
        blocking -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
            Icon(
                when {
                    item == null -> Icons.Default.CheckCircle
                    blocking -> Icons.Default.Error
                    else -> Icons.Default.Info
                },
                contentDescription = null,
                tint = if (item == null) readyGreen else Color.Unspecified,
                modifier = Modifier.size(18.dp),
            )
            Text(
                item?.message ?: "Auto-send ready",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp),
            )
            val fix = item?.fix
            when {
                item == null -> Unit
                fix != null -> TextButton(onClick = { runCatching { context.startActivity(Intent(fix).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                    Text(item.fixLabel ?: "Fix")
                }
                item.problem.fixedInAppSettings && onOpenSettings != null -> TextButton(onClick = onOpenSettings) { Text(item.fixLabel ?: "Settings") }
            }
        }
    }
}

private val readyGreen = Color(0xFF2E7D32)
