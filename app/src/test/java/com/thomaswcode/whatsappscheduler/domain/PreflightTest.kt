package com.thomaswcode.whatsappscheduler.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun alarmsRecoveredLateLookAtOnce() {
        val tenTo = nine - Duration.ofMinutes(10).toMillis()
        assertEquals(tenTo, Preflight.checkAt(nine, nowMillis = tenTo, catchUp = true))
        assertEquals(nine - Duration.ofMinutes(15).toMillis(), Preflight.checkAt(nine, nowMillis = nine - Duration.ofHours(1).toMillis(), catchUp = true))
        // Nothing to look at once the send is due.
        assertNull(Preflight.checkAt(nine, nowMillis = nine, catchUp = true))
    }

    @Test
    fun aWarningIsAboutATimeAndAChat() {
        assertEquals(Preflight.warningKey(nine, "Mum"), Preflight.warningKey(nine, "Mum"))
        assertTrue(Preflight.warningKey(nine, "Mum") != Preflight.warningKey(nine, "Dad"))
    }
}
