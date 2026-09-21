package com.thomaswcode.whatsappscheduler.ui.onboarding

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.WaAccessibilityService

enum class CheckState { OK, MISSING, MANUAL }

data class SetupItem(
    val key: String,
    val title: String,
    val why: String,
    val state: CheckState,
    val action: Intent?,
    val actionLabel: String = "Open settings",
)

/** Evaluates every permission and setting the send pipeline depends on. */
object PermissionChecks {

    fun evaluate(context: Context, hasPin: Boolean): List<SetupItem> {
        val pkg = context.packageName
        val pkgUri = Uri.parse("package:$pkg")
        val alarm = context.getSystemService(AlarmManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        @Suppress("DEPRECATION")
        val whatsappInstalled = listOf(Selectors.WHATSAPP, Selectors.WHATSAPP_BUSINESS).any {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)

        return listOf(
            SetupItem(
                "whatsapp", "WhatsApp installed", "The official app is what gets automated.",
                if (whatsappInstalled) CheckState.OK else CheckState.MISSING, null,
            ),
            SetupItem(
                "accessibility", "Accessibility service enabled",
                "Lets the app tap Send, type the PIN on the lock screen and navigate WhatsApp. Idle otherwise.",
                if (WaAccessibilityService.isEnabled(context)) CheckState.OK else CheckState.MISSING,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            ),
            SetupItem(
                "exact", "Alarms & reminders allowed",
                "Exact alarms fire at the scheduled minute even in Doze.",
                if (alarm.canScheduleExactAlarms()) CheckState.OK else CheckState.MISSING,
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri),
            ),
            SetupItem(
                "overlay", "Display over other apps",
                "Required to bring WhatsApp to the front from the background.",
                if (Settings.canDrawOverlays(context)) CheckState.OK else CheckState.MISSING,
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
            ),
            SetupItem(
                "notifications", "Notifications allowed",
                "Needed for the foreground service and the tap-to-send fallback.",
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) CheckState.OK else CheckState.MISSING,
                appDetails,
            ),
            SetupItem(
                "fullscreen", "Full-screen notifications allowed",
                "Wakes the screen when the phone is locked.",
                if (Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent()) CheckState.OK else CheckState.MISSING,
                if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri) else null,
            ),
            SetupItem(
                "battery", "Battery: unrestricted",
                "Stops Android and One UI from deferring the alarm or killing the service.",
                if (power.isIgnoringBatteryOptimizations(pkg)) CheckState.OK else CheckState.MISSING,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
            ),
            SetupItem(
                "samsung", "Samsung: add to \"Never sleeping apps\"",
                "Settings → Battery → Background usage limits → Never sleeping apps → add WhatsApp Scheduler. Also keep WhatsApp out of \"Deep sleeping apps\".",
                CheckState.MANUAL, appDetails, "Open app info",
            ),
            SetupItem(
                "pin", "Lock-screen PIN stored",
                if (keyguard.isDeviceSecure) "Your lock must be a PIN or password (pattern is not supported). Store it under Settings."
                else "No secure lock detected: nothing to store.",
                when {
                    !keyguard.isDeviceSecure -> CheckState.OK
                    hasPin -> CheckState.OK
                    else -> CheckState.MISSING
                },
                null, "Settings",
            ),
            SetupItem(
                "walock", "WhatsApp app lock off",
                "WhatsApp → Settings → Privacy → App lock must be off. Chat Lock on individual chats is fine.",
                CheckState.MANUAL, null,
            ),
        )
    }

    fun allRequiredOk(items: List<SetupItem>) = items.none { it.state == CheckState.MISSING }
}
