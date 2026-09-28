package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import android.view.accessibility.AccessibilityNodeInfo
import com.thomaswcode.whatsappscheduler.sending.accessibility.JobProgress
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.sending.accessibility.visibleText
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlinx.coroutines.delay

/** What a Chat Lock prompt lets the app authenticate with, judged by the button's text rather than its id (B26). */
internal enum class PromptOffer { PIN, SECRET_CODE, NOTHING }

internal fun promptOffer(buttonText: CharSequence?, isUseCredentialId: Boolean): PromptOffer {
    val text = visibleText(buttonText)
    return when {
        isUseCredentialId || Selectors.authUseCredentialButton.text!!.matches(text) -> PromptOffer.PIN
        Selectors.useSecretCodeLabel.matches(text) -> PromptOffer.SECRET_CODE
        else -> PromptOffer.NOTHING
    }
}

/**
 * Handles the prompt WhatsApp shows for a locked chat. Returns quietly when none appears. The
 * prompt is a biometric one whose fallback is either the device PIN ("Use PIN") or, on One UI with
 * a secret code set, WhatsApp's own secret code ("Use secret code", then WhatsApp's sheet). A wrong
 * PIN here counts against the phone's lockout like one on the lock screen, so the same guard
 * applies (B04): [pinBlock] stops entry outright, and [JobProgress.pinUnconfirmed] covers the entry
 * itself. The secret code is WhatsApp's alone and doesn't touch the phone's lockout.
 */
class HandleAuthPrompt(
    private val driver: UiDriver,
    private val log: AutomationLog,
    private val progress: JobProgress,
    private val pinBlock: String?,
    private val secretCode: String?,
) {
    private val step = "authPrompt"

    /** Everything that shows WhatsApp is asking to authenticate, most specific first. */
    private val prompt = arrayOf(
        Selectors.waSecretCodeField,
        Selectors.authPasswordField,
        Selectors.authUseCredentialById,
        Selectors.authUseCredentialButton,
        Selectors.authNegativeButton,
    )

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
        when {
            Selectors.waSecretCodeField.matches(node) -> enterSecretCode(button = null, stepTimeoutMs)
            Selectors.authPasswordField.matches(node) -> enterPin(pin, button = null, stepTimeoutMs)
            else -> when (promptOffer(node.text, Selectors.authUseCredentialById.matches(node))) {
                PromptOffer.PIN -> enterPin(pin, node, stepTimeoutMs)
                PromptOffer.SECRET_CODE -> enterSecretCode(node, stepTimeoutMs)
                PromptOffer.NOTHING -> driver.fail(
                    step, "The Chat Lock prompt offers neither the PIN nor the secret code (its button reads '${visibleText(node.text)}')", retryable = false,
                )
            }
        }
        return true
    }

    private suspend fun enterPin(pin: String?, button: AccessibilityNodeInfo?, stepTimeoutMs: Long) {
        if (pin.isNullOrEmpty()) driver.fail(step, "Locked chat needs the PIN but none is stored", retryable = false)
        pinBlock?.let { driver.fail(step, it, retryable = false) }
        if (button != null && !pressIfStillThere(button)) return
        val field = driver.tryWaitFor(Selectors.authPasswordField, stepTimeoutMs)
            ?: driver.tryWaitFor(Selectors.authAnyEditable, 1_500)
            ?: driver.fail(step, "PIN field of the credential prompt not found")
        progress.pinUnconfirmed = true
        driver.setText(step, field, pin, secret = true)
        delay(300)
        if (driver.find(Selectors.authPasswordField) != null || driver.find(Selectors.authAnyEditable) != null) {
            val enter = driver.find(Selectors.authEnterByText) ?: driver.find(Selectors.keyguardEnter)
            if (enter != null) driver.click(step, enter) else field.let { driver.imeEnter(it) }
        }
        // Gone by every selector that could have found the field, not only the first: when the fallback
        // found it, waiting on the other selector alone would pass at once and count a wrong PIN as accepted.
        if (!driver.waitUntil(stepTimeoutMs) { driver.findAny(Selectors.authPasswordField, Selectors.authAnyEditable) == null }) {
            driver.fail(step, "Credential prompt did not close after entering PIN")
        }
        progress.pinUnconfirmed = false
        progress.pinAccepted = true
        log.info("$step: prompt completed with the PIN")
    }

    private suspend fun enterSecretCode(button: AccessibilityNodeInfo?, stepTimeoutMs: Long) {
        val code = secretCode
        if (code.isNullOrEmpty()) {
            driver.fail(step, "The locked chat asks for its Chat Lock secret code, but none is stored in Settings", retryable = false)
        }
        if (button != null && !pressIfStillThere(button)) return
        val field = driver.tryWaitFor(Selectors.waSecretCodeField, stepTimeoutMs)
            ?: driver.fail(step, "WhatsApp's secret code field did not appear")
        driver.setText(step, field, code, secret = true)
        delay(200)
        val go = driver.find(Selectors.waSecretCodeContinue)
        if (go != null) driver.click(step, go) else driver.imeEnter(field)
        // Retrying can't make a wrong code right, and a wrong code is WhatsApp's to rate-limit.
        if (!driver.waitUntilGone(Selectors.waSecretCodeField, stepTimeoutMs)) {
            driver.fail(step, "WhatsApp did not accept the stored secret code", retryable = false)
        }
        log.info("$step: prompt completed with the secret code")
    }

    /**
     * Presses the prompt's button unless the prompt has gone meanwhile, typically because the user
     * finished biometrics first. A press on a stale node would fall back to a tap at its old
     * position, which is now somewhere inside the chat.
     */
    private suspend fun pressIfStillThere(button: AccessibilityNodeInfo): Boolean {
        if (!button.refresh()) {
            log.info("$step: the prompt closed by itself (biometrics?)")
            return false
        }
        driver.click(step, button)
        delay(400)
        return true
    }
}
