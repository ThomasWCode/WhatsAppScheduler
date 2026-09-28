package com.thomaswcode.whatsappscheduler.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class PostponingTest {

    private val london = ZoneId.of("Europe/London")
    private val firstAt = at("2026-09-01T09:00")

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(london).toInstant()

    @Test
    fun movingLaterSkipsTheOccurrencesItPasses() {
        val tuesday = at("2026-09-29T09:00")
        assertEquals(listOf(at("2026-09-30T09:00")), Postponing.skipped(Recurrence.Daily, firstAt, london, tuesday, at("2026-09-30T18:30")))
        // Landing exactly on the next occurrence: one message then, so nothing is missed.
        assertEquals(emptyList<Instant>(), Postponing.skipped(Recurrence.Daily, firstAt, london, tuesday, at("2026-09-30T09:00")))
        assertEquals(emptyList<Instant>(), Postponing.skipped(Recurrence.Daily, firstAt, london, tuesday, at("2026-09-29T18:00")))
        assertEquals(3, Postponing.skipped(Recurrence.Daily, firstAt, london, tuesday, at("2026-10-09T09:00"), limit = 3).size)
    }

    @Test
    fun onlyTheSeriesOwnDaysAreSkipped() {
        val mondaysAndThursdays = Recurrence.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        val monday = at("2026-09-28T09:00")
        assertEquals(listOf(at("2026-10-01T09:00")), Postponing.skipped(mondaysAndThursdays, firstAt, london, monday, at("2026-10-03T12:00")))
    }

    @Test
    fun aOneOffSkipsNothing() {
        assertEquals(emptyList<Instant>(), Postponing.skipped(Recurrence.Once, firstAt, london, firstAt, at("2026-12-01T09:00")))
    }
}
