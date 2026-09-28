package com.thomaswcode.whatsappscheduler.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.db.Attention
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendLog
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Postpone (quick-schedule.md §9) and the housekeeping that clears Sent and old history (§8, B28). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostponeAndHousekeepingTest {

    private lateinit var db: AppDatabase
    private lateinit var schedules: ScheduleRepository

    /** Monday 28 September 2026, 13:00 in London. */
    private val now: Instant = Instant.parse("2026-09-28T12:00:00Z")

    /** Tuesday 09:00 in London. */
    private val tomorrowNine: Instant = Instant.parse("2026-09-29T08:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        schedules = ScheduleRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun message(at: Instant = tomorrowNine, recurrence: String = "ONCE") = Schedule(
        message = "Bins tonight", targetKind = TargetKind.CONTACT, targetName = "Mum", phoneE164 = null, isLocked = false,
        firstAtMillis = at.toEpochMilli(), zoneId = "Europe/London", recurrence = recurrence, nextAtMillis = null,
    )

    @Test
    fun aOneOffMovesAndRemembersWhenItWasFirstDue() = runTest {
        val saved = schedules.save(message(), now)
        val evening = tomorrowNine.plus(Duration.ofHours(9))
        val moved = schedules.postpone(saved.id, evening)!!
        assertEquals(saved, moved.before)
        assertEquals(evening.toEpochMilli(), moved.after.nextAtMillis)
        assertEquals(evening.toEpochMilli(), moved.after.firstAtMillis)
        assertEquals(tomorrowNine.toEpochMilli(), moved.after.postponedFromMillis)

        // Moved again, it is still "moved from" the time it was first due; Undo goes back one step.
        val again = schedules.postpone(saved.id, evening.plus(Duration.ofDays(1)))!!
        assertEquals(tomorrowNine.toEpochMilli(), again.after.postponedFromMillis)
        assertEquals(moved.after, schedules.restoreTiming(again.before))
        assertEquals(moved.after, schedules.get(saved.id))
    }

    @Test
    fun aMissedOneOffIsScheduledAgain() = runTest {
        val saved = schedules.save(message(), now)
        val ranAt = tomorrowNine.plus(Duration.ofHours(3))
        schedules.recordRun(saved.id, saved.nextAtMillis!!, SendStatus.MISSED, "missed by 180 min (phone off?)", "Mum", ranAt)
        schedules.setEnabled(saved.id, false, ranAt)
        val later = ranAt.plus(Duration.ofHours(2))
        val moved = schedules.postpone(saved.id, later)!!.after
        assertNull(moved.attention)
        assertTrue(moved.enabled)
        assertEquals(later.toEpochMilli(), moved.nextAtMillis)
    }

    @Test
    fun aSeriesMovesOnlyThisOccurrenceAndResumesAfterIt() = runTest {
        val daily = schedules.save(message(recurrence = "DAILY"), now)
        val wednesdayEvening = tomorrowNine.plus(Duration.ofDays(1)).plus(Duration.ofHours(9))
        val moved = schedules.postpone(daily.id, wednesdayEvening)!!.after
        assertEquals("the rule is untouched", daily.firstAtMillis, moved.firstAtMillis)
        assertEquals(wednesdayEvening.toEpochMilli(), moved.nextAtMillis)

        // Sent at its new time: Wednesday 09:00, which it passed, is skipped and the series resumes on Thursday.
        val ran = schedules.recordRun(daily.id, moved.nextAtMillis!!, SendStatus.SENT, null, "Mum", wednesdayEvening.plusSeconds(20))!!
        assertEquals(tomorrowNine.plus(Duration.ofDays(2)).toEpochMilli(), ran.nextAtMillis)
        assertNull("no longer moved", ran.postponedFromMillis)
        assertNull(schedules.get(daily.id)!!.postponedFromMillis)
    }

    @Test
    fun aSeriesMovedEarlierIsNotSentAgainAtItsOldTime() = runTest {
        val daily = schedules.save(message(recurrence = "DAILY"), now)
        val tonight = now.plus(Duration.ofHours(6))
        val moved = schedules.postpone(daily.id, tonight)!!.after
        val ran = schedules.recordRun(daily.id, moved.nextAtMillis!!, SendStatus.SENT, null, "Mum", tonight.plusSeconds(20))!!
        // Tuesday 09:00 was this message, sent early: the next one is Wednesday's.
        assertEquals(tomorrowNine.plus(Duration.ofDays(1)).toEpochMilli(), ran.nextAtMillis)
    }

    @Test
    fun whatWasSentOrChangedSinceIsNotMoved() = runTest {
        val seen = schedules.save(message(), now)
        schedules.recordRun(seen.id, seen.nextAtMillis!!, SendStatus.SENT, null, "Mum", tomorrowNine.plusSeconds(20))
        assertNull(schedules.postpone(seen.id, tomorrowNine.plus(Duration.ofDays(1)), seen = seen))
        assertNull("the sent one-off stays sent", schedules.get(seen.id)!!.nextAtMillis)
        // As seen: moved.
        val fresh = schedules.save(message(), now)
        assertNotNull(schedules.postpone(fresh.id, tomorrowNine.plus(Duration.ofDays(1)), seen = fresh))
    }

    @Test
    fun aSeriesKeepsWhatItWaitsOnWhenItsNextSendMoves() = runTest {
        val daily = schedules.save(message(recurrence = "DAILY"), now)
        val failed = schedules.recordRun(daily.id, daily.nextAtMillis!!, SendStatus.FAILED, "search: Timed out", "Mum", tomorrowNine.plusSeconds(20))!!
        val moved = schedules.postpone(daily.id, Instant.ofEpochMilli(failed.nextAtMillis!!).plus(Duration.ofHours(3)), seen = failed)!!.after
        assertEquals("the earlier occurrence still needs attention", Attention.FAILED, moved.attention)
    }

    @Test
    fun savingReplacesAPostponement() = runTest {
        val daily = schedules.save(message(recurrence = "DAILY"), now)
        schedules.postpone(daily.id, tomorrowNine.plus(Duration.ofHours(3)))
        val resaved = schedules.setEnabled(daily.id, true, now)!!
        assertEquals(tomorrowNine.toEpochMilli(), resaved.nextAtMillis)
        assertNull(resaved.postponedFromMillis)
    }

    @Test
    fun housekeepingClearsOldSentMessagesButKeepsTheirHistory() = runTest {
        suspend fun sent(ranAt: Instant, status: SendStatus = SendStatus.SENT): Schedule {
            val saved = schedules.save(message(), now)
            return schedules.recordRun(saved.id, saved.nextAtMillis!!, status, null, "Mum", ranAt)!!
        }
        val old = sent(tomorrowNine)
        val oldDryRun = sent(tomorrowNine, SendStatus.DRY_RUN)
        val failed = sent(tomorrowNine, SendStatus.FAILED)
        val recent = sent(tomorrowNine.plus(Duration.ofDays(20)))
        val pending = schedules.save(message(at = tomorrowNine.plus(Duration.ofDays(40))), now)
        // From before schema v2: only the result text says it was sent.
        val legacy = schedules.save(message(at = now.minus(Duration.ofDays(40))).copy(lastResult = "manual DRY_RUN: composer filled", lastRunAtMillis = now.minus(Duration.ofDays(40)).toEpochMilli()), now)
        db.sendLogDao().insert(SendLog(scheduleId = null, atMillis = now.minus(Duration.ofDays(100)).toEpochMilli(), status = SendStatus.SENT, targetName = "Dad", detail = null))

        val housekeeping = Housekeeping(schedules, { 30 }, AutomationLog(ApplicationProvider.getApplicationContext()))
        housekeeping.run(now = tomorrowNine.plus(Duration.ofDays(31)))

        listOf(old, oldDryRun, legacy).forEach { assertNull("sent over 30 days ago", schedules.get(it.id)) }
        listOf(failed, recent, pending).forEach { assertNotNull(schedules.get(it.id)) }
        assertEquals(Attention.FAILED, schedules.get(failed.id)!!.attention)
        val history = schedules.observeLogs().first()
        assertEquals("the cleared ones' history stays, unlinked", 2, history.count { it.scheduleId == null })
        assertTrue("history older than 90 days goes", history.none { it.targetName == "Dad" })
    }

    @Test
    fun keepingSentMessagesAlwaysClearsNothing() = runTest {
        val saved = schedules.save(message(), now)
        schedules.recordRun(saved.id, saved.nextAtMillis!!, SendStatus.SENT, null, "Mum", tomorrowNine)
        Housekeeping(schedules, { 0 }, AutomationLog(ApplicationProvider.getApplicationContext())).run(now = tomorrowNine.plus(Duration.ofDays(365)))
        assertNotNull(schedules.get(saved.id))
    }
}
