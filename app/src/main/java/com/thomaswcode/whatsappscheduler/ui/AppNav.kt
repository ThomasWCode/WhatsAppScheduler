package com.thomaswcode.whatsappscheduler.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.ui.inspector.InspectorScreen
import com.thomaswcode.whatsappscheduler.ui.logs.LogsScreen
import com.thomaswcode.whatsappscheduler.ui.onboarding.SetupScreen
import com.thomaswcode.whatsappscheduler.ui.schedules.ScheduleEditorScreen
import com.thomaswcode.whatsappscheduler.ui.schedules.ScheduleListScreen
import com.thomaswcode.whatsappscheduler.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val LIST = "list"
    const val EDIT = "edit/{id}"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    const val LOGS = "logs"
    const val INSPECTOR = "inspector"

    fun edit(id: Long) = "edit/$id"
}

/** [destination] is a screen a notification asked for ([MainActivity.DESTINATION_SETTINGS]); it is cleared once opened. */
@Composable
fun AppNav(container: AppContainer, destination: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val requested by destination.collectAsState()
    LaunchedEffect(requested) {
        when (requested ?: return@LaunchedEffect) {
            MainActivity.DESTINATION_SETTINGS -> nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
        }
        destination.value = null
    }
    NavHost(navController = nav, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            ScheduleListScreen(
                container = container,
                onAdd = { nav.navigate(Routes.edit(0L)) },
                onEdit = { nav.navigate(Routes.edit(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onLogs = { nav.navigate(Routes.LOGS) },
                onSetup = { nav.navigate(Routes.SETUP) },
            )
        }
        // The editor's ViewModel reads "id" from its SavedStateHandle, which carries the route's arguments.
        composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
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
