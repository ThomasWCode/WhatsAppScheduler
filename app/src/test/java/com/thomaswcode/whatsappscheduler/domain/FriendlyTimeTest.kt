package com.thomaswcode.whatsappscheduler.domain

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FriendlyTimeTest {

    private val london = ZoneId.of("Europe/London")
    private val now: ZonedDateTime = LocalDateTime.parse("2026-09-28T14:08:30").atZone(london)

    private fun day(text: String) = FriendlyTime.dayAndTime(LocalDateTime.parse(text).atZone(london), now, locale = Locale.UK)

    @Test
    fun namesNearbyDays() {
        assertEquals("Today 20:00", day("2026-09-28T20:00"))
        assertEquals("Tomorrow 09:00", day("2026-09-29T09:00"))
        assertEquals("Yesterday 09:00", day("2026-09-27T09:00"))
        assertEquals("Sat 3 Oct 18:30", day("2026-10-03T18:30"))
        assertEquals("Mon 4 Jan 2027 09:00", day("2027-01-04T09:00"))
    }

    @Test
    fun describesHowFarAway() {
        val base = now.toInstant()
        assertEquals("in 18 h 52 min", FriendlyTime.relative(base.plus(Duration.ofMinutes(18 * 60 + 52)), base))
        assertEquals("in 3 h", FriendlyTime.relative(base.plus(Duration.ofHours(3)), base))
        assertEquals("in 5 min", FriendlyTime.relative(base.plus(Duration.ofSeconds(4 * 60 + 1)), base))
        assertEquals("in under a minute", FriendlyTime.relative(base.plusSeconds(30), base))
        assertEquals("in 1 day", FriendlyTime.relative(base.plus(Duration.ofHours(30)), base))
        assertEquals("in 3 days", FriendlyTime.relative(base.plus(Duration.ofDays(3)), base))
        assertEquals("5 min ago", FriendlyTime.relative(base.minus(Duration.ofMinutes(5)), base))
        assertEquals("just now", FriendlyTime.relative(base.minusSeconds(10), base))
    }
}
