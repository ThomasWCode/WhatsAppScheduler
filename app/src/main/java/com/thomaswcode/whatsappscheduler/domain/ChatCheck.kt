package com.thomaswcode.whatsappscheduler.domain

/** The outcome of "Check this chat now" (features F4). */
sealed interface ChatCheck {
    /** WhatsApp's search found the chat by this name, and the chat that opened has that title. */
    data object Found : ChatCheck

    /** The search, or the chat that opened, didn't match; [reason] says which. */
    data class NotFound(val reason: String) : ChatCheck

    /**
     * WhatsApp didn't respond as expected before the search could run, e.g. its layout changed: says
     * nothing about the name. [reason] is the step that failed.
     */
    data class Failed(val reason: String) : ChatCheck

    /**
     * Nothing was tried, e.g. auto-send isn't running or a message is being sent. [stopsOthers] when
     * whatever stopped it would stop the next check too; not when only this chat can't be checked, e.g.
     * it has been put behind Chat Lock since the check was offered.
     */
    data class NotChecked(val why: Why, val stopsOthers: Boolean = true) : ChatCheck {
        /** Why it wasn't tried, which the app says in words of its own. */
        enum class Why { LOCKED, BY_NUMBER, SENDING, SERVICE_OFF, NO_WHATSAPP }
    }
}
