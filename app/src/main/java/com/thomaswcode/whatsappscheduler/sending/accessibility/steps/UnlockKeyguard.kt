package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import android.app.KeyguardManager
import android.view.accessibility.AccessibilityNodeInfo
import com.thomaswcode.whatsappscheduler.sending.accessibility.JobProgress
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlinx.coroutines.delay

/**
 * Enters the stored PIN on the system lock screen (the "bouncer"). A numeric PIN is typed on the
 * keypad whenever one is showing: the PIN bouncer's `pinEntry` reports itself as an editable
 * EditText but only takes input from its own keys, so ACTION_SET_TEXT "succeeds" and enters
 * nothing. The text field is used only for password bouncers, which have a real EditText.
 *
 * The PIN is submitted at most once per attempt (B04). From the first key press until the phone
 * unlocks, [JobProgress.pinUnconfirmed] is set, so any failure in between is reported as a
 * rejected PIN, which is never retried and pauses PIN entry.
 */
class UnlockKeyguard(
    private val driver: UiDriver,
    private val log: AutomationLog,
    private val keyguard: KeyguardManager,
    private val progress: JobProgress,
) {
    private val step = "unlockKeyguard"

    suspend fun run(pin: String, timeoutMs: Long) {
        if (!keyguard.isKeyguardLocked) return
        if (!awaitBouncer()) {
            log.info("$step: no bouncer visible yet, swiping up")
            driver.swipeUp()
            if (!awaitBouncer()) driver.fail(step, "Lock screen PIN entry not found (is the lock type PIN or password?)")
        }
        val numeric = pin.all { it in '0'..'9' }
        if (numeric && findKey(pin.first()) != null) {
            unlockWithKeypad(pin)
        } else {
            val field = driver.findAny(Selectors.keyguardPasswordField, Selectors.keyguardAnyEditable)
                ?: driver.fail(step, "Lock screen shows a keypad but the stored PIN is not numeric", retryable = false)
            progress.pinUnconfirmed = true
            driver.setText(step, field, pin, secret = true)
            delay(300)
            if (keyguard.isKeyguardLocked) submit()
        }
        if (!driver.waitUntil(timeoutMs) { !keyguard.isKeyguardLocked }) {
            driver.fail(step, "Keyguard still locked after entering PIN (wrong PIN, or bouncer layout changed)")
        }
        progress.pinUnconfirmed = false
        progress.pinAccepted = true
        log.info("$step: keyguard dismissed")
    }

    private suspend fun awaitBouncer(): Boolean =
        driver.tryWaitForAny(Selectors.keypadDigitById('1'), Selectors.keyguardPasswordField, Selectors.keypadDigit('1'), Selectors.keyguardAnyEditable, timeoutMs = 3_000) != null

    private fun findKey(digit: Char): AccessibilityNodeInfo? = driver.findAny(Selectors.keypadDigitById(digit), Selectors.keypadDigit(digit))

    /**
     * Some bouncers ignore ACTION_CLICK on their keys. The first digit shows which kind this one is
     * before anything has been submitted: when the field still reads empty after a click, that
     * digit is typed again with a touch gesture and so is the rest. When the field can't be read,
     * clicks are kept, because typing a digit twice would make a correct PIN wrong.
     */
    private suspend fun unlockWithKeypad(pin: String) {
        log.info("$step: typing on keypad")
        progress.pinUnconfirmed = true
        press(pin.first(), gestures = false)
        val gestures = enteredLength() == 0 && !driver.waitUntil(CLICK_SETTLE_MS) { (enteredLength() ?: 1) > 0 }
        if (gestures) {
            log.warn("$step: key clicks do not register, typing with touch gestures")
            press(pin.first(), gestures = true)
        }
        for (ch in pin.drop(1)) {
            if (!keyguard.isKeyguardLocked) return
            press(ch, gestures)
        }
        delay(300)
        if (keyguard.isKeyguardLocked) submit()
    }

    private suspend fun press(digit: Char, gestures: Boolean) {
        val key = findKey(digit) ?: driver.fail(step, "Keypad digit not found")
        if (gestures) driver.tapNode(step, key, quiet = true) else driver.click(step, key, quiet = true)
        delay(80)
    }

    /** Number of masked characters showing in the PIN field, or null when it cannot be read. */
    private fun enteredLength(): Int? = driver.find(Selectors.keyguardPasswordField)?.text?.length

    private suspend fun submit() {
        val enter = driver.findAny(Selectors.keyguardEnter, Selectors.keyguardEnterByText)
        if (enter != null) {
            log.info("$step: pressing ${enter.viewIdResourceName ?: "enter"}")
            // A non-clickable key (One UI's OK) is tapped directly rather than clicking some ancestor.
            if (enter.isClickable) driver.click(step, enter, quiet = true) else driver.tapNode(step, enter, quiet = true)
            return
        }
        driver.find(Selectors.keyguardPasswordField)?.let { if (driver.imeEnter(it)) log.info("$step: IME enter") }
    }

    private companion object {
        /** How long a clicked digit may take to show in the field before clicks are judged not to work. */
        const val CLICK_SETTLE_MS = 500L
    }
}
