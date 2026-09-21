package com.thomaswcode.whatsappscheduler.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecurrenceCalculatorTest {

    private val london: ZoneId = ZoneId.of("Europe/London")

    private fun at(text: String, zone: ZoneId = london): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun local(instant: Instant, zone: ZoneId = london): ZonedDateTime = instant.atZone(zone)

    @Test
    fun onceReturnsFirstOnlyWhenInFuture() {
        val first = at("2026-10-01T09:00")
        assertEquals(first, RecurrenceCalculator.nextOccurrence(Recurrence.Once, first, at("2026-09-30T09:00"), london))
        assertNull(RecurrenceCalculator.nextOccurrence(Recurrence.Once, first, first, london))
        assertNull(RecurrenceCalculator.nextOccurrence(Recurrence.Once, first, at("2026-10-02T09:00"), london))
    }

    @Test
    fun dailyKeepsWallClockAcrossDstEnd() {
        val first = at("2026-10-24T09:00")
        val next = RecurrenceCalculator.nextOccurrence(Recurrence.Daily, first, first, london)!!
        assertEquals("2026-10-25T09:00", local(next).toLocalDateTime().toString())
        assertEquals(25L * 3600, next.epochSecond - first.epochSecond)
        val afterDst = RecurrenceCalculator.nextOccurrence(Recurrence.Daily, first, next, london)!!
        assertEquals("2026-10-26T09:00", local(afterDst).toLocalDateTime().toString())
        assertEquals(24L * 3600, afterDst.epochSecond - next.epochSecond)
    }

    @Test
    fun dailyKeepsWallClockAcrossDstStart() {
        val first = at("2026-03-28T09:00")
        val next = RecurrenceCalculator.nextOccurrence(Recurrence.Daily, first, first, london)!!
        assertEquals("2026-03-29T09:00", local(next).toLocalDateTime().toString())
        assertEquals(23L * 3600, next.epochSecond - first.epochSecond)
    }

    @Test
    fun dailyReturnsFirstWhenAfterIsEarlier() {
        val first = at("2026-10-01T09:00")
        assertEquals(first, RecurrenceCalculator.nextOccurrence(Recurrence.Daily, first, at("2026-09-01T00:00"), london))
    }

    @Test
    fun weeklyPicksNextConfiguredDay() {
        val first = at("2026-09-21T18:30")
        val rule = Recurrence.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        val next = RecurrenceCalculator.nextOccurrence(rule, first, first, london)!!
        assertEquals("2026-09-24T18:30", local(next).toLocalDateTime().toString())
        val following = RecurrenceCalculator.nextOccurrence(rule, first, next, london)!!
        assertEquals("2026-09-28T18:30", local(following).toLocalDateTime().toString())
    }

    @Test
    fun weeklyWithNoDaysHasNoOccurrence() {
        val first = at("2026-09-21T18:30")
        assertNull(RecurrenceCalculator.nextOccurrence(Recurrence.Weekly(emptySet()), first, first, london))
    }

    @Test
    fun monthlyClampsToShortMonths() {
        val first = at("2026-01-31T08:00")
        val rule = Recurrence.Monthly(31)
        val feb = RecurrenceCalculator.nextOccurrence(rule, first, first, london)!!
        assertEquals("2026-02-28T08:00", local(feb).toLocalDateTime().toString())
        val mar = RecurrenceCalculator.nextOccurrence(rule, first, feb, london)!!
        assertEquals("2026-03-31T08:00", local(mar).toLocalDateTime().toString())
    }

    @Test
    fun everyNHoursStepsInAbsoluteTime() {
        val first = at("2026-09-21T06:00")
        val rule = Recurrence.EveryNHours(6)
        val next = RecurrenceCalculator.nextOccurrence(rule, first, at("2026-09-21T13:00"), london)!!
        assertEquals(at("2026-09-21T18:00"), next)
        assertEquals(first, RecurrenceCalculator.nextOccurrence(rule, first, at("2026-09-20T13:00"), london))
    }

    @Test
    fun everyNDaysKeepsWallClockAndPhase() {
        val first = at("2026-10-20T07:30")
        val rule = Recurrence.EveryNDays(3)
        val next = RecurrenceCalculator.nextOccurrence(rule, first, at("2026-10-24T12:00"), london)!!
        assertEquals("2026-10-26T07:30", local(next).toLocalDateTime().toString())
        val after = RecurrenceCalculator.nextOccurrence(rule, first, next, london)!!
        assertEquals("2026-10-29T07:30", local(after).toLocalDateTime().toString())
    }

    @Test
    fun codecRoundTrips() {
        val rules = listOf(
            Recurrence.Once,
            Recurrence.Daily,
            Recurrence.Weekly(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)),
            Recurrence.Monthly(15),
            Recurrence.EveryNHours(4),
            Recurrence.EveryNDays(10),
        )
        rules.forEach { assertEquals(it, RecurrenceCodec.decode(RecurrenceCodec.encode(it))) }
        assertEquals(Recurrence.Once, RecurrenceCodec.decode("garbage"))
    }
}
