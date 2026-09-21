package com.thomaswcode.whatsappscheduler.ui

import androidx.compose.runtime.Composable
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

object Routes {
    const val LIST = "list"
    const val EDIT = "edit/{id}"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    const val LOGS = "logs"
    const val INSPECTOR = "inspector"

    fun edit(id: Long) = "edit/$id"
}

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
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
        composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ScheduleEditorScreen(
                container = container,
                scheduleId = entry.arguments?.getLong("id") ?: 0L,
                onDone = { nav.popBackStack() },
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
