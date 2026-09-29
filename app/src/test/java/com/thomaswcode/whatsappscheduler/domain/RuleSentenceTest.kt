package com.thomaswcode.whatsappscheduler.domain

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleSentenceTest {

    private val london = ZoneId.of("Europe/London")

    // Wednesday 30 September 2026, 12:00.
    private val now: ZonedDateTime = LocalDateTime.parse("2026-09-30T12:00").atZone(london)
    private val time: (LocalTime) -> String = { it.toString() }

    private fun at(text: String): ZonedDateTime = LocalDateTime.parse(text).atZone(london)

    private fun say(rule: Recurrence, firstAt: ZonedDateTime) = RuleSentence.describe(rule, firstAt, now, Locale.UK, time)

    @Test
    fun aRuleStartingLaterSaysWhen() {
        assertEquals("Every day at 09:00, starting tomorrow", say(Recurrence.Daily, at("2026-10-01T09:00")))
        // Starts on the first chosen day on or after the date picked.
        assertEquals(
            "Every Mon and Thu at 18:30, starting Mon 5 Oct",
            say(Recurrence.Weekly(setOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY)), at("2026-10-02T18:30")),
        )
        assertEquals(
            "Every Mon, Wed and Fri at 07:15, starting Fri 2 Oct",
            say(Recurrence.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)), at("2026-10-01T07:15")),
        )
        assertEquals("Every 2 days at 20:00, starting today", say(Recurrence.EveryNDays(2), at("2026-09-30T20:00")))
    }

    @Test
    fun aRuleAlreadyGoingSaysWhenItGoesNext() {
        assertEquals("Every day at 09:00, next tomorrow", say(Recurrence.Daily, at("2026-09-01T09:00")))
        assertEquals("Every 3 hours from 09:00, next today 15:00", say(Recurrence.EveryNHours(3), at("2026-09-30T09:00")))
        assertEquals("Every hour from 09:00, next today 13:00", say(Recurrence.EveryNHours(1), at("2026-09-30T09:00")))
    }

    @Test
    fun monthlyRulesNameTheDay() {
        assertEquals("On the 5th of every month at 09:00, starting Mon 5 Oct", say(Recurrence.Monthly(5), at("2026-10-05T09:00")))
        assertEquals(
            "On the 31st of every month at 09:00 (the last day, in shorter months), starting Sat 31 Oct",
            say(Recurrence.Monthly(31), at("2026-10-31T09:00")),
        )
    }

    @Test
    fun everyDayIsSaidSo() {
        assertEquals("Every day at 09:00, starting tomorrow", say(Recurrence.Weekly(DayOfWeek.entries.toSet()), at("2026-10-01T09:00")))
        assertEquals("Every day at 09:00, starting tomorrow", say(Recurrence.EveryNDays(1), at("2026-10-01T09:00")))
    }

    @Test
    fun nothingToSayWithoutATimeToGo() {
        assertNull(say(Recurrence.Once, at("2026-10-01T09:00")))
        assertNull(say(Recurrence.Weekly(emptySet()), at("2026-10-01T09:00")))
    }

    @Test
    fun ordinalsReadAsWords() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"), listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).map(RuleSentence::ordinal))
    }
}
