package com.thomaswcode.whatsappscheduler.ui.schedules

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PostponeHelpersTest {

    private val london = ZoneId.of("Europe/London")

    /** Monday 5 October 2026, 17:00. */
    private val now = ZonedDateTime.of(2026, 10, 5, 17, 0, 0, 0, london)

    private fun on(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, london)

    @Test
    fun thePickerStartsAtTheSendTimeOrTheNextDayThatTimeIsAhead() {
        assertEquals(on(6, 9), postponeStart(on(6, 9), now))
        // Missed this morning: the same time tomorrow.
        assertEquals(on(6, 9), postponeStart(on(5, 9), now))
        // Missed yesterday evening: this evening, which is still ahead.
        assertEquals(on(5, 20), postponeStart(on(4, 20), now))
    }

    @Test
    fun skippedOccurrencesAreNamed() {
        val days = listOf(on(6, 9), on(7, 9), on(8, 9), on(9, 9)).map { it.toInstant() }
        assertEquals("Tomorrow 09:00", skippedText(days.take(1), now))
        assertEquals("Tomorrow 09:00 and Wed 7 Oct 09:00", skippedText(days.take(2), now))
        assertEquals("Tomorrow 09:00, Wed 7 Oct 09:00 and 2 more", skippedText(days, now))
    }
}
