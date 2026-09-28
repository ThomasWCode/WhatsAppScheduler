package com.thomaswcode.whatsappscheduler.ui.health

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
import com.thomaswcode.whatsappscheduler.domain.PinBlock
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.WaAccessibilityService

/**
 * Something that will stop, or quietly change, automatic sending; most serious first (F5).
 * [blocksSending] problems make every send fail; the rest delay it or change what happens.
 */
enum class HealthProblem(val blocksSending: Boolean, val fixedInAppSettings: Boolean = false) {
    /** Switched on in Accessibility settings but not connected: One UI can leave it so after an update or crash. */
    SERVICE_NOT_RUNNING(true),
    SERVICE_OFF(true),
    WHATSAPP_MISSING(true),

    /** Needed to bring WhatsApp to the front from the background. */
    OVERLAY_DENIED(true),
    PIN_PAUSED(true, fixedInAppSettings = true),
    NO_PIN(true, fixedInAppSettings = true),
    DRY_RUN(false),

    /** Alarms fall back to inexact ones, which the phone may deliver minutes late. */
    EXACT_ALARMS_DENIED(false),

    /** A locked phone is woken through the full-screen notification as well as directly. */
    FULL_SCREEN_DENIED(false),
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
        val pkgUri = Uri.parse("package:${context.packageName}")
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
        @Suppress("DEPRECATION")
        val whatsappInstalled = listOf(Selectors.WHATSAPP, Selectors.WHATSAPP_BUSINESS).any {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }
        if (!whatsappInstalled) items += HealthItem(HealthProblem.WHATSAPP_MISSING, "WhatsApp isn't installed.", null, null)
        if (!Settings.canDrawOverlays(context)) {
            items += HealthItem(
                HealthProblem.OVERLAY_DENIED,
                "\"Display over other apps\" is off, so WhatsApp can't be brought to the front.",
                "Fix", Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
            )
        }
        if (pinBlock != null) {
            items += HealthItem(HealthProblem.PIN_PAUSED, pinBlock.describe(), "Settings", null)
        } else if (!hasPin && context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            items += HealthItem(HealthProblem.NO_PIN, "No lock-screen PIN is stored, so a locked phone can't be unlocked to send.", "Settings", null)
        }
        if (dryRun) items += HealthItem(HealthProblem.DRY_RUN, "Dry run is on: messages are typed but never sent.", null, null)
        if (!context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            items += HealthItem(
                HealthProblem.EXACT_ALARMS_DENIED,
                "\"Alarms & reminders\" is off, so messages may go out late.",
                "Fix", Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri),
            )
        }
        if (Build.VERSION.SDK_INT >= 34 && !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            items += HealthItem(
                HealthProblem.FULL_SCREEN_DENIED,
                "Full-screen notifications are off, so a locked phone may not wake up to send.",
                "Fix", Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri),
            )
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
            )
        }
        return items.sortedBy { it.problem.ordinal }
    }
}
