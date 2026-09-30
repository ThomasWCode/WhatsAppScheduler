package com.thomaswcode.whatsappscheduler.domain

sealed class SendResult {
    data object Sent : SendResult()
    data object DryRun : SendResult()

    /**
     * Failed before Send was tapped, so another attempt cannot duplicate the message. [retryable]
     * is false when waiting cannot help, e.g. no PIN is stored or PIN entry is paused.
     */
    data class Failed(val step: String, val reason: String, val windowDump: String? = null, val retryable: Boolean = true) : SendResult() {
        companion object {
            /** Step name for checks made before anything happens on the phone. */
            const val PRECHECK = "precheck"
        }
    }

    /** Went wrong after Send was tapped: the message may have gone out, so it is never retried (B02). */
    data class Uncertain(val step: String, val reason: String, val windowDump: String? = null) : SendResult()

    /** The phone or the Chat Lock prompt stayed locked after the stored PIN was entered (B04). Never retried. */
    data class PinRejected(val step: String, val reason: String, val windowDump: String? = null) : SendResult()
}

/** One unit of work handed to a [com.thomaswcode.whatsappscheduler.sending.MessageSender]. */
data class SendJob(
    val scheduleId: Long,
    val target: ChatTarget,
    val message: String,
    val dryRun: Boolean,
    val attempt: Int,
    /**
     * Called when the chat turns out to be behind Chat Lock though [target] didn't say so: WhatsApp
     * asked to unlock it, or it was in Locked chats. The queue remembers that, for the chat's later sends.
     */
    val onFoundLocked: suspend () -> Unit = {},
)
