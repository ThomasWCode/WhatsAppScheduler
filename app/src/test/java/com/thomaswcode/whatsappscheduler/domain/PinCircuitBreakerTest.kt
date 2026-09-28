package com.thomaswcode.whatsappscheduler.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinCircuitBreakerTest {

    private val start = Instant.parse("2026-09-29T08:00:00Z").toEpochMilli()
    private val hour = Duration.ofHours(1).toMillis()

    @Test
    fun nothingRecordedAllowsEntry() {
        assertNull(PinCircuitBreaker.block(PinFailures(), start))
    }

    @Test
    fun oneRejectionBlocksUntilThePinIsSavedAgain() {
        val rejected = PinCircuitBreaker.afterRejection(PinFailures(), start)
        assertEquals(PinBlock.Rejected(start), PinCircuitBreaker.block(rejected, start + 10 * 24 * hour))
        val saved = PinCircuitBreaker.afterPinSaved(rejected, start + hour)
        assertNull(PinCircuitBreaker.block(saved, start + 2 * hour))
    }

    @Test
    fun twoRejectionsInADayBlockEvenAfterSavingAgain() {
        var state = PinCircuitBreaker.afterRejection(PinFailures(), start)
        state = PinCircuitBreaker.afterPinSaved(state, start + hour)
        state = PinCircuitBreaker.afterRejection(state, start + 2 * hour)
        state = PinCircuitBreaker.afterPinSaved(state, start + 3 * hour)
        assertEquals(PinBlock.DailyLimit(start + 24 * hour), PinCircuitBreaker.block(state, start + 4 * hour))
        assertNull(PinCircuitBreaker.block(state, start + 24 * hour))
    }

    @Test
    fun anAcceptedEntryStartsTheCountAgain() {
        var state = PinCircuitBreaker.afterRejection(PinFailures(), start)
        state = PinCircuitBreaker.afterPinSaved(state, start + hour)
        state = PinCircuitBreaker.afterAccepted(state)
        state = PinCircuitBreaker.afterRejection(state, start + 2 * hour)
        state = PinCircuitBreaker.afterPinSaved(state, start + 3 * hour)
        assertNull(PinCircuitBreaker.block(state, start + 4 * hour))
    }

    @Test
    fun oldRejectionsAreForgotten() {
        val state = PinFailures(recentMillis = listOf(start - 30 * hour, start - 25 * hour))
        assertEquals(emptyList<Long>(), PinCircuitBreaker.afterPinSaved(state, start).recentMillis)
    }

    @Test
    fun descriptionsNameTheWayOut() {
        assertTrue(PinBlock.Rejected(start).describe(ZoneOffset.UTC).contains("Update it in Settings"))
        val daily = PinBlock.DailyLimit(start).describe(ZoneOffset.UTC)
        assertTrue(daily, daily.contains("paused until") && daily.contains("08:00"))
    }
}
