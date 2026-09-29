package com.thomaswcode.whatsappscheduler.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.db.Attention
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScheduleRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ScheduleRepository

    private val nine: Instant = Instant.parse("2026-09-29T08:00:00Z") // 09:00 in London
    private val ranAt: Instant = nine.plusSeconds(35)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = ScheduleRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun saveDaily(): Schedule = repository.save(
        Schedule(
            message = "Morning!", targetKind = TargetKind.CONTACT, targetName = "Mum", phoneE164 = "+447700900123",
            isLocked = false, firstAtMillis = nine.toEpochMilli(), zoneId = "Europe/London", recurrence = "DAILY", nextAtMillis = null,
        ),
        now = nine.minus(Duration.ofHours(1)),
    )

    @Test
    fun aMissedOccurrenceIsHeldForTheUser() = runTest {
        // A series goes on, and the missed message waits as a one-off of its own (B09).
        val daily = saveDaily()
        val missed = repository.recordMissed(daily.id, nine.toEpochMilli(), "missed by 180 min (phone off?)", "Mum", ranAt)!!
        assertNull(missed.schedule.attention)
        assertEquals(nine.plus(Duration.ofDays(1)).toEpochMilli(), repository.get(daily.id)!!.nextAtMillis)
        assertNull(repository.get(daily.id)!!.attention)
        val held = repository.get(missed.held.id)!!
        assertEquals(Attention.MISSED, held.attention)
        assertEquals(daily.id, held.sourceScheduleId)
        assertEquals(nine.toEpochMilli(), held.firstAtMillis)
        assertNull(held.nextAtMillis)
        assertEquals("ONCE", held.recurrence)
        assertEquals(daily.message, held.message)
        // Its run is the held message's, so the series' own last run and history stand.
        assertNull(repository.get(daily.id)!!.lastRunAtMillis)
        assertEquals(mapOf(held.id to nine.toEpochMilli()), repository.observeLatestOccurrences().first())
        // A one-off waits itself.
        val once = repository.save(daily.copy(id = 0, recurrence = "ONCE", firstAtMillis = nine.plus(Duration.ofDays(2)).toEpochMilli()), ranAt)
        val oneOff = repository.recordMissed(once.id, once.nextAtMillis!!, "missed by 30 min (phone off?)", "Mum", ranAt.plus(Duration.ofDays(2)))!!
        assertEquals(oneOff.schedule, oneOff.held)
        assertEquals(Attention.MISSED, oneOff.held.attention)
        assertNull(oneOff.held.nextAtMillis)
    }

    @Test
    fun theCountdownsSnoozeAndPostpone() = runTest {
        // Snooze: five minutes on, logged, the series otherwise as it was.
        val daily = saveDaily()
        val later = nine.plus(Duration.ofMinutes(5))
        val snoozed = repository.snooze(daily.id, nine.toEpochMilli(), later, nine)!!
        assertEquals(later.toEpochMilli(), snoozed.nextAtMillis)
        assertEquals(nine.toEpochMilli(), snoozed.postponedFromMillis)
        assertNull(snoozed.lastStatus)
        assertNull(repository.snooze(daily.id, nine.toEpochMilli(), later, nine))
        // Postpone… on a series: it goes on, and this occurrence waits on its own for a new time.
        val held = repository.hold(daily.id, later.toEpochMilli(), later, "held for a new time")!!
        assertNull(held.schedule.attention)
        assertEquals(nine.plus(Duration.ofDays(1)).toEpochMilli(), repository.get(daily.id)!!.nextAtMillis)
        assertEquals(Attention.HELD, held.held.attention)
        assertNull(held.held.lastStatus)
        assertNull(held.held.nextAtMillis)
        assertEquals(daily.id, held.held.sourceScheduleId)
        // A one-off waits itself.
        val once = repository.save(daily.copy(id = 0, recurrence = "ONCE", firstAtMillis = nine.plus(Duration.ofDays(2)).toEpochMilli()), ranAt)
        val heldOnce = repository.hold(once.id, once.nextAtMillis!!, nine.plus(Duration.ofDays(2)), "held for a new time")!!
        assertEquals(heldOnce.schedule, heldOnce.held)
        assertEquals(Attention.HELD, repository.get(once.id)!!.attention)
        assertNull(repository.get(once.id)!!.nextAtMillis)
    }

    @Test
    fun aBatchsHeldCopiesAreABatchOfTheirOwn() = runTest {
        val before = nine.minus(Duration.ofHours(1))
        val mum = repository.save(saveDaily().copy(batchId = "b"), before)
        val dad = repository.save(saveDaily().copy(batchId = "b", targetName = "Dad"), before)
        val heldMum = repository.hold(mum.id, nine.toEpochMilli(), ranAt, "held for a new time")!!.held
        val heldDad = repository.hold(dad.id, nine.toEpochMilli(), ranAt, "held for a new time")!!.held
        assertEquals("b@${nine.toEpochMilli()}", heldMum.batchId)
        assertEquals(heldMum.batchId, heldDad.batchId)
        // The series themselves stay in their own batch.
        assertEquals("b", repository.get(mum.id)!!.batchId)
    }

    @Test
    fun aBatchsMembersDueTogetherShareTheCountdown() = runTest {
        val daily = saveDaily()
        val a = repository.save(daily.copy(id = 0, batchId = "b"), ranAt)
        val b = repository.save(daily.copy(id = 0, batchId = "b", targetName = "Dad"), ranAt)
        assertEquals(setOf(a.id, b.id), repository.duePeers(a, a.nextAtMillis!!).map { it.id }.toSet())
        assertEquals(listOf(daily.id), repository.duePeers(daily, daily.nextAtMillis!!).map { it.id })
    }

    @Test
    fun aSendThatCouldntStartIsRecordedOnlyWhileItsStillDue() = runTest {
        val daily = saveDaily()
        repository.save(daily.copy(firstAtMillis = nine.plus(Duration.ofHours(8)).toEpochMilli()), ranAt)
        // Its alarm was for 09:00, but the schedule has since moved to 17:00.
        assertNull(repository.recordNotStarted(daily.id, nine.toEpochMilli(), "precheck: …", ranAt))
        assertNull(repository.get(daily.id)!!.lastStatus)
        val moved = repository.get(daily.id)!!
        val failed = repository.recordNotStarted(daily.id, moved.nextAtMillis!!, "precheck: …", ranAt)!!
        assertEquals(SendStatus.FAILED, failed.lastStatus)
    }

    @Test
    fun anOccurrenceEditedAwayIsntMissed() = runTest {
        val daily = saveDaily()
        // Edited to another time between the queue looking at it and holding it.
        repository.save(daily.copy(firstAtMillis = nine.plus(Duration.ofHours(8)).toEpochMilli()), ranAt)
        assertNull(repository.recordMissed(daily.id, nine.toEpochMilli(), "missed by 180 min (phone off?)", "Mum", ranAt))
        assertNull(repository.get(daily.id)!!.attention)
        assertEquals(nine.plus(Duration.ofHours(8)).toEpochMilli(), repository.get(daily.id)!!.nextAtMillis)
        assertTrue(repository.observeLogs().first().isEmpty())
    }

    @Test
    fun aMissedOccurrenceLeavesWhatTheSeriesAlreadyWaitsOn() = runTest {
        val daily = saveDaily()
        repository.recordRun(daily.id, nine.toEpochMilli(), SendStatus.FAILED, "search: Timed out", "Mum", ranAt)
        val failed = repository.get(daily.id)!!
        val tomorrow = failed.nextAtMillis!!
        repository.recordMissed(daily.id, tomorrow, "missed by 180 min (phone off?)", "Mum", Instant.ofEpochMilli(tomorrow).plus(Duration.ofHours(3)))
        val after = repository.get(daily.id)!!
        // Yesterday's failure is still described as it was, and the series has moved on.
        assertEquals(Attention.FAILED, after.attention)
        assertEquals(SendStatus.FAILED, after.lastStatus)
        assertEquals(failed.lastRunAtMillis, after.lastRunAtMillis)
        assertEquals(failed.lastResult, after.lastResult)
        assertEquals(Instant.ofEpochMilli(tomorrow).plus(Duration.ofDays(1)).toEpochMilli(), after.nextAtMillis)
        assertEquals(nine.toEpochMilli(), repository.observeLatestOccurrences().first()[daily.id])
    }

    @Test
    fun recordRunMovesPastTheOccurrenceAndLogsIt() = runTest {
        val saved = saveDaily()
        val stored = repository.recordRun(saved.id, nine.toEpochMilli(), SendStatus.SENT, null, "Mum", ranAt)!!
        assertEquals(nine.plus(Duration.ofDays(1)).toEpochMilli(), stored.nextAtMillis)
        assertEquals(stored, repository.get(saved.id))
        val log = repository.observeLogs().first().single()
        assertEquals(SendStatus.SENT, log.status)
        assertEquals(ranAt.toEpochMilli(), log.atMillis)
    }

    @Test
    fun editsMadeWhileTheSendRanAreKept() = runTest {
        val saved = saveDaily()
        // The send started with `saved`; meanwhile the message is edited and the schedule switched off.
        db.scheduleDao().update(saved.copy(message = "Edited while sending", enabled = false))
        repository.recordRun(saved.id, nine.toEpochMilli(), SendStatus.SENT, null, "Mum", ranAt)
        val row = repository.get(saved.id)!!
        assertEquals("Edited while sending", row.message)
        assertFalse(row.enabled)
        assertEquals(ranAt.toEpochMilli(), row.lastRunAtMillis)
        assertEquals("SENT", row.lastResult)
    }

    @Test
    fun anOccurrenceMovedWhileTheSendRanIsLeftWhereTheEditPutIt() = runTest {
        val saved = saveDaily()
        val tenOClock = nine.plus(Duration.ofHours(1))
        db.scheduleDao().update(saved.copy(firstAtMillis = tenOClock.toEpochMilli(), nextAtMillis = tenOClock.toEpochMilli()))
        val stored = repository.recordRun(saved.id, nine.toEpochMilli(), SendStatus.SENT, null, "Mum", ranAt)!!
        assertEquals(tenOClock.toEpochMilli(), stored.nextAtMillis)
    }

    @Test
    fun aScheduleDeletedDuringItsSendIsStillInTheHistory() = runTest {
        val saved = saveDaily()
        repository.delete(saved)
        assertNull(repository.recordRun(saved.id, nine.toEpochMilli(), SendStatus.SENT, null, "Mum", ranAt))
        assertNull(repository.recordManual(saved.id, SendStatus.SENT, null, "Mum", ranAt))
        val logs = repository.observeLogs().first()
        assertEquals(2, logs.size)
        logs.forEach {
            assertNull(it.scheduleId)
            assertEquals("Mum", it.targetName)
        }
    }

    @Test
    fun outcomesSetTheTypedStatusAndWhatNeedsAttention() = runTest {
        val saved = saveDaily()
        val failed = repository.recordRun(saved.id, nine.toEpochMilli(), SendStatus.FAILED, "search: Timed out", "Mum", ranAt)!!
        assertEquals(SendStatus.FAILED, failed.lastStatus)
        assertEquals(Attention.FAILED, failed.attention)
        assertEquals(nine.toEpochMilli(), repository.observeLogs().first().single().occurrenceAtMillis)
        // A test run changes nothing it waits on, nor the last run; a copy sent by hand resolves it.
        val tested = repository.recordManual(saved.id, SendStatus.DRY_RUN, null, "Mum", ranAt)!!
        assertEquals(Attention.FAILED, tested.attention)
        assertEquals(SendStatus.FAILED, tested.lastStatus)
        assertEquals(SendStatus.FAILED, repository.get(saved.id)!!.lastStatus)
        val resolved = repository.recordManual(saved.id, SendStatus.SENT, null, "Mum", ranAt)!!
        assertNull(resolved.attention)
        assertEquals(resolved, repository.get(saved.id))
        // The next occurrence's outcome decides again.
        val next = resolved.nextAtMillis!!
        val uncertain = repository.recordRun(saved.id, next, SendStatus.UNCERTAIN, null, "Mum", ranAt.plusSeconds(86_400))!!
        assertEquals(Attention.UNCERTAIN, uncertain.attention)
        // A scheduled dry run sends nothing, so what was waiting still is.
        val dryRun = repository.recordRun(saved.id, uncertain.nextAtMillis!!, SendStatus.DRY_RUN, null, "Mum", ranAt.plusSeconds(2 * 86_400))!!
        assertEquals(Attention.UNCERTAIN, dryRun.attention)
        assertEquals(SendStatus.DRY_RUN, dryRun.lastStatus)
    }

    @Test
    fun oneOffSentEarlyStaysSentWhenSwitchedOffAndOnAgain() = runTest {
        val saved = repository.save(
            Schedule(
                message = "Tomorrow's news", targetKind = TargetKind.CONTACT, targetName = "Mum", phoneE164 = "+447700900123",
                isLocked = false, firstAtMillis = nine.plus(Duration.ofDays(1)).toEpochMilli(), zoneId = "Europe/London",
                recurrence = "ONCE", nextAtMillis = null,
            ),
            now = nine,
        )
        val sent = repository.recordRun(saved.id, saved.nextAtMillis!!, SendStatus.SENT, null, "Mum", ranAt)!!
        assertNull(sent.nextAtMillis)
        assertEquals(ranAt.toEpochMilli(), sent.firstAtMillis)
        // What setEnabled does, on the test's clock rather than the real one.
        val later = ranAt.plusSeconds(60)
        val off = repository.save(sent.copy(enabled = false), now = later)
        val on = repository.save(off.copy(enabled = true), now = later)
        assertNull("switching it back on must not bring the old time back", on.nextAtMillis)
    }

    @Test
    fun switchingOnOrOffStartsFromTheStoredRow() = runTest {
        val saved = saveDaily()
        // The list still shows `saved`, but a send has finished since.
        repository.recordRun(saved.id, nine.toEpochMilli(), SendStatus.SENT, null, "Mum", ranAt)
        val off = repository.setEnabled(saved.id, false, now = ranAt.plusSeconds(60))!!
        assertEquals(false, off.enabled)
        assertEquals("the run is not written back over", SendStatus.SENT, off.lastStatus)
        assertEquals(ranAt.toEpochMilli(), off.lastRunAtMillis)
        assertNull(repository.setEnabled(999, true))
    }

    @Test
    fun recordManualLeavesTheNextOccurrence() = runTest {
        val saved = saveDaily()
        val stored = repository.recordManual(saved.id, SendStatus.SENT, null, "Mum", ranAt)!!
        assertEquals(saved.nextAtMillis, stored.nextAtMillis)
        assertEquals(saved.nextAtMillis, repository.get(saved.id)!!.nextAtMillis)
        assertEquals("manual SENT", stored.lastResult)
    }

    @Test
    fun unknownStatusNamesReadAsFailed() = runTest {
        val saved = saveDaily()
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO send_log (scheduleId, atMillis, status, targetName, detail) VALUES (${saved.id}, 1, 'FROM_A_NEWER_VERSION', 'Mum', NULL)"
        )
        assertEquals(SendStatus.FAILED, repository.observeLogs().first().single().status)
    }
}
