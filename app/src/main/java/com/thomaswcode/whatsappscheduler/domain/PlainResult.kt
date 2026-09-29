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
            "search", "lockedChat" -> "Couldn't find the chat$named"
            "deepLink" -> "WhatsApp didn't open the chat for its number"
            "authPrompt" -> "Couldn't open the locked chat"
            "typeAndSend" -> "Couldn't tap Send"
            "verify" -> "Couldn't confirm it was sent"
            "job" -> "Sending took too long"
            else -> null
        }
    }

    /** [reason] from a schedule's last result text ("FAILED: search: …", "manual FAILED: …"). */
    fun fromResult(result: String?, chat: String? = null): String? =
        result?.removePrefix("manual ")?.substringAfter(": ", missingDelimiterValue = "")?.let { reason(it, chat) }

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
}
