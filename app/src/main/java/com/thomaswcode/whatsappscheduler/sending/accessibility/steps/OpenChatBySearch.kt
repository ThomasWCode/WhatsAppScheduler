package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selector
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.INVISIBLE_CHARS
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.sending.accessibility.visibleText
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlinx.coroutines.delay

private val youSuffix = Regex("\\s*\\(You\\)$", RegexOption.IGNORE_CASE)

/**
 * True when a row or chat title shows [name]; WhatsApp lists the message-yourself chat as "Name (You)".
 * Invisible characters (direction marks, zero-width spaces) are ignored on both sides.
 */
internal fun chatNameMatches(shown: CharSequence?, name: String, ignoreCase: Boolean = false): Boolean {
    if (shown == null) return false
    val text = visibleText(shown)
    val wanted = visibleText(name)
    return text.equals(wanted, ignoreCase) || text.replace(youSuffix, "").equals(wanted, ignoreCase)
}

/** Finds a chat (group or contact) by its exact name using WhatsApp's own search. */
class OpenChatBySearch(private val context: Context, private val driver: UiDriver, private val log: AutomationLog) {

    private val step = "search"

    /** Brings WhatsApp's chat list to the front, backing out of any open conversation or search. */
    suspend fun goHome(whatsappPackage: String, timeoutMs: Long) {
        val launch = context.packageManager.getLaunchIntentForPackage(whatsappPackage)
            ?: driver.fail(step, "WhatsApp is not installed")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launch)
        // The composer is checked first: inside a conversation, message text could pass for a search label.
        // An open search (left by a failed attempt, which relaunching WhatsApp doesn't close) hides the
        // entry points, so it is backed out of too.
        var backs = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (driver.find(Selectors.waEntry) != null && backs < MAX_BACKS) {
                log.info("$step: a conversation is open, backing out")
                driver.back()
                backs++
            } else if (driver.find(Selectors.waSearchInput) != null && backs < MAX_BACKS) {
                log.info("$step: a search is open, backing out")
                driver.back()
                backs++
            } else if (driver.findAny(*Selectors.waSearchEntryPoints) != null) {
                return
            } else {
                delay(200)
            }
        }
        driver.fail(step, "WhatsApp chat list search not found after ${timeoutMs}ms")
    }

    /** Types [query] into the chat-list search box. */
    suspend fun search(query: String, timeoutMs: Long, secret: Boolean = false) {
        // A click that lands while WhatsApp is still animating in is swallowed, so it is repeated until the box opens.
        var input: AccessibilityNodeInfo? = null
        for (attempt in 1..3) {
            val button = if (attempt == 1) {
                driver.waitForAny(step, *Selectors.waSearchEntryPoints, timeoutMs = timeoutMs).second
            } else {
                driver.findAny(*Selectors.waSearchEntryPoints) ?: break
            }
            driver.click(step, button)
            input = driver.tryWaitForAny(Selectors.waSearchInput, Selectors.waAnyEditable, timeoutMs = 2_500)
            if (input != null) break
            log.warn("$step: search box did not open (try $attempt)")
        }
        if (input == null) input = driver.waitForAny(step, Selectors.waSearchInput, Selectors.waAnyEditable, timeoutMs = timeoutMs).second
        driver.setText(step, input, query, secret = secret)
        delay(600)
    }

    /**
     * Picks the result row whose title equals [name], preferring an exact case-sensitive match, and opens
     * it; the chat's title as shown, or null when WhatsApp's layout doesn't expose it.
     */
    suspend fun openResult(name: String, timeoutMs: Long): String? = open(findResult(name, timeoutMs), name, timeoutMs)

    /**
     * The result row whose title equals [name], preferring an exact case-sensitive match. Its one
     * failure is that there is none, which is about the name rather than about WhatsApp.
     */
    suspend fun findResult(name: String, timeoutMs: Long): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + timeoutMs
        var candidates: List<AccessibilityNodeInfo> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            candidates = driver.findAll(Selectors.waConversationRowTitle).ifEmpty {
                driver.findAll(rowByText(name))
            }
            if (candidates.any { chatNameMatches(it.text, name) }) break
            delay(200)
        }
        val exact = candidates.filter { chatNameMatches(it.text, name) }
        val loose = candidates.filter { chatNameMatches(it.text, name, ignoreCase = true) }
        val chosen = exact.firstOrNull() ?: loose.firstOrNull()
            ?: driver.fail(step, "No chat named '$name' in search results (${candidates.size} rows)")
        if (exact.size > 1) log.warn("$step: ${exact.size} chats named '$name'; picking the first")
        if (exact.isEmpty()) log.warn("$step: only a case-insensitive match for '$name'")
        return chosen
    }

    /**
     * Opens the result [chosen] and checks that the chat which opens is [name]. Returns the title it
     * read, or null when none could be found, in which case which chat opened isn't known.
     */
    suspend fun open(chosen: AccessibilityNodeInfo, name: String, timeoutMs: Long): String? {
        driver.click(step, chosen)
        // Rows are clicked as soon as they appear, so a click lost to the list's entry animation is repeated once.
        if (driver.tryWaitFor(Selectors.waEntry, 2_500) == null) {
            log.warn("$step: chat did not open, clicking the row again")
            chosen.refresh()
            driver.click(step, chosen)
        }
        driver.waitFor(step, Selectors.waEntry, timeoutMs)
        val title = driver.find(Selectors.waChatTitle)?.text?.toString()
        if (title != null && !chatNameMatches(title, name, ignoreCase = true)) {
            driver.fail(step, "Opened chat titled '$title' instead of '$name'")
        }
        delay(300)
        return title
    }

    /**
     * Fallback when the row-title id is unknown: any WhatsApp node showing exactly [name], except
     * the search box itself. Invisible characters are allowed around the name.
     */
    private fun rowByText(name: String) =
        Selector("wa.rowByText", text = Regex("$INVISIBLE_CHARS*${Regex.escape(name)}$INVISIBLE_CHARS*"), pkg = Regex("com\\.whatsapp(\\.w4b)?"), editable = false)

    private companion object {
        /** Presses of Back allowed while leaving conversations and searches. */
        const val MAX_BACKS = 4
    }
}
