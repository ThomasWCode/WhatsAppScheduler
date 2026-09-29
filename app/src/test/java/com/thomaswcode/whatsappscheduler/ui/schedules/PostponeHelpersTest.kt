package com.thomaswcode.whatsappscheduler.ui.schedules

import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.Postponing
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.RecurrenceCodec
import com.thomaswcode.whatsappscheduler.domain.TargetKind
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
    fun aSecondMoveCountsSkipsFromTheOriginalTime() {
        val daily = Schedule(
            message = "Morning!", targetKind = TargetKind.CONTACT, targetName = "Mum", phoneE164 = "+447400123456", isLocked = false,
            firstAtMillis = on(1, 9).toInstant().toEpochMilli(), zoneId = london.id, recurrence = RecurrenceCodec.encode(Recurrence.Daily),
            nextAtMillis = on(6, 9).toInstant().toEpochMilli(),
        )
        assertEquals(listOf(on(7, 9).toInstant()), skippedByMove(daily, on(7, 18).toInstant()))
        // Tuesday's moved to Thursday evening, then back to Wednesday evening: Wednesday morning is still skipped.
        val moved = daily.copy(nextAtMillis = on(8, 18).toInstant().toEpochMilli(), postponedFromMillis = on(6, 9).toInstant().toEpochMilli())
        assertEquals(listOf(on(7, 9).toInstant()), skippedByMove(moved, on(7, 18).toInstant()))
        // Back before the original time: nothing.
        assertEquals(emptyList<java.time.Instant>(), skippedByMove(moved, on(6, 8).toInstant()))
    }

    @Test
    fun skippedOccurrencesAreNamed() {
        val days = listOf(on(6, 9), on(7, 9), on(8, 9), on(9, 9)).map { it.toInstant() }
        assertEquals("Tomorrow 09:00", skippedText(days.take(1), now))
        assertEquals("Tomorrow 09:00 and Wed 7 Oct 09:00", skippedText(days.take(2), now))
        assertEquals("Tomorrow 09:00, Wed 7 Oct 09:00 and 2 more", skippedText(days, now))
        // A list as long as the limit may be cut short.
        val many = List(Postponing.LIMIT) { i -> on(6, 9).plusHours(i.toLong()).toInstant() }
        assertEquals("Tomorrow 09:00, Tomorrow 10:00 and at least ${Postponing.LIMIT - 2} more", skippedText(many, now))
    }
}
