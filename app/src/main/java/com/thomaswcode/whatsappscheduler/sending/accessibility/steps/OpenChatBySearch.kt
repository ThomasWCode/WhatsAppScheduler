package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selector
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlinx.coroutines.delay

/** Finds a chat (group or contact) by its exact name using WhatsApp's own search. */
class OpenChatBySearch(private val context: Context, private val driver: UiDriver, private val log: AutomationLog) {

    private val step = "search"

    /** Brings WhatsApp's chat list to the front, backing out of any open conversation. */
    suspend fun goHome(whatsappPackage: String, timeoutMs: Long) {
        val launch = context.packageManager.getLaunchIntentForPackage(whatsappPackage)
            ?: driver.fail(step, "WhatsApp is not installed")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launch)
        repeat(4) {
            if (driver.tryWaitFor(Selectors.waSearchButton, 1_500) != null || driver.find(Selectors.waSearchByDesc) != null) return
            if (driver.find(Selectors.waEntry) != null) {
                log.info("$step: a conversation is open, backing out")
                driver.back()
            }
        }
        driver.waitForAny(step, Selectors.waSearchButton, Selectors.waSearchByDesc, timeoutMs = timeoutMs)
    }

    /** Types [query] into the chat-list search box. */
    suspend fun search(query: String, timeoutMs: Long, secret: Boolean = false) {
        val (_, button) = driver.waitForAny(step, Selectors.waSearchButton, Selectors.waSearchByDesc, timeoutMs = timeoutMs)
        driver.click(step, button)
        val input = driver.tryWaitFor(Selectors.waSearchInput, timeoutMs) ?: driver.waitFor(step, Selectors.waAnyEditable, timeoutMs)
        driver.setText(step, input, query, secret = secret)
        delay(600)
    }

    /** Picks the result row whose title equals [name], preferring an exact case-sensitive match. */
    suspend fun openResult(name: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var candidates: List<AccessibilityNodeInfo> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            candidates = driver.findAll(Selectors.waConversationRowTitle).ifEmpty {
                driver.findAll(rowByText(name))
            }
            if (candidates.any { it.text?.toString()?.trim() == name }) break
            delay(200)
        }
        val exact = candidates.filter { it.text?.toString()?.trim() == name }
        val loose = candidates.filter { it.text?.toString()?.trim().equals(name, ignoreCase = true) }
        val chosen = exact.firstOrNull() ?: loose.firstOrNull()
            ?: driver.fail(step, "No chat named '$name' in search results (${candidates.size} rows)")
        if (exact.size > 1) log.warn("$step: ${exact.size} chats named '$name'; picking the first")
        if (exact.isEmpty()) log.warn("$step: only a case-insensitive match for '$name'")
        driver.click(step, chosen)
        driver.waitFor(step, Selectors.waEntry, timeoutMs)
        val title = driver.find(Selectors.waChatTitle)?.text?.toString()
        if (title != null && !title.equals(name, ignoreCase = true)) {
            driver.fail(step, "Opened chat titled '$title' instead of '$name'")
        }
        delay(300)
    }

    /** Fallback when the row-title id is unknown: any WhatsApp node showing exactly [name]. */
    private fun rowByText(name: String) =
        Selector("wa.rowByText", text = Regex(Regex.escape(name)), pkg = Regex("com\\.whatsapp(\\.w4b)?"))
}
