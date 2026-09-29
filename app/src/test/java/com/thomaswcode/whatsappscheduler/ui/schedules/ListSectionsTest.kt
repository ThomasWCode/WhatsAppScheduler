package com.thomaswcode.whatsappscheduler.ui.schedules

import com.thomaswcode.whatsappscheduler.data.db.Attention
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListSectionsTest {

    private val london = ZoneId.of("Europe/London")

    /** Monday 28 September 2026, 17:00. */
    private val now = ZonedDateTime.of(2026, 9, 28, 17, 0, 0, 0, london)

    private fun ms(text: String) = LocalDateTime.parse(text).atZone(london).toInstant().toEpochMilli()

    /** A pending one-off by default; [next] null makes it finished, first due yesterday at 09:00. */
    private fun schedule(id: Long, name: String = "Mum", next: String? = "2026-09-28T20:00", recurrence: String = "ONCE", batch: String? = null) = Schedule(
        id = id, message = "Hi", targetKind = TargetKind.CONTACT, targetName = name, phoneE164 = null, isLocked = false,
        firstAtMillis = next?.let(::ms) ?: ms("2026-09-27T09:00"), zoneId = london.id, recurrence = recurrence,
        nextAtMillis = next?.let(::ms), batchId = batch,
    )

    @Test
    fun eachScheduleGoesWhereItBelongs() {
        val later = schedule(1, "Dad", next = "2026-09-29T09:00")
        val soon = schedule(2, "Mum")
        val series = schedule(3, "Work crew", next = "2026-10-05T09:00", recurrence = "WEEKLY:MONDAY")
        val paused = schedule(4, "Gym", next = "2026-09-29T07:00", recurrence = "DAILY").copy(enabled = false)
        val sent = schedule(5, "Sam", next = null).copy(lastRunAtMillis = ms("2026-09-27T09:00"), lastStatus = SendStatus.SENT)
        val failed = schedule(6, "Gran", next = null).copy(lastRunAtMillis = ms("2026-09-28T09:00"), attention = Attention.FAILED)
        val missedOccurrence = series.copy(id = 7, targetName = "Book club", lastRunAtMillis = ms("2026-09-28T12:00"), attention = Attention.MISSED)

        val sections = ListSections.of(listOf(later, soon, series, paused, sent, failed, missedOccurrence))

        assertEquals("most recent first", listOf(7L, 6L), sections.attention.map { it.single().id })
        assertEquals("soonest first", listOf(2L, 1L), sections.upcoming.map { it.single().id })
        // The series with a missed occurrence carries on, so it is also under Repeating; paused ones last.
        assertEquals(listOf(3L, 7L, 4L), sections.repeating.map { it.single().id })
        assertEquals(listOf(5L), sections.sent.map { it.single().id })
    }

    @Test
    fun aBatchIsOneEntryPerSectionItHasMembersIn() {
        val mum = schedule(1, "Mum", batch = "b")
        val dad = schedule(2, "Dad", batch = "b")
        assertEquals(listOf(listOf(1L, 2L)), ListSections.of(listOf(dad, mum)).upcoming.map { group -> group.map { it.id } })

        // Mum's send failed and Dad's went: each shows where it belongs, not hidden behind the other.
        val ran = ms("2026-09-28T09:00")
        val split = ListSections.of(
            listOf(
                mum.copy(nextAtMillis = null, lastRunAtMillis = ran, attention = Attention.FAILED),
                dad.copy(nextAtMillis = null, lastRunAtMillis = ran, lastStatus = SendStatus.SENT),
            ),
        )
        assertEquals(listOf(1L), split.attention.single().map { it.id })
        assertEquals(listOf(2L), split.sent.single().map { it.id })

        // Both need attention: an entry each, so checking one chat can't mark the other as sent.
        val both = ListSections.of(
            listOf(
                mum.copy(nextAtMillis = null, lastRunAtMillis = ran, attention = Attention.FAILED),
                dad.copy(nextAtMillis = null, lastRunAtMillis = ran, attention = Attention.UNCERTAIN),
            ),
        )
        assertEquals(listOf(listOf(1L), listOf(2L)), both.attention.map { group -> group.map { it.id } })
        val bothUncertain = ListSections.of(
            listOf(
                mum.copy(nextAtMillis = null, lastRunAtMillis = ran, attention = Attention.UNCERTAIN),
                dad.copy(nextAtMillis = null, lastRunAtMillis = ran, attention = Attention.UNCERTAIN),
            ),
        )
        assertEquals(listOf(listOf(1L), listOf(2L)), bothUncertain.attention.map { group -> group.map { it.id } })
        assertTrue(ListSections.of(emptyList()).isEmpty)
    }

    @Test
    fun statusLinesSayWhenInPlainWords() {
        val dueAt = mapOf(6L to ms("2026-09-28T09:00"))
        fun line(section: Section, s: Schedule) = statusLine(section, listOf(s), now, dueAt)

        assertEquals("Today 20:00 · in 3 h", line(Section.UPCOMING, schedule(1)))
        assertEquals("Tomorrow 09:00", line(Section.UPCOMING, schedule(1, next = "2026-09-29T09:00")))
        assertEquals("Paused · Tomorrow 09:00", line(Section.UPCOMING, schedule(1, next = "2026-09-29T09:00").copy(enabled = false)))
        assertEquals(
            "Tomorrow 09:00 · moved from Today 08:00",
            line(Section.UPCOMING, schedule(1, next = "2026-09-29T09:00").copy(postponedFromMillis = ms("2026-09-28T08:00"))),
        )

        val weekly = schedule(3, next = "2026-10-05T09:00", recurrence = "WEEKLY:MONDAY")
        assertEquals("Weekly on Mon · next Mon 5 Oct 09:00", line(Section.REPEATING, weekly))
        assertEquals("Weekly on Mon · paused", line(Section.REPEATING, weekly.copy(enabled = false)))

        // Missed: when it was due, by its latest run's occurrence, although that was noticed later.
        val missed = schedule(6, next = null).copy(lastRunAtMillis = ms("2026-09-28T11:30"), attention = Attention.MISSED)
        assertEquals("Missed · Today 09:00", line(Section.ATTENTION, missed))
        assertEquals("Missed · Yesterday 09:00", line(Section.ATTENTION, schedule(8, next = null).copy(attention = Attention.MISSED)))
        // Failed or uncertain: when it was tried, which after Send now is earlier than it was due.
        val failed = schedule(6, next = null).copy(lastRunAtMillis = ms("2026-09-28T08:40"), attention = Attention.FAILED)
        assertEquals("Couldn't send · Today 08:40", line(Section.ATTENTION, failed))
        assertEquals("May have been sent · Today 08:40: check the chat", line(Section.ATTENTION, failed.copy(attention = Attention.UNCERTAIN)))

        val sent = schedule(5, next = null).copy(lastRunAtMillis = ms("2026-09-27T09:00"), lastStatus = SendStatus.SENT)
        assertEquals("Sent Yesterday 09:00", line(Section.SENT, sent))
        assertEquals("Sent Yesterday 09:00 (dry run)", line(Section.SENT, sent.copy(lastStatus = SendStatus.DRY_RUN)))
        // From before schema v2: only the result text says it was sent.
        assertEquals("Sent Yesterday 09:00", line(Section.SENT, sent.copy(lastStatus = null, lastResult = "SENT: verified")))
        assertEquals("Sent Yesterday 09:00 (dry run)", line(Section.SENT, sent.copy(lastStatus = null, lastResult = "manual DRY_RUN: composer filled")))
        assertEquals("Not sent · was due Yesterday 09:00", line(Section.SENT, schedule(9, next = null)))
    }

    @Test
    fun initialsTakeTheFirstLetterOfUpToTwoWords() {
        assertEquals("M", initials("Mum"))
        assertEquals("WC", initials("Work crew"))
        assertEquals("MM", initials("Mum (mobile)"))
        assertEquals("ÉL", initials("élodie lefèvre"))
        assertEquals("?", initials("🎉"))
    }
}
