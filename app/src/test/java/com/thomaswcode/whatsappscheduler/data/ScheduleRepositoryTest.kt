package com.thomaswcode.whatsappscheduler.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
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
    fun recordingADeletedScheduleIsHarmless() = runTest {
        val saved = saveDaily()
        repository.delete(saved)
        assertNull(repository.recordRun(saved.id, nine.toEpochMilli(), SendStatus.SENT, null, "Mum", ranAt))
        assertNull(repository.recordManual(saved.id, SendStatus.SENT, null, "Mum", ranAt))
        assertEquals(emptyList<Any>(), repository.observeLogs().first())
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
