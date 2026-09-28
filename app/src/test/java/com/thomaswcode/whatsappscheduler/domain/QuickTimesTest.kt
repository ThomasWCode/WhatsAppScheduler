package com.thomaswcode.whatsappscheduler.domain

import com.thomaswcode.whatsappscheduler.domain.TimeChip.Kind
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTimesTest {

    private val london = ZoneId.of("Europe/London")

    private fun at(text: String): ZonedDateTime = LocalDateTime.parse(text).atZone(london)

    private fun suggest(now: String) = QuickTimes.suggest(at(now), locale = Locale.UK)

    private fun List<TimeChip>.times() = associate { it.kind to it.at.toLocalDateTime().toString() }

    @Test
    fun weekdayMiddayOffersTonightAndTomorrow() {
        val chips = suggest("2026-09-29T12:00") // Tuesday
        assertEquals(
            mapOf(
                Kind.IN_30_MIN to "2026-09-29T12:30", Kind.IN_1_H to "2026-09-29T13:00", Kind.IN_3_H to "2026-09-29T15:00",
                Kind.TONIGHT to "2026-09-29T20:00", Kind.TOMORROW_MORNING to "2026-09-30T09:00",
            ),
            chips.times(),
        )
        assertEquals(listOf("In 30 min", "In 1 h", "In 3 h", "Tonight 20:00", "Tomorrow 09:00"), chips.map { it.label })
    }

    @Test
    fun justBeforeMidnightOffsetsRollIntoTomorrow() {
        val chips = suggest("2026-09-28T23:50") // Monday
        assertEquals(
            mapOf(
                Kind.IN_30_MIN to "2026-09-29T00:20", Kind.IN_1_H to "2026-09-29T00:50", Kind.IN_3_H to "2026-09-29T02:50",
                Kind.TOMORROW_MORNING to "2026-09-29T09:00",
            ),
            chips.times(),
        )
    }

    @Test
    fun smallHoursOfferThisMorningByName() {
        // At 00:30 "tomorrow morning" could mean either morning, so this one is named.
        val chips = suggest("2026-09-29T00:30").times()
        assertEquals("2026-09-29T09:00", chips[Kind.THIS_MORNING])
        assertEquals("2026-09-29T20:00", chips[Kind.TONIGHT])
        assertEquals("2026-09-30T09:00", chips[Kind.TOMORROW_MORNING])
        assertEquals(null, suggest("2026-09-29T05:00").times()[Kind.THIS_MORNING])
    }

    @Test
    fun fridayAndSaturdayOfferMondayButSundayDoesNot() {
        assertEquals("2026-10-05T09:00", suggest("2026-10-02T21:00").times()[Kind.NEXT_MONDAY]) // Friday
        assertEquals("2026-10-05T09:00", suggest("2026-10-03T10:00").times()[Kind.NEXT_MONDAY]) // Saturday
        assertEquals(null, suggest("2026-10-04T10:00").times()[Kind.NEXT_MONDAY]) // Sunday: tomorrow is Monday
        assertEquals("Mon 09:00", suggest("2026-10-02T21:00").single { it.kind == Kind.NEXT_MONDAY }.label)
    }

    @Test
    fun tonightNeedsHalfAnHoursNotice() {
        assertEquals("2026-09-29T20:00", suggest("2026-09-29T19:30").times()[Kind.TONIGHT])
        assertEquals(null, suggest("2026-09-29T19:31").times()[Kind.TONIGHT])
    }

    @Test
    fun offsetsRoundUpToTheNextWholeMinute() {
        val chips = QuickTimes.suggest(at("2026-09-29T10:00:05"), locale = Locale.UK).times()
        assertEquals("2026-09-29T10:31", chips[Kind.IN_30_MIN])
        assertEquals("2026-09-29T10:30", QuickTimes.suggest(at("2026-09-29T10:00"), locale = Locale.UK).times()[Kind.IN_30_MIN])
    }

    @Test
    fun offsetsAreRealTimeAcrossTheClocksGoingForward() {
        // 29 March 2026: 01:00 GMT becomes 02:00 BST. Thirty minutes after 00:45 is 02:15 on the clock.
        val chips = suggest("2026-03-29T00:45")
        val in30 = chips.single { it.kind == Kind.IN_30_MIN }.at
        assertEquals("2026-03-29T02:15", in30.toLocalDateTime().toString())
        assertEquals(Duration.ofMinutes(30), Duration.between(at("2026-03-29T00:45"), in30))
        assertEquals("2026-03-29T09:00", chips.times()[Kind.THIS_MORNING])
    }

    @Test
    fun alwaysFutureAndNeverMoreThanSeven() {
        var now = at("2026-10-01T00:00")
        repeat(7 * 48) {
            val chips = QuickTimes.suggest(now, locale = Locale.UK)
            assertTrue("$now: ${chips.size} chips", chips.size <= 7)
            chips.forEach { assertTrue("$now ${it.kind} ${it.at}", it.at.isAfter(now)) }
            now = now.plusMinutes(30)
        }
    }

    @Test
    fun customMorningAndEveningTimes() {
        val chips = QuickTimes.suggest(at("2026-09-29T12:00"), morning = java.time.LocalTime.of(7, 30), evening = java.time.LocalTime.of(18, 0), locale = Locale.UK)
        assertEquals("Tonight 18:00", chips.single { it.kind == Kind.TONIGHT }.label)
        assertEquals("2026-09-30T07:30", chips.times()[Kind.TOMORROW_MORNING])
    }
}
