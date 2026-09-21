package com.thomaswcode.whatsappscheduler.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thomaswcode.whatsappscheduler.SchedulerApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = SchedulerApp.container(this)
        setContent { AppTheme { AppNav(container) } }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch { runCatching { container.alarmScheduler.rescheduleAll() } }
    }
}
