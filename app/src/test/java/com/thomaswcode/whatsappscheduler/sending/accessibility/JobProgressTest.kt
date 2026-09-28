package com.thomaswcode.whatsappscheduler.sending.accessibility

import com.thomaswcode.whatsappscheduler.domain.SendResult
import org.junit.Assert.assertEquals
import org.junit.Test

class JobProgressTest {

    @Test
    fun failureBeforeAnythingRiskyStaysRetryable() {
        assertEquals(SendResult.Failed("search", "Timed out", null, true), JobProgress().classify("search", "Timed out", null, retryable = true))
        assertEquals(SendResult.Failed("wake", "No PIN", null, false), JobProgress().classify("wake", "No PIN", null, retryable = false))
    }

    @Test
    fun failureAfterTypingThePinIsARejectedPin() {
        val progress = JobProgress().apply { pinUnconfirmed = true }
        assertEquals(SendResult.PinRejected("unlockKeyguard", "Still locked", null), progress.classify("unlockKeyguard", "Still locked", null, retryable = true))
    }

    @Test
    fun anyFailureAfterTheTapIsUncertain() {
        val progress = JobProgress().apply { sendTapped = true }
        assertEquals(SendResult.Uncertain("job", "Job exceeded 90000ms", null), progress.classify("job", "Job exceeded 90000ms", null, retryable = true))
        progress.pinUnconfirmed = true
        assertEquals(SendResult.Uncertain("verify", "Composer still holds the text", null), progress.classify("verify", "Composer still holds the text", null, retryable = true))
    }
}
