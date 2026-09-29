package com.thomaswcode.whatsappscheduler.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.ui.inspector.InspectorScreen
import com.thomaswcode.whatsappscheduler.ui.logs.LogsScreen
import com.thomaswcode.whatsappscheduler.ui.onboarding.SetupScreen
import com.thomaswcode.whatsappscheduler.ui.quick.QuickEvent
import com.thomaswcode.whatsappscheduler.ui.schedules.ScheduleEditorScreen
import com.thomaswcode.whatsappscheduler.ui.schedules.ScheduleListScreen
import com.thomaswcode.whatsappscheduler.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val LIST = "list"
    /** The full editor; for a new schedule the optional arguments pre-fill it (from the quick sheet's Repeat…). */
    const val EDIT = "edit/{id}?kind={kind}&name={name}&phone={phone}&locked={locked}&message={message}&at={at}"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    const val LOGS = "logs"
    const val INSPECTOR = "inspector"

    fun edit(id: Long) = "edit/$id"

    fun repeat(prefill: QuickEvent.OpenFullEditor): String {
        val r = prefill.recipient
        val args = listOfNotNull(
            r?.let { "kind=${it.kind.name}" },
            r?.let { "name=${Uri.encode(it.chatName)}" },
            r?.phoneE164?.let { "phone=${Uri.encode(it)}" },
            r?.let { "locked=${it.isLocked}" },
            prefill.message.takeIf { it.isNotBlank() }?.let { "message=${Uri.encode(it)}" },
            prefill.atMillis?.let { "at=$it" },
        )
        return "edit/0" + if (args.isEmpty()) "" else args.joinToString("&", prefix = "?")
    }
}

/** [destination] is a screen a notification asked for ([MainActivity.DESTINATION_SETTINGS]); it is cleared once opened. */
@Composable
fun AppNav(container: AppContainer, destination: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val requested by destination.collectAsState()
    // A row a notification asked to show (features F2); the list scrolls to it and clears this.
    val focusRow = remember { MutableStateFlow<Long?>(null) }
    LaunchedEffect(requested) {
        when (val target = requested ?: return@LaunchedEffect) {
            MainActivity.DESTINATION_SETTINGS -> nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
            // The launcher activity is exported, so anything else is ignored, and a malformed route mustn't
            // crash the app.
            else -> when {
                target.startsWith(MainActivity.DESTINATION_ROW) -> target.removePrefix(MainActivity.DESTINATION_ROW).toLongOrNull()?.let { id ->
                    nav.popBackStack(Routes.LIST, inclusive = false)
                    focusRow.value = id
                }
                // A new schedule pre-filled from outside (Repeat… in the Share sheet).
                target == "edit/0" || target.startsWith("edit/0?") -> runCatching { nav.navigate(target) }
            }
        }
        destination.value = null
    }
    NavHost(navController = nav, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            ScheduleListScreen(
                container = container,
                onEdit = { nav.navigate(Routes.edit(it)) },
                onRepeat = { nav.navigate(Routes.repeat(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onSetup = { nav.navigate(Routes.SETUP) },
                focusRow = focusRow,
            )
        }
        // The editor's ViewModel reads "id" from its SavedStateHandle, which carries the route's arguments.
        composable(
            Routes.EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("kind") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("phone") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("locked") { type = NavType.BoolType; defaultValue = false },
                navArgument("message") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("at") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) {
            ScheduleEditorScreen(
                container = container,
                // Pops the editor only while it is still there, so a save finishing after Back was
                // pressed can't pop the list as well and leave a blank screen (B05).
                onDone = { nav.popBackStack(Routes.EDIT, inclusive = true) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { nav.popBackStack() },
                onSetup = { nav.navigate(Routes.SETUP) },
                onInspector = { nav.navigate(Routes.INSPECTOR) },
                onLogs = { nav.navigate(Routes.LOGS) },
            )
        }
        composable(Routes.SETUP) { SetupScreen(container = container, onBack = { nav.popBackStack() }) }
        composable(Routes.LOGS) { LogsScreen(container = container, onBack = { nav.popBackStack() }) }
        composable(Routes.INSPECTOR) { InspectorScreen(container = container, onBack = { nav.popBackStack() }) }
    }
}
