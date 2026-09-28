package com.thomaswcode.whatsappscheduler.ui.health

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.thomaswcode.whatsappscheduler.domain.PinBlock
import com.thomaswcode.whatsappscheduler.sending.accessibility.WaAccessibilityService

/** Something that will stop, or quietly change, automatic sending; most serious first (F5). */
enum class HealthProblem(val blocksSending: Boolean) {
    /** Switched on in Accessibility settings but not connected: One UI can leave it so after an update or crash. */
    SERVICE_NOT_RUNNING(true),
    SERVICE_OFF(true),
    PIN_PAUSED(true),
    NO_PIN(true),
    DRY_RUN(false),
    NOTIFICATIONS_BLOCKED(false),
    BATTERY_RESTRICTED(false),
}

data class HealthItem(val problem: HealthProblem, val message: String, val fixLabel: String?, val fix: Intent?)

/** Evaluates what stands between a scheduled message and it being sent automatically. */
object HealthCheck {

    /** [serviceConnected] comes from [WaAccessibilityService.instance], so callers can observe it. */
    fun evaluate(
        context: Context,
        serviceConnected: Boolean,
        hasPin: Boolean,
        pinBlock: PinBlock?,
        dryRun: Boolean,
    ): List<HealthItem> {
        val items = mutableListOf<HealthItem>()
        val accessibility = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val enabled = WaAccessibilityService.isEnabled(context)
        when {
            enabled && !serviceConnected -> items += HealthItem(
                HealthProblem.SERVICE_NOT_RUNNING,
                "Auto-send is switched on but not running. Turn it off and on again.",
                "Fix", accessibility,
            )
            !enabled -> items += HealthItem(HealthProblem.SERVICE_OFF, "Auto-send is off: the accessibility service isn't enabled.", "Fix", accessibility)
        }
        if (pinBlock != null) {
            items += HealthItem(HealthProblem.PIN_PAUSED, pinBlock.describe(), null, null)
        } else if (!hasPin && context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            items += HealthItem(HealthProblem.NO_PIN, "No lock-screen PIN is stored, so a locked phone can't be unlocked to send.", null, null)
        }
        if (dryRun) items += HealthItem(HealthProblem.DRY_RUN, "Dry run is on: messages are typed but never sent.", null, null)
        // The app-level switch, which on Android 13+ also reflects the notification permission; on 12 there is no permission.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            items += HealthItem(
                HealthProblem.NOTIFICATIONS_BLOCKED,
                "Notifications are blocked, so a failed send can't ask you to finish it.",
                "Allow",
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
        if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) {
            items += HealthItem(
                HealthProblem.BATTERY_RESTRICTED,
                "Battery use is restricted, so the phone may delay or stop sends.",
                "Fix",
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}")),
            )
        }
        return items.sortedBy { it.problem.ordinal }
    }
}
