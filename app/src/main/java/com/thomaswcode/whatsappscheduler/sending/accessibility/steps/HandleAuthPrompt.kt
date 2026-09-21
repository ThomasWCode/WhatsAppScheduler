package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import android.view.accessibility.AccessibilityNodeInfo
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlinx.coroutines.delay

/**
 * Handles the biometric prompt WhatsApp shows for locked chats by switching it to the device
 * PIN and entering the stored PIN. Returns quietly when no prompt appears.
 */
class HandleAuthPrompt(private val driver: UiDriver, private val log: AutomationLog) {

    private val step = "authPrompt"
    private val prompt = arrayOf(Selectors.authUseCredentialById, Selectors.authUseCredentialButton, Selectors.authPasswordField)

    /**
     * Returns true when a prompt was found and completed, false when none appeared. The wait ends
     * early once [alreadyPast] reports that the destination screen is showing, instead of sitting
     * out [appearTimeoutMs] for a prompt that is not coming. A prompt is its own window and leaves
     * what is beneath it findable, so [alreadyPast] must itself rule one out (a prompt takes focus).
     */
    suspend fun run(pin: String?, appearTimeoutMs: Long, stepTimeoutMs: Long, alreadyPast: () -> Boolean = { false }): Boolean {
        // No prompt is normal (WhatsApp skips it shortly after an unlock), so this must not go through fail().
        val deadline = System.currentTimeMillis() + appearTimeoutMs
        var node: AccessibilityNodeInfo? = null
        while (node == null) {
            if (alreadyPast()) {
                log.info("$step: no authentication needed")
                return false
            }
            node = driver.findAny(*prompt)
            if (node != null || System.currentTimeMillis() >= deadline) break
            delay(100)
        }
        if (node == null) {
            log.info("$step: no authentication prompt within ${appearTimeoutMs}ms")
            return false
        }
        if (pin.isNullOrEmpty()) driver.fail(step, "Locked chat needs the PIN but none is stored")
        if (!Selectors.authPasswordField.matches(node)) {
            driver.click(step, node)
            delay(400)
        }
        val field = driver.tryWaitFor(Selectors.authPasswordField, stepTimeoutMs)
            ?: driver.tryWaitFor(Selectors.authAnyEditable, 1_500)
            ?: driver.fail(step, "PIN field of the credential prompt not found")
        driver.setText(step, field, pin, secret = true)
        delay(300)
        if (driver.find(Selectors.authPasswordField) != null || driver.find(Selectors.authAnyEditable) != null) {
            val enter = driver.find(Selectors.authEnterByText) ?: driver.find(Selectors.keyguardEnter)
            if (enter != null) driver.click(step, enter) else field.let { driver.imeEnter(it) }
        }
        if (!driver.waitUntilGone(Selectors.authPasswordField, stepTimeoutMs)) {
            driver.fail(step, "Credential prompt did not close after entering PIN")
        }
        log.info("$step: prompt completed")
        return true
    }
}
