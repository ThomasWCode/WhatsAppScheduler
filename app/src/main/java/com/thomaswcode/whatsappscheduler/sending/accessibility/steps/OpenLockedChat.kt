package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlinx.coroutines.delay

/**
 * Opens a chat inside WhatsApp's "Locked chats" folder: reveals the folder (via secret code when
 * one is set), authenticates with the device PIN, then selects the chat by name.
 */
class OpenLockedChat(
    private val driver: UiDriver,
    private val log: AutomationLog,
    private val search: OpenChatBySearch,
    private val auth: HandleAuthPrompt,
) {
    private val step = "lockedChat"

    suspend fun run(name: String, pin: String?, secretCode: String?, stepTimeoutMs: Long) {
        if (!secretCode.isNullOrEmpty()) {
            log.info("$step: revealing locked chats with secret code")
            search.search(secretCode, stepTimeoutMs, secret = true)
        }
        val folder = driver.tryWaitFor(Selectors.waLockedChatsRow, stepTimeoutMs)
            ?: driver.fail(step, "'Locked chats' folder not visible (hidden by a secret code that is not configured?)")
        driver.click(step, folder)
        auth.run(pin, appearTimeoutMs = 4_000, stepTimeoutMs = stepTimeoutMs)
        delay(500)
        if (driver.find(Selectors.waLockedChatsRow) != null && driver.find(Selectors.waConversationRowTitle) == null) {
            driver.fail(step, "Locked chats folder did not open after authentication")
        }
        search.openResult(name, stepTimeoutMs)
    }
}
