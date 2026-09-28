package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.util.AutomationLog

/**
 * True once the composer no longer holds [message] (B03): it is empty, it is showing its hint, or
 * it holds other text, which covers the hint in any language when the field does not flag it. The
 * text is never compared with the hint's wording, so a message that starts with "Message" still
 * counts as unsent while it sits in the composer.
 */
internal fun composerCleared(text: String?, showingHint: Boolean, message: String): Boolean =
    text.isNullOrEmpty() || showingHint || text.trim() != message.trim()

/**
 * Confirms the send: WhatsApp only clears the composer once the message is queued. Seeing the
 * text in a bubble is logged as extra confidence but not required, because long messages are
 * truncated in the tree. Runs after Send was tapped, so a failure here means "uncertain", not "unsent".
 */
class VerifySent(private val driver: UiDriver, private val log: AutomationLog) {

    private val step = "verify"

    suspend fun run(message: String, timeoutMs: Long) {
        val cleared = driver.waitUntil(timeoutMs) {
            val entry = driver.find(Selectors.waEntry) ?: return@waitUntil false
            entry.refresh()
            composerCleared(entry.text?.toString(), entry.isShowingHintText, message)
        }
        if (!cleared) driver.fail(step, "Composer still holds the text after tapping send")
        val head = message.lineSequence().first().take(40)
        val bubble = driver.findAll(Selectors.waMessageText).lastOrNull { it.text?.toString()?.contains(head) == true }
        if (bubble != null) log.info("$step: message bubble found") else log.warn("$step: composer cleared but no bubble matched; treating as sent")
    }
}
