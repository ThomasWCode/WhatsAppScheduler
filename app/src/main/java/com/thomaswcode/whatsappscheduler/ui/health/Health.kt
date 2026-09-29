package com.thomaswcode.whatsappscheduler.ui.health

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.app.NotificationManagerCompat
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.data.settings.Settings as AppSettings
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
    /** Test mode: nothing is sent at all, so it is shown as prominently as what stops a send (B11). */
    DRY_RUN(true),

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
        /** When test mode ends, if it is on; [AppSettings.UNTIL_TURNED_OFF] when it doesn't by itself. */
        dryRunUntilMillis: Long?,
    ): List<HealthItem> {
        val items = mutableListOf<HealthItem>()
        val pkgUri = Uri.parse("package:${context.packageName}")
        val accessibility = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val enabled = WaAccessibilityService.isEnabled(context)
        when {
            enabled && !serviceConnected -> items += HealthItem(
                HealthProblem.SERVICE_NOT_RUNNING,
                context.getString(R.string.health_service_not_running),
                context.getString(R.string.action_fix), accessibility,
            )
            !enabled -> items += HealthItem(
                HealthProblem.SERVICE_OFF, context.getString(R.string.health_service_off), context.getString(R.string.action_fix), accessibility,
            )
        }
        @Suppress("DEPRECATION")
        val whatsappInstalled = listOf(Selectors.WHATSAPP, Selectors.WHATSAPP_BUSINESS).any {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }
        if (!whatsappInstalled) {
            items += HealthItem(
                HealthProblem.WHATSAPP_MISSING, context.getString(R.string.health_whatsapp_missing),
                context.getString(R.string.action_install), Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${Selectors.WHATSAPP}")),
            )
        }
        if (!Settings.canDrawOverlays(context)) {
            items += HealthItem(
                HealthProblem.OVERLAY_DENIED,
                context.getString(R.string.health_overlay),
                context.getString(R.string.action_fix), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
            )
        }
        if (pinBlock != null) {
            items += HealthItem(
                HealthProblem.PIN_PAUSED, pinBlock.describe(is24Hour = DateFormat.is24HourFormat(context)), context.getString(R.string.settings_title), null,
            )
        } else if (!hasPin && context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            items += HealthItem(HealthProblem.NO_PIN, context.getString(R.string.health_no_pin), context.getString(R.string.settings_title), null)
        }
        if (dryRunUntilMillis != null) {
            val message = if (dryRunUntilMillis == AppSettings.UNTIL_TURNED_OFF) {
                context.getString(R.string.health_test_mode_until_off)
            } else {
                context.getString(R.string.health_test_mode_until, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(dryRunUntilMillis)))
            }
            items += HealthItem(HealthProblem.DRY_RUN, message, context.getString(R.string.action_turn_off), null)
        }
        if (!context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            items += HealthItem(
                HealthProblem.FULL_SCREEN_DENIED,
                context.getString(R.string.health_full_screen),
                context.getString(R.string.action_fix), Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri),
            )
        }
        // The app-level switch, which also reflects the notification permission.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            items += HealthItem(
                HealthProblem.NOTIFICATIONS_BLOCKED,
                context.getString(R.string.health_notifications),
                context.getString(R.string.action_allow),
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
        if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) {
            items += HealthItem(
                HealthProblem.BATTERY_RESTRICTED,
                context.getString(R.string.health_battery),
                context.getString(R.string.action_fix),
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
            )
        }
        return items.sortedBy { it.problem.ordinal }
    }
}
