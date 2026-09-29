package com.thomaswcode.whatsappscheduler.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
        // A runtime permission from Android 13; before that, notifications are on unless switched off.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Only on a fresh start: a recreate (rotation, dark mode switching at sunset) doesn't need it again.
        if (savedInstanceState == null) container.recoverOnOpen()
    }

    override fun onStart() {
        super.onStart()
        // Each time the app comes back, not only on a fresh start: with no sends in between (the other
        // time it runs), sent messages past the period set in Settings would otherwise stay.
        val container = SchedulerApp.container(this)
        container.appScope.launch {
            runCatching { container.housekeeping.run() }.onFailure { container.log.error("Housekeeping failed", it) }
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
