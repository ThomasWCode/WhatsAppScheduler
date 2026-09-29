package com.thomaswcode.whatsappscheduler.sending

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.db.Attention
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.scheduling.ScheduleAlarms
import com.thomaswcode.whatsappscheduler.util.Texts
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The buttons on "Needs your attention" notifications (features F3). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AttentionActionsTest {

    // What the actions say, read from the app's resources: the test checks the wording.
    private val texts = Texts.of(ApplicationProvider.getApplicationContext<Context>().resources)

    private lateinit var db: AppDatabase
    private lateinit var schedules: ScheduleRepository
    private lateinit var actions: AttentionActions

    /** Monday 28 September 2026, 13:00 in London. */
    private val now: Instant = Instant.parse("2026-09-28T12:00:00Z")

    /** Tuesday 09:00 in London. */
    private val tomorrowNine: Instant = Instant.parse("2026-09-29T08:00:00Z")

    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val armed = mutableListOf<Schedule>()
    private val requested = mutableListOf<SendRequest>()
    private val cancelled = mutableListOf<Long>()
    private var state = SendState()
    private val alarms = object : ScheduleAlarms {
        override fun sync(schedule: Schedule) {
            armed += schedule
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        schedules = ScheduleRepository(db)
        actions = AttentionActions(schedules, alarms, { state }, { requested += it }, { cancelled += it }, clock, texts = texts)
    }

    @After
    fun tearDown() = db.close()

    private fun message(recurrence: String = "ONCE") = Schedule(
        message = "Bins tonight", targetKind = TargetKind.CONTACT, targetName = "Mum", phoneE164 = "+447400123456", isLocked = false,
        firstAtMillis = tomorrowNine.toEpochMilli(), zoneId = "Europe/London", recurrence = recurrence, nextAtMillis = null,
    )

    /** Saved for tomorrow 09:00, and missed then. */
    private suspend fun missed(recurrence: String = "ONCE"): Schedule {
        val saved = schedules.save(message(recurrence), now)
        return schedules.recordRun(saved.id, saved.nextAtMillis!!, SendStatus.MISSED, "missed by 180 min (phone off?)", "Mum", tomorrowNine.plus(Duration.ofHours(3)))!!
    }

    @Test
    fun sendNowSendsACopyAndTheNotificationGoes() = runTest {
        val m = missed()
        assertNull(actions.sendNow(m.id))
        assertEquals(listOf(SendRequest(m.id, SendKind.EXTRA_COPY)), requested)
        assertEquals(listOf(m.id), cancelled)
    }

    @Test
    fun nothingIsDoneWhileItIsBeingSent() = runTest {
        val m = missed()
        state = SendState(currentId = m.id)
        assertEquals("It's being sent now.", actions.sendNow(m.id))
        assertEquals("It's being sent now.", actions.discard(m.id))
        assertEquals("It's being sent now.", actions.markSent(m.id))
        assertEquals("It's being sent now, so it wasn't moved.", actions.postpone(m, now.plus(Duration.ofHours(2))))
        assertTrue(requested.isEmpty() && cancelled.isEmpty() && armed.isEmpty())
        assertEquals(m, schedules.get(m.id))
    }

    @Test
    fun aDiscardWaitsForASendTakingTheRow() = runTest {
        val m = missed()
        val admission = Mutex()
        val guarded = AttentionActions(schedules, alarms, { state }, { requested += it }, { cancelled += it }, clock, admission, texts)
        admission.lock()
        val discard = async { guarded.discard(m.id) }
        runCurrent()
        assertFalse(discard.isCompleted)
        // The send went ahead with the row: the discard finds it busy and leaves it.
        state = SendState(currentId = m.id)
        admission.unlock()
        assertEquals("It's being sent now.", discard.await())
        assertNotNull(schedules.get(m.id))
    }

    @Test
    fun aMoveWaitsForASendTakingTheRow() = runTest {
        val m = missed()
        val admission = Mutex()
        val guarded = AttentionActions(schedules, alarms, { state }, { requested += it }, { cancelled += it }, clock, admission, texts)
        admission.lock()
        val move = async { guarded.postpone(m, now.plus(Duration.ofHours(2))) }
        runCurrent()
        assertFalse(move.isCompleted)
        state = SendState(currentId = m.id)
        admission.unlock()
        assertEquals("It's being sent now, so it wasn't moved.", move.await())
        assertEquals(m, schedules.get(m.id))
    }

    @Test
    fun aSendThatCantStartKeepsTheNotification() = runTest {
        val m = missed()
        val blocked = AttentionActions(schedules, alarms, { state }, { error("not allowed from the background") }, { cancelled += it }, clock, texts = texts)
        assertTrue(blocked.sendNow(m.id)!!.startsWith("Couldn't start sending"))
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun discardingAOneOffKeepsItsHistory() = runTest {
        val m = missed()
        assertNull(actions.discard(m.id))
        assertNull(schedules.get(m.id))
        assertEquals(1, schedules.observeLogs().first().size)
        assertEquals(listOf(m.id), cancelled)
    }

    @Test
    fun discardingASeriesOccurrenceKeepsTheSeries() = runTest {
        val m = missed("DAILY")
        assertEquals(Attention.MISSED, m.attention)
        assertNull(actions.discard(m.id))
        val kept = schedules.get(m.id)!!
        assertNull(kept.attention)
        assertNotNull(kept.nextAtMillis)
    }

    @Test
    fun markAsSentResolvesAMaybe() = runTest {
        val saved = schedules.save(message(), now)
        schedules.recordRun(saved.id, saved.nextAtMillis!!, SendStatus.UNCERTAIN, "send: Timed out", "Mum", tomorrowNine.plusSeconds(30))
        assertNull(actions.markSent(saved.id))
        val marked = schedules.get(saved.id)!!
        assertEquals(SendStatus.SENT, marked.lastStatus)
        assertNull(marked.attention)
        assertEquals(listOf(saved.id), cancelled)
    }

    @Test
    fun postponingAMissedOneOffArmsItAgain() = runTest {
        val m = missed()
        val later = now.plus(Duration.ofHours(2))
        assertNull(actions.postpone(m, later))
        assertEquals(later.toEpochMilli(), schedules.get(m.id)!!.nextAtMillis)
        assertNull(schedules.get(m.id)!!.attention)
        assertEquals(later.toEpochMilli(), armed.single().nextAtMillis)
        assertEquals(listOf(m.id), cancelled)
        // Too soon, or changed since the picker opened: not moved.
        assertEquals("Pick a time at least a minute from now.", actions.postpone(schedules.get(m.id)!!, now.plusSeconds(30)))
        assertEquals("It had just been sent or changed, so it wasn't moved.", actions.postpone(m, later.plus(Duration.ofHours(1))))
        assertEquals(later.toEpochMilli(), schedules.get(m.id)!!.nextAtMillis)
    }

    @Test
    fun aDeletedScheduleJustLosesItsNotification() = runTest {
        assertEquals("It has been deleted.", actions.markSent(42))
        assertEquals(listOf(42L), cancelled)
    }
}
