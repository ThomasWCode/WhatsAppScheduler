package com.thomaswcode.whatsappscheduler.sending

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.thomaswcode.whatsappscheduler.sending.accessibility.JobProgress
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.sending.accessibility.steps.UnlockKeyguard
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** How the phone was before a send touched it, so it can be put back that way afterwards. */
data class UnlockOutcome(val wasLocked: Boolean, val screenWasOff: Boolean)

/** Turns the screen on and gets past the lock screen before WhatsApp is driven; re-locks afterwards. */
class WakeUnlockController(
    private val context: Context,
    private val log: AutomationLog,
    private val notifications: Notifications,
) {
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)
    private val power get() = context.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    /** Taken before [ensureUnlocked], so the phone is re-locked even when unlocking fails part-way. */
    fun snapshot(): UnlockOutcome = UnlockOutcome(wasLocked = keyguard.isKeyguardLocked, screenWasOff = !power.isInteractive)

    /** True when the lock screen stands in the way and only the stored PIN gets past it. */
    fun needsPin(): Boolean = keyguard.isKeyguardLocked && keyguard.isDeviceSecure

    /** [pinBlock] is why the stored PIN must not be typed right now (B04), or null when it may be. */
    @Suppress("DEPRECATION")
    suspend fun ensureUnlocked(driver: UiDriver, before: UnlockOutcome, pin: String?, pinBlock: String?, progress: JobProgress, stepTimeoutMs: Long) {
        val (wasLocked, screenWasOff) = before
        if (!screenWasOff && !wasLocked) return
        log.info("wake: screenOff=$screenWasOff locked=$wasLocked secure=${keyguard.isDeviceSecure}")

        wakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "WaScheduler:wake",
        ).apply { acquire(120_000L) }

        UnlockBroker.reset()
        launchUnlockActivity()
        if (!driver.waitUntil(5_000) { power.isInteractive }) driver.fail("wake", "Screen did not turn on")

        if (wasLocked) {
            if (keyguard.isDeviceSecure) {
                if (pin.isNullOrEmpty()) driver.fail("wake", "Phone is locked and no PIN is stored in the app", retryable = false)
                pinBlock?.let { driver.fail("wake", it, retryable = false) }
                delay(800)
                UnlockKeyguard(driver, log, keyguard, progress).run(pin, stepTimeoutMs)
            } else {
                val result = withTimeoutOrNull(stepTimeoutMs) { UnlockBroker.dismissal.await() }
                if (result == null || !result.first) driver.fail("wake", "Keyguard dismissal failed: ${result?.second ?: "timeout"}")
            }
        }
        if (!driver.waitUntil(3_000) { !keyguard.isKeyguardLocked }) driver.fail("wake", "Keyguard still reported locked")
        notifications.cancel(Notifications.ID_UNLOCK)
    }

    /** Ends the unlock activity so WhatsApp (or the previous app) is on top. */
    fun releaseActivity() {
        UnlockBroker.finish()
        notifications.cancel(Notifications.ID_UNLOCK)
    }

    fun relock(driver: UiDriver) {
        log.info("wake: locking screen again")
        driver.lockScreen()
    }

    fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun launchUnlockActivity() {
        val intent = Intent(context, UnlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val pending = PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notifications.show(Notifications.ID_UNLOCK, notifications.unlock(pending))
        runCatching { context.startActivity(intent) }.onFailure { log.warn("wake: direct start failed, relying on full-screen intent: ${it.message}") }
    }
}
