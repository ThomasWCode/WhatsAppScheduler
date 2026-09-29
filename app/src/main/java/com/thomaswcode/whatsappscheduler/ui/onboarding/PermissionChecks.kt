package com.thomaswcode.whatsappscheduler.ui.onboarding

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.WaAccessibilityService

enum class CheckState { OK, MISSING, MANUAL }

/** How much a setup item matters (improvements I9). */
enum class SetupGroup(@StringRes val title: Int, @StringRes val blurb: Int) {
    REQUIRED(R.string.setup_group_required, R.string.setup_group_required_blurb),
    RECOMMENDED(R.string.setup_group_recommended, R.string.setup_group_recommended_blurb),
    MANUAL(R.string.setup_group_manual, R.string.setup_group_manual_blurb),
}

/** An item on the setup checklist; its texts are string resources, which the screen reads. */
data class SetupItem(
    val key: String,
    @StringRes val title: Int,
    @StringRes val why: Int,
    val state: CheckState,
    val action: Intent?,
    @StringRes val actionLabel: Int = R.string.setup_open_settings,
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
                "whatsapp", R.string.setup_whatsapp_title, R.string.setup_whatsapp_why,
                if (whatsappInstalled) CheckState.OK else CheckState.MISSING, null,
            ),
            accessibility(WaAccessibilityService.isEnabled(context), serviceRunning),
            SetupItem(
                "overlay", R.string.setup_overlay_title, R.string.setup_overlay_why,
                if (Settings.canDrawOverlays(context)) CheckState.OK else CheckState.MISSING,
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
            ),
            SetupItem(
                "notifications", R.string.setup_notifications_title, R.string.setup_notifications_why,
                if (NotificationManagerCompat.from(context).areNotificationsEnabled()) CheckState.OK else CheckState.MISSING,
                appDetails, group = SetupGroup.RECOMMENDED,
            ),
            SetupItem(
                "fullscreen", R.string.setup_fullscreen_title, R.string.setup_fullscreen_why,
                if (notifications.canUseFullScreenIntent()) CheckState.OK else CheckState.MISSING,
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri),
                group = SetupGroup.RECOMMENDED,
            ),
            SetupItem(
                "battery", R.string.setup_battery_title, R.string.setup_battery_why,
                if (power.isIgnoringBatteryOptimizations(pkg)) CheckState.OK else CheckState.MISSING,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri), group = SetupGroup.RECOMMENDED,
            ),
            SetupItem(
                "samsung", R.string.setup_samsung_title, R.string.setup_samsung_why,
                CheckState.MANUAL, appDetails, R.string.setup_open_app_info, group = SetupGroup.MANUAL,
            ),
            SetupItem(
                "pin", R.string.setup_pin_title,
                if (keyguard.isDeviceSecure) R.string.setup_pin_why else R.string.setup_pin_why_no_lock,
                when {
                    !keyguard.isDeviceSecure -> CheckState.OK
                    hasPin -> CheckState.OK
                    else -> CheckState.MISSING
                },
                null, R.string.settings_title,
            ),
            SetupItem(
                "walock", R.string.setup_walock_title, R.string.setup_walock_why,
                CheckState.MANUAL, null, group = SetupGroup.MANUAL,
            ),
        )
    }

    /** The accessibility item: switched on, and connected (the service can be one without the other). */
    fun accessibility(enabled: Boolean, running: Boolean): SetupItem = when {
        !enabled -> SetupItem(
            "accessibility", R.string.setup_accessibility_enabled_title, R.string.setup_accessibility_why,
            CheckState.MISSING, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        !running -> SetupItem(
            "accessibility", R.string.setup_accessibility_running_title, R.string.setup_accessibility_running_why,
            CheckState.MISSING, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        else -> SetupItem(
            "accessibility", R.string.setup_accessibility_enabled_title, R.string.setup_accessibility_why,
            CheckState.OK, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
    }

    /** Whether nothing that blocks sending is missing; recommended and by-hand items don't count. */
    fun allRequiredOk(items: List<SetupItem>) = items.none { it.group == SetupGroup.REQUIRED && it.state == CheckState.MISSING }
}
