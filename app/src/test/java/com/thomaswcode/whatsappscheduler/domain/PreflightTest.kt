package com.thomaswcode.whatsappscheduler.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreflightTest {

    private val nine = Instant.parse("2026-09-29T08:00:00Z").toEpochMilli()

    @Test
    fun itLooksFifteenMinutesBeforeTheSend() {
        val quarterTo = nine - Duration.ofMinutes(15).toMillis()
        assertEquals(quarterTo, Preflight.checkAt(nine, nowMillis = nine - Duration.ofHours(1).toMillis()))
        // Set less than 15 minutes ahead: the user has only just looked at it.
        assertNull(Preflight.checkAt(nine, nowMillis = nine - Duration.ofMinutes(10).toMillis()))
        assertNull(Preflight.checkAt(nine, nowMillis = quarterTo))
    }
}
