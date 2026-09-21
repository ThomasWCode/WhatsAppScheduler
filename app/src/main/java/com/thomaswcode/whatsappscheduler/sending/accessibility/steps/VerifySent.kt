package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.util.AutomationLog

/**
 * Confirms the send: WhatsApp only clears the composer once the message is queued. Seeing the
 * text in a bubble is logged as extra confidence but not required, because long messages are
 * truncated in the tree.
 */
class VerifySent(private val driver: UiDriver, private val log: AutomationLog) {

    private val step = "verify"

    suspend fun run(message: String, timeoutMs: Long) {
        val cleared = driver.waitUntil(timeoutMs) {
            val entry = driver.find(Selectors.waEntry) ?: return@waitUntil false
            entry.refresh()
            entry.text.isNullOrEmpty() || entry.text.toString().startsWith("Message", ignoreCase = true)
        }
        if (!cleared) driver.fail(step, "Composer still holds the text after tapping send")
        val head = message.lineSequence().first().take(40)
        val bubble = driver.findAll(Selectors.waMessageText).lastOrNull { it.text?.toString()?.contains(head) == true }
        if (bubble != null) log.info("$step: message bubble found") else log.warn("$step: composer cleared but no bubble matched; treating as sent")
    }
}
