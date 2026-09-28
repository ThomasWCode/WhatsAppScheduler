package com.thomaswcode.whatsappscheduler.sending.accessibility

import com.thomaswcode.whatsappscheduler.domain.SendResult

/**
 * What one send attempt has done so far. A failure is classified by the point it happened at,
 * because that decides whether trying again is safe.
 */
class JobProgress {

    /** A PIN has been typed and the phone (or the Chat Lock prompt) has not yet accepted it. */
    @Volatile var pinUnconfirmed = false

    /** The PIN was typed and accepted during this attempt, which resets the phone's failure counter. */
    @Volatile var pinAccepted = false

    /** Send is about to be or has been tapped: from here on the message may have gone out. */
    @Volatile var sendTapped = false

    fun classify(step: String, reason: String, dump: String?, retryable: Boolean): SendResult = when {
        sendTapped -> SendResult.Uncertain(step, reason, dump)
        pinUnconfirmed -> SendResult.PinRejected(step, reason, dump)
        else -> SendResult.Failed(step, reason, dump, retryable)
    }
}
