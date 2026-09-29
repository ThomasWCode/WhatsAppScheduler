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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.sending.accessibility.WaAccessibilityService
import com.thomaswcode.whatsappscheduler.ui.BackBar

/**
 * The setup checklist (improvements I9), grouped by what it blocks: Required, Recommended, By hand.
 * It opens by itself at launch until everything required is done ([onboardingDone][com.thomaswcode.whatsappscheduler.data.settings.Settings.onboardingDone]).
 */
@Composable
fun SetupScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val hasPin by container.secrets.hasPin().collectAsState(initial = false)
    // Observed, so "running" turns green as the service connects, without leaving the screen.
    val service by WaAccessibilityService.instance.collectAsState()
    var items by remember { mutableStateOf(PermissionChecks.evaluate(context, hasPin)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(hasPin, service) { items = PermissionChecks.evaluate(context, hasPin) }
    // Once everything required is done, it no longer opens by itself.
    LaunchedEffect(items) {
        if (PermissionChecks.allRequiredOk(items) && !container.settings.current().onboardingDone) {
            container.settings.update { it.copy(onboardingDone = true) }
        }
    }
    // Looked at again on each return from a settings screen. Keyed on hasPin, so the observer isn't
    // left with the value it started with, and removed when the screen goes rather than left behind.
    DisposableEffect(lifecycleOwner, hasPin) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) items = PermissionChecks.evaluate(context, hasPin)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(topBar = { BackBar(stringResource(R.string.settings_setup_checklist), onBack) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                val missing = items.count { it.group == SetupGroup.REQUIRED && it.state == CheckState.MISSING }
                Text(
                    if (missing == 0) stringResource(R.string.setup_all_done) else pluralStringResource(R.plurals.setup_missing, missing, missing),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            SetupGroup.entries.forEach { group ->
                val inGroup = items.filter { it.group == group }
                if (inGroup.isEmpty()) return@forEach
                item(key = "group-${group.name}") {
                    Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        Text(stringResource(group.title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(group.blurb), style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(inGroup, key = { it.key }) { item -> SetupCard(item) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SetupCard(item: SetupItem) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = when (item.state) {
                CheckState.OK -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                CheckState.MISSING -> Icons.Default.Error to MaterialTheme.colorScheme.error
                CheckState.MANUAL -> Icons.Default.Info to MaterialTheme.colorScheme.outline
            }
            // Said, not only shown by colour.
            val said = stringResource(
                when (item.state) {
                    CheckState.OK -> R.string.setup_state_done
                    CheckState.MISSING -> R.string.setup_state_to_do
                    CheckState.MANUAL -> R.string.setup_state_manual
                },
            )
            Icon(icon, contentDescription = said, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(item.title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(item.why), style = MaterialTheme.typography.bodySmall)
                item.action?.let { intent ->
                    TextButton(onClick = { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                        Text(stringResource(item.actionLabel))
                    }
                }
            }
        }
    }
}
