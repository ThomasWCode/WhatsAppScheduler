package com.thomaswcode.whatsappscheduler.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thomaswcode.whatsappscheduler.SchedulerApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Screen a notification asked to open, consumed by [AppNav]. */
    private val destination = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = SchedulerApp.container(this)
        if (savedInstanceState == null) destination.value = intent.getStringExtra(EXTRA_DESTINATION)
        setContent { AppTheme { AppNav(container, destination) } }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Only on a fresh start. A recreate (rotation, dark mode switching at sunset) during a send
        // would otherwise run it again while the occurrence is still pending (B01).
        if (savedInstanceState == null) {
            lifecycleScope.launch { runCatching { container.alarmScheduler.rescheduleAll() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_DESTINATION)?.let { destination.value = it }
    }

    companion object {
        private const val EXTRA_DESTINATION = "destination"
        const val DESTINATION_SETTINGS = "settings"

        /** Opens the app on [destination], reusing the running instance if there is one. */
        fun intent(context: Context, destination: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
