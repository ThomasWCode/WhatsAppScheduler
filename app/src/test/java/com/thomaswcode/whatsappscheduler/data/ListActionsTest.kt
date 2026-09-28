package com.thomaswcode.whatsappscheduler.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.db.Attention
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the list's swipe, Undo, Discard and Mark as sent do to the stored rows (quick-schedule.md §7). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ListActionsTest {

    private lateinit var db: AppDatabase
    private lateinit var schedules: ScheduleRepository
    private lateinit var recipients: RecipientRepository

    private val now: Instant = Instant.parse("2026-09-28T12:00:00Z")
    private val tomorrowNine: Instant = Instant.parse("2026-09-29T08:00:00Z")
    private val ranAt: Instant = tomorrowNine.plusSeconds(20)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        schedules = ScheduleRepository(db)
        recipients = RecipientRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun oneOff(name: String = "Mum") = Schedule(
        message = "Lunch on Sunday?", targetKind = TargetKind.CONTACT, targetName = name, phoneE164 = null, isLocked = false,
        firstAtMillis = tomorrowNine.toEpochMilli(), zoneId = "Europe/London", recurrence = "ONCE", nextAtMillis = null,
    )

    private suspend fun history() = db.sendLogDao().observeRecent(100).first()

    @Test
    fun deletingTakesTheHistoryAndUndoBringsBothBack() = runTest {
        val failed = schedules.save(oneOff(), now)
        schedules.recordRun(failed.id, failed.nextAtMillis!!, SendStatus.FAILED, "search: Timed out", "Mum", ranAt)
        val other = schedules.save(oneOff("Dad"), now)
        schedules.recordRun(other.id, other.nextAtMillis!!, SendStatus.SENT, null, "Dad", ranAt)

        val removed = schedules.deleteWithHistory(listOf(failed.id))
        assertNull(schedules.get(failed.id))
        assertEquals(1, removed.logs.size)
        assertEquals("the other schedule's history stays", listOf("Dad"), history().map { it.targetName })

        val restored = schedules.restore(removed).single()
        assertEquals(failed.id, restored.id)
        assertEquals(Attention.FAILED, schedules.get(failed.id)!!.attention)
        assertEquals(setOf("Mum", "Dad"), history().map { it.targetName }.toSet())
        assertEquals(failed.id, history().single { it.targetName == "Mum" }.scheduleId)
    }

    @Test
    fun undoStillWorksWhenTheRecipientWasForgottenMeanwhile() = runTest {
        val mum = recipients.resolve(RecipientDraft(TargetKind.CONTACT, "Mum", phoneE164 = "+447400123456"))
        val saved = schedules.saveBatch(oneOff(), listOf(mum), now = now).saved.single()
        val removed = schedules.deleteWithHistory(listOf(saved.id))
        recipients.remove(mum)
        val restored = schedules.restore(removed).single()
        assertNull(restored.recipientId)
        assertEquals("+447400123456", schedules.get(saved.id)!!.phoneE164)
    }

    @Test
    fun markAsSentClearsAttentionAndKeepsWhenItRan() = runTest {
        val uncertain = schedules.save(oneOff(), now)
        schedules.recordRun(uncertain.id, uncertain.nextAtMillis!!, SendStatus.UNCERTAIN, "verify: composer not cleared", "Mum", ranAt)
        schedules.markSent(uncertain.id, now = ranAt.plusSeconds(600))
        val after = schedules.get(uncertain.id)!!
        assertNull(after.attention)
        assertEquals(SendStatus.SENT, after.lastStatus)
        assertEquals(ranAt.toEpochMilli(), after.lastRunAtMillis)
        assertEquals(listOf(SendStatus.SENT, SendStatus.UNCERTAIN), history().map { it.status })
    }

    @Test
    fun dueTimeComesFromTheLatestScheduledRunNotAManualOne() = runTest {
        val series = schedules.save(oneOff().copy(recurrence = "DAILY"), now)
        val occurrence = series.nextAtMillis!!
        schedules.recordRun(series.id, occurrence, SendStatus.MISSED, "missed by 3 h", "Mum", ranAt.plusSeconds(3 * 3600))
        schedules.recordManual(series.id, SendStatus.DRY_RUN, null, "Mum", ranAt.plusSeconds(4 * 3600))
        assertEquals(mapOf(series.id to occurrence), schedules.observeLatestOccurrences().first())
    }
}
