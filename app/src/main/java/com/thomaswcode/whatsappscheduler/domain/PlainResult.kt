package com.thomaswcode.whatsappscheduler.domain

/**
 * Plain-language results (improvements I2): what a run's step code means to the user. The list, the
 * notifications and Logs say this; the technical text ("search: Timed out after 8000ms waiting for
 * wa.searchInput") stays in Logs for diagnosis.
 */
object PlainResult {

    /** How a run by hand is marked at the start of its logged detail. */
    const val MANUAL = "(manual) "

    /**
     * What went wrong, in a sentence, from a run's detail ("search: …", or "(manual) search: …" for a
     * run by hand), naming [chat] where that helps. Null when there is nothing plain to say.
     */
    fun reason(detail: String?, chat: String? = null): String? {
        val text = detail?.removePrefix(MANUAL)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.endsWith(AFTER_TAP)) return "Check the chat before sending it again"
        val step = text.substringBefore(": ", missingDelimiterValue = "")
        val rest = text.substringAfter(": ", missingDelimiterValue = "")
        val named = chat?.let { " '$it'" }.orEmpty()
        return when (step) {
            SendResult.Failed.PRECHECK -> when {
                "Accessibility service" in rest -> "Auto-send is off"
                "WhatsApp is not installed" in rest -> "WhatsApp isn't installed"
                // Already a sentence, e.g. why PIN entry is paused.
                else -> rest.takeIf { it.isNotBlank() }?.trimEnd('.')
            }
            "wake", "unlockKeyguard" -> "Couldn't unlock the phone"
            "lockedChat" if LOCKED_CHATS in rest -> "Couldn't open Locked chats"
            "search", "lockedChat" -> "Couldn't find the chat$named"
            "deepLink" -> "WhatsApp didn't open the chat for its number"
            "authPrompt" -> when {
                NO_SECRET_CODE in rest -> "Chat Lock asked for its secret code, which isn't stored"
                NO_PIN in rest -> "Chat Lock asked for the phone's PIN, which isn't stored"
                CODE_REFUSED in rest -> "WhatsApp didn't accept the stored secret code"
                else -> "Couldn't open the locked chat"
            }
            "typeAndSend" -> "Couldn't tap Send"
            "verify" -> "Couldn't confirm it was sent"
            "job" -> "Sending took too long"
            else -> null
        }
    }

    /**
     * What to do about a run's failure, in a sentence, where there is something the user can do: mostly
     * about Chat Lock, which a chat can be behind without its details saying so. A chat that isn't in
     * WhatsApp's search is looked for in Locked chats too, when the app has a way in (the sender); these
     * say what is missing when it didn't. Null when there is nothing to suggest.
     */
    fun nextStep(detail: String?): String? {
        val text = detail?.removePrefix(MANUAL)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.endsWith(AFTER_TAP)) return null
        val step = text.substringBefore(": ", missingDelimiterValue = "")
        val rest = text.substringAfter(": ", missingDelimiterValue = "")
        return when {
            step == "search" && NOT_FOUND in rest && LOCKED_CHATS in rest -> "Check the name is exactly as WhatsApp shows it."
            step == "search" && NOT_FOUND in rest -> "If the chat is behind Chat Lock, store the Chat Lock secret code in Settings, so the app can look in Locked chats."
            step == "lockedChat" && CODE_NOT_SHOWING in rest -> "Check the Chat Lock secret code in Settings is the one set in WhatsApp."
            step == "lockedChat" && FOLDER_HIDDEN in rest -> "If Locked chats is hidden, store the Chat Lock secret code in Settings."
            step == "authPrompt" && NO_SECRET_CODE in rest -> "Store the Chat Lock secret code in Settings, then send it again."
            step == "authPrompt" && NO_PIN in rest -> "Store the phone's PIN in Settings, then send it again."
            step == "authPrompt" && CODE_REFUSED in rest -> "Check the Chat Lock secret code in Settings, then send it again."
            else -> null
        }
    }

    /** [reason] from a schedule's last result text ("FAILED: search: …", "manual FAILED: …"). */
    fun fromResult(result: String?, chat: String? = null): String? = detailOf(result)?.let { reason(it, chat) }

    /** [nextStep] from a schedule's last result text, as [fromResult]. */
    fun nextStepFromResult(result: String?): String? = detailOf(result)?.let(::nextStep)

    private fun detailOf(result: String?): String? = result?.removePrefix("manual ")?.substringAfter(": ", missingDelimiterValue = "")

    /** How the history puts a run to [chat] in words, by its status. */
    fun headline(status: String, chat: String): String = when (status) {
        "SENT" -> "Sent to $chat"
        "FAILED" -> "Couldn't send to $chat"
        "UNCERTAIN" -> "May have been sent to $chat"
        "DRY_RUN" -> "Test run for $chat: not sent"
        "MISSED" -> "Missed: your message to $chat"
        "SKIPPED" -> "Not sent to $chat"
        else -> "$status · $chat"
    }

    private const val AFTER_TAP = "(after Send was tapped)"

    // What the steps say (OpenChatBySearch, AccessibilitySender, OpenLockedChat, HandleAuthPrompt).
    private const val NOT_FOUND = "No chat named"
    private const val LOCKED_CHATS = "Locked chats"
    private const val CODE_NOT_SHOWING = "didn't appear for the stored secret code"
    private const val FOLDER_HIDDEN = "folder not visible"
    private const val NO_SECRET_CODE = "secret code, but none is stored"
    private const val NO_PIN = "needs the PIN but none is stored"
    private const val CODE_REFUSED = "did not accept the stored secret code"
}
