package com.thomaswcode.whatsappscheduler.ui.onboarding

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.WaAccessibilityService

enum class CheckState { OK, MISSING, MANUAL }

/** How much a setup item matters (improvements I9). */
enum class SetupGroup(val title: String, val blurb: String) {
    REQUIRED("Required", "Automatic sending doesn't work without these."),
    RECOMMENDED("Recommended", "Sending works without these, but can be late, or go wrong unnoticed."),
    MANUAL("By hand", "The app can't check these: do them once."),
}

data class SetupItem(
    val key: String,
    val title: String,
    val why: String,
    val state: CheckState,
    val action: Intent?,
    val actionLabel: String = "Open settings",
    val group: SetupGroup = SetupGroup.REQUIRED,
)

/** Evaluates every permission and setting the send pipeline depends on. */
object PermissionChecks {

    /**
     * [serviceRunning] is whether the accessibility service is connected, which One UI can leave it not
     * after an update or a crash although it is switched on (features F5). Just after the app starts it
     * may not have connected yet, so a check made then can take it as running.
     */
    fun evaluate(
        context: Context,
        hasPin: Boolean,
        serviceRunning: Boolean = WaAccessibilityService.instance.value != null,
    ): List<SetupItem> {
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
            accessibility(WaAccessibilityService.isEnabled(context), serviceRunning),
            SetupItem(
                "exact", "Alarms & reminders allowed",
                "Exact alarms fire at the scheduled minute even in Doze. Without them messages still go, but can be late.",
                if (alarm.canScheduleExactAlarms()) CheckState.OK else CheckState.MISSING,
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri), group = SetupGroup.RECOMMENDED,
            ),
            SetupItem(
                "overlay", "Display over other apps",
                "Required to bring WhatsApp to the front from the background.",
                if (Settings.canDrawOverlays(context)) CheckState.OK else CheckState.MISSING,
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
            ),
            SetupItem(
                "notifications", "Notifications allowed",
                "For the countdown before a send, and for telling you when one couldn't go.",
                // On Android 12 there is no notification permission, only the app-level switch.
                if (NotificationManagerCompat.from(context).areNotificationsEnabled()) CheckState.OK else CheckState.MISSING,
                appDetails, group = SetupGroup.RECOMMENDED,
            ),
            SetupItem(
                "fullscreen", "Full-screen notifications allowed",
                "The other way the app wakes a locked phone's screen, if the first doesn't work.",
                if (Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent()) CheckState.OK else CheckState.MISSING,
                if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri) else null,
                group = SetupGroup.RECOMMENDED,
            ),
            SetupItem(
                "battery", "Battery: unrestricted",
                "Stops Android and One UI from deferring the alarm or killing the service.",
                if (power.isIgnoringBatteryOptimizations(pkg)) CheckState.OK else CheckState.MISSING,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri), group = SetupGroup.RECOMMENDED,
            ),
            SetupItem(
                "samsung", "Samsung: add to \"Never sleeping apps\"",
                "Settings → Battery → Background usage limits → Never sleeping apps → add WhatsApp Scheduler. Also keep WhatsApp out of \"Deep sleeping apps\".",
                CheckState.MANUAL, appDetails, "Open app info", group = SetupGroup.MANUAL,
            ),
            SetupItem(
                "pin", "Lock-screen PIN stored",
                if (keyguard.isDeviceSecure) {
                    "Your lock must be a PIN or password (pattern is not supported). Store it under Settings. If One UI's " +
                        "Auto factory reset is on, keep the stored PIN up to date: the app stops after one rejected unlock, " +
                        "but a wrong PIN still costs attempts."
                } else {
                    "No secure lock detected: nothing to store."
                },
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
                CheckState.MANUAL, null, group = SetupGroup.MANUAL,
            ),
        )
    }

    /** The accessibility item: switched on, and connected (the service can be one without the other). */
    fun accessibility(enabled: Boolean, running: Boolean): SetupItem = when {
        !enabled -> SetupItem(
            "accessibility", "Accessibility service enabled",
            "Lets the app tap Send, type the PIN on the lock screen and navigate WhatsApp. Idle otherwise.",
            CheckState.MISSING, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        !running -> SetupItem(
            "accessibility", "Accessibility service running",
            "It's switched on but not running, which One UI can leave it as after an update or a crash. Turn it off and on again.",
            CheckState.MISSING, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        else -> SetupItem(
            "accessibility", "Accessibility service enabled",
            "Lets the app tap Send, type the PIN on the lock screen and navigate WhatsApp. Idle otherwise.",
            CheckState.OK, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
    }

    /** Whether nothing that blocks sending is missing; recommended and by-hand items don't count. */
    fun allRequiredOk(items: List<SetupItem>) = items.none { it.group == SetupGroup.REQUIRED && it.state == CheckState.MISSING }
}
