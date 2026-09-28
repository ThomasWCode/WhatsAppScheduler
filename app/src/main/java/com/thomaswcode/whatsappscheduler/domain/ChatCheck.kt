package com.thomaswcode.whatsappscheduler.domain

/** The outcome of "Check this chat now" (features F4). */
sealed interface ChatCheck {
    /** WhatsApp's search found the chat by this name, and the chat that opened has that title. */
    data object Found : ChatCheck

    /** The search, or the chat that opened, didn't match; [reason] says which. */
    data class NotFound(val reason: String) : ChatCheck

    /** Nothing was tried, e.g. auto-send isn't running or a message is being sent. */
    data class NotChecked(val reason: String) : ChatCheck
}
