package com.thomaswcode.whatsappscheduler.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.thomaswcode.whatsappscheduler.SchedulerApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Screen a notification asked to open, consumed by [AppNav]. */
    private val destination = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Picks status-bar icon contrast per theme, so they are visible in dark mode (B25, I3).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = SchedulerApp.container(this)
        if (savedInstanceState == null) destination.value = intent.getStringExtra(EXTRA_DESTINATION)
        setContent { AppTheme { AppNav(container, destination) } }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Only on a fresh start: a recreate (rotation, dark mode switching at sunset) doesn't need it
        // again. It runs in the app's scope, not this activity's, so a recreate while it is still
        // working doesn't cancel the one recovery pass the fresh start was owed.
        if (savedInstanceState == null) {
            container.appScope.launch {
                runCatching { container.alarmScheduler.rescheduleAll() }.onFailure { container.log.error("rescheduleAll failed", it) }
                // Once: schedules from before recipients existed become recents, keeping their Chat Lock flag.
                runCatching {
                    if (!container.settings.recipientsLinked()) {
                        container.recipients.linkUnlinkedSchedules()
                        container.settings.markRecipientsLinked()
                    }
                }.onFailure { container.log.error("Linking recipients failed", it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_DESTINATION)?.let { destination.value = it }
    }

    companion object {
        private const val EXTRA_DESTINATION = "destination"
        const val DESTINATION_SETTINGS = "settings"

        /** Opens the app on [destination] (Settings, or a navigation route), reusing the running instance if there is one. */
        fun intent(context: Context, destination: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
