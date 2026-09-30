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
        openFolder(pin, secretCode, stepTimeoutMs)
        log.info("$step: folder open, selecting chat")
        search.openResult(name, stepTimeoutMs)
    }

    /**
     * Reveals Locked chats, from WhatsApp's chat list, and opens it: the folder's chats are then on
     * screen to pick from by name ([OpenChatBySearch.findResult]).
     */
    suspend fun openFolder(pin: String?, secretCode: String?, stepTimeoutMs: Long) {
        if (!secretCode.isNullOrEmpty()) {
            log.info("$step: revealing locked chats with secret code")
            search.search(secretCode, stepTimeoutMs, secret = true)
        }
        val folder = driver.tryWaitFor(Selectors.waLockedChatsRow, stepTimeoutMs)
            ?: driver.fail(
                step,
                if (secretCode.isNullOrEmpty()) "'Locked chats' folder not visible (hidden by a secret code that is not configured?)"
                else "'Locked chats' didn't appear for the stored secret code (is it the one set in WhatsApp?)",
            )
        driver.click(step, folder)
        // WhatsApp authenticates before it shows the folder, so once the folder is up there is no prompt to wait for.
        val prompted = auth.run(pin, appearTimeoutMs = 4_000, stepTimeoutMs = stepTimeoutMs, alreadyPast = ::folderOpen)
        if (prompted) delay(500)
        // The open folder is itself titled "Locked chats", so only a search box still on screen means it did not open.
        if (driver.find(Selectors.waLockedChatsRow) != null && driver.find(Selectors.waSearchInput) != null) {
            driver.fail(step, "Locked chats folder did not open after authentication")
        }
    }

    /**
     * The folder's chat list is showing: its title and rows are present, the search box is gone,
     * and WhatsApp still has focus (an authentication prompt would have taken it).
     */
    private fun folderOpen(): Boolean =
        driver.find(Selectors.waSearchInput) == null &&
            driver.find(Selectors.waConversationRowTitle) != null &&
            driver.find(Selectors.waLockedChatsRow) != null &&
            driver.foregroundPackage()?.startsWith(Selectors.WHATSAPP) == true
}
