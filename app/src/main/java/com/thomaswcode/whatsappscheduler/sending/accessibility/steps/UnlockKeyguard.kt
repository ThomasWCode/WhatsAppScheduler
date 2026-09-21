package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import android.app.KeyguardManager
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlinx.coroutines.delay

/**
 * Enters the stored PIN on the system lock screen (the "bouncer"). Supports both a text field
 * (PIN/password) and a numeric keypad, and both auto-submit and explicit Enter.
 */
class UnlockKeyguard(private val driver: UiDriver, private val log: AutomationLog, private val keyguard: KeyguardManager) {

    private val step = "unlockKeyguard"

    suspend fun run(pin: String, timeoutMs: Long) {
        if (!keyguard.isKeyguardLocked) return
        var field = driver.tryWaitFor(Selectors.keyguardPasswordField, 2_500)
            ?: driver.tryWaitFor(Selectors.keyguardAnyEditable, 1_000)
        var keypad = if (field == null) driver.find(Selectors.keypadDigitById('1')) ?: driver.find(Selectors.keypadDigit('1')) else null
        if (field == null && keypad == null) {
            log.info("$step: no bouncer visible yet, swiping up")
            driver.swipeUp()
            field = driver.tryWaitFor(Selectors.keyguardPasswordField, 2_500) ?: driver.tryWaitFor(Selectors.keyguardAnyEditable, 1_000)
            keypad = if (field == null) driver.find(Selectors.keypadDigitById('1')) ?: driver.find(Selectors.keypadDigit('1')) else null
        }
        when {
            field != null -> {
                driver.setText(step, field, pin, secret = true)
                delay(300)
                if (keyguard.isKeyguardLocked) submit()
            }
            keypad != null -> {
                log.info("$step: typing on keypad")
                for (ch in pin) {
                    val key = driver.find(Selectors.keypadDigitById(ch)) ?: driver.find(Selectors.keypadDigit(ch))
                        ?: driver.fail(step, "Keypad digit not found")
                    driver.click(step, key)
                    delay(80)
                }
                delay(300)
                if (keyguard.isKeyguardLocked) submit()
            }
            else -> driver.fail(step, "Lock screen PIN entry not found (is the lock type PIN or password?)")
        }
        if (!driver.waitUntil(timeoutMs) { !keyguard.isKeyguardLocked }) {
            driver.fail(step, "Keyguard still locked after entering PIN (wrong PIN, or bouncer layout changed)")
        }
        log.info("$step: keyguard dismissed")
    }

    private suspend fun submit() {
        val enter = driver.find(Selectors.keyguardEnter) ?: driver.find(Selectors.keyguardEnterByText)
        if (enter != null) {
            driver.click(step, enter)
            return
        }
        driver.find(Selectors.keyguardPasswordField)?.let { if (driver.imeEnter(it)) log.info("$step: IME enter") }
    }
}
