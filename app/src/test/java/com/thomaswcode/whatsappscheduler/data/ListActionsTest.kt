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
    fun aResendThatMayHaveGoneOutAsksToBeChecked() = runTest {
        val failed = schedules.save(oneOff(), now)
        schedules.recordRun(failed.id, failed.nextAtMillis!!, SendStatus.FAILED, "search: Timed out", "Mum", ranAt)
        // Send now on it: the occurrence is used up, so it is a run by hand, and it may have gone out.
        val resent = schedules.recordManual(failed.id, SendStatus.UNCERTAIN, "verify: composer not cleared", "Mum", ranAt.plusSeconds(600))!!
        assertEquals(Attention.UNCERTAIN, resent.attention)
        assertEquals(Attention.UNCERTAIN, schedules.get(failed.id)!!.attention)
    }

    @Test
    fun outcomesFromBeforeSchemaTwoAreTypedOnce() = runTest {
        suspend fun legacy(result: String) =
            schedules.save(oneOff().copy(firstAtMillis = now.minusSeconds(86_400).toEpochMilli(), lastResult = result, lastRunAtMillis = now.minusSeconds(86_000).toEpochMilli()), now)
        val failed = legacy("FAILED: search: No chat named 'Gran'")
        val skipped = legacy("SKIPPED: missed by 180 min (phone off?)")
        val sent = legacy("SENT")
        val tested = legacy("manual DRY_RUN: composer filled, send not tapped")
        assertEquals(3, schedules.typeLegacyOutcomes())
        assertEquals(Attention.FAILED, schedules.get(failed.id)!!.attention)
        assertEquals(Attention.MISSED, schedules.get(skipped.id)!!.attention)
        assertEquals(SendStatus.SENT, schedules.get(sent.id)!!.lastStatus)
        assertNull(schedules.get(sent.id)!!.attention)
        assertNull("a run by hand never decides attention", schedules.get(tested.id)!!.lastStatus)
        // Done: Discard on it isn't undone by the next start.
        schedules.setAttention(failed.id, null)
        assertEquals(0, schedules.typeLegacyOutcomes())
        assertNull(schedules.get(failed.id)!!.attention)
    }

    @Test
    fun aTestRunDoesNotChangeWhenTheMessageWent() = runTest {
        val sent = schedules.save(oneOff(), now)
        schedules.recordRun(sent.id, sent.nextAtMillis!!, SendStatus.SENT, null, "Mum", ranAt)
        val tested = schedules.recordManual(sent.id, SendStatus.DRY_RUN, "composer filled, send not tapped", "Mum", ranAt.plusSeconds(3600))!!
        assertEquals(SendStatus.SENT, tested.lastStatus)
        assertEquals(ranAt.toEpochMilli(), schedules.get(sent.id)!!.lastRunAtMillis)
        assertEquals("manual DRY_RUN: composer filled, send not tapped", schedules.get(sent.id)!!.lastResult)
        // A copy sent by hand is a real send, so it is the last run now.
        val copy = schedules.recordManual(sent.id, SendStatus.SENT, null, "Mum", ranAt.plusSeconds(7200))!!
        assertEquals(ranAt.plusSeconds(7200).toEpochMilli(), copy.lastRunAtMillis)
    }

    @Test
    fun dueTimeComesFromTheLatestScheduledRunNotAManualOne() = runTest {
        val series = schedules.save(oneOff().copy(recurrence = "DAILY"), now)
        val occurrence = series.nextAtMillis!!
        schedules.recordRun(series.id, occurrence, SendStatus.MISSED, "missed by 3 h", "Mum", ranAt.plusSeconds(3 * 3600))
        schedules.recordManual(series.id, SendStatus.DRY_RUN, null, "Mum", ranAt.plusSeconds(4 * 3600))
        assertEquals(mapOf(series.id to occurrence), schedules.observeLatestOccurrences().first().mapValues { it.value.behind(Attention.MISSED)?.occurrenceAtMillis })
    }
}
