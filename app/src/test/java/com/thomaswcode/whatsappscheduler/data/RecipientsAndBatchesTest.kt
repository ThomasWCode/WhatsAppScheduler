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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecipientsAndBatchesTest {

    private lateinit var db: AppDatabase
    private lateinit var schedules: ScheduleRepository
    private lateinit var recipients: RecipientRepository

    private val now: Instant = Instant.parse("2026-09-28T12:00:00Z")
    private val tomorrowNine: Instant = Instant.parse("2026-09-29T08:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        schedules = ScheduleRepository(db)
        recipients = RecipientRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private val mum = RecipientDraft(TargetKind.CONTACT, "Mum", phoneE164 = "+447400123456")
    private val dad = RecipientDraft(TargetKind.CONTACT, "Dad", phoneE164 = "+447400123457")
    private val crew = RecipientDraft(TargetKind.GROUP, "Work crew")

    private fun template(message: String = "Lunch on Sunday?", at: Instant = tomorrowNine) = Schedule(
        message = message, targetKind = TargetKind.CONTACT, targetName = "", phoneE164 = null, isLocked = false,
        firstAtMillis = at.toEpochMilli(), zoneId = "Europe/London", recurrence = "ONCE", nextAtMillis = null,
    )

    @Test
    fun pickingSomeoneAgainFindsTheSameRecipient() = runTest {
        val first = recipients.resolve(mum)
        recipients.updateDetails(first.copy(isLocked = true))
        // Picked again from contacts, with the same number: what was learned (Chat Lock) is kept.
        val again = recipients.resolve(mum.copy(displayName = "Mother"))
        assertEquals(first.id, again.id)
        assertTrue(again.isLocked)
        val group = recipients.resolve(crew)
        assertEquals(group.id, recipients.resolve(RecipientDraft(TargetKind.GROUP, "Work crew")).id)
        assertEquals("Work crew", group.chatName)
    }

    @Test
    fun recentsAreTheMostRecentlyUsed() = runTest {
        val m = recipients.resolve(mum)
        val d = recipients.resolve(dad)
        val c = recipients.resolve(crew)
        assertEquals("unused recipients aren't recents", emptyList<Any>(), recipients.observeRecent().first())
        recipients.markUsed(listOf(m.id), now)
        recipients.markUsed(listOf(c.id), now.plusSeconds(60))
        recipients.markUsed(listOf(d.id), now.plusSeconds(120))
        assertEquals(listOf("Dad", "Work crew", "Mum"), recipients.observeRecent().first().map { it.displayName })
        assertEquals(1, recipients.get(m.id)!!.useCount)
        assertEquals(listOf("Work crew"), recipients.search("crew").map { it.displayName })
    }

    @Test
    fun oneMessageToThreePeopleIsThreeSchedulesInOneBatch() = runTest {
        val people = listOf(mum, dad, crew).map { recipients.resolve(it) }
        val change = schedules.saveBatch(template(), people, now = now)
        assertEquals(3, change.saved.size)
        val batchId = change.saved.first().batchId
        assertNotNull(batchId)
        assertTrue(change.saved.all { it.batchId == batchId && it.nextAtMillis == tomorrowNine.toEpochMilli() })
        assertEquals(listOf("Mum", "Dad", "Work crew"), change.saved.map { it.targetName })
        assertEquals(listOf("+447400123456", "+447400123457", null), change.saved.map { it.phoneE164 })
        assertEquals(TargetKind.GROUP, change.saved.last().targetKind)
        assertNull("a single recipient isn't a batch", schedules.saveBatch(template(), people.take(1), now = now).saved.single().batchId)
    }

    @Test
    fun editingABatchAppliesToEveryMemberAndFollowsTheRecipients() = runTest {
        val (m, d, c) = listOf(mum, dad, crew).map { recipients.resolve(it) }
        val members = schedules.saveBatch(template(), listOf(m, d), now = now).saved
        // Remove Dad, add the group, change the message and time.
        val later = tomorrowNine.plus(Duration.ofHours(2))
        val change = schedules.saveBatch(template("Lunch at 1?", later), listOf(m, c), members, now)
        assertEquals(listOf("Dad"), change.deleted.map { it.targetName })
        assertEquals(listOf("Mum", "Work crew"), change.saved.map { it.targetName })
        assertEquals(members.first().id, change.saved.first().id)
        assertTrue(change.saved.all { it.message == "Lunch at 1?" && it.nextAtMillis == later.toEpochMilli() })
        assertEquals(members.first().batchId, change.saved.last().batchId)
        assertNull(schedules.get(members.last().id))
    }

    @Test
    fun editedDetailsReachPendingSchedulesButNotSentOnes() = runTest {
        val m = recipients.resolve(mum)
        val pending = schedules.saveBatch(template(), listOf(m), now = now).saved.single()
        val sent = schedules.saveBatch(template(), listOf(m), now = now).saved.single()
        schedules.recordRun(sent.id, sent.nextAtMillis!!, SendStatus.SENT, null, "Mum", tomorrowNine.plusSeconds(30))
        recipients.updateDetails(m.copy(whatsappName = "Mum ❤️", isLocked = true))
        assertEquals("Mum ❤️", schedules.get(pending.id)!!.targetName)
        assertTrue(schedules.get(pending.id)!!.isLocked)
        assertEquals("Mum", schedules.get(sent.id)!!.targetName)
    }

    @Test
    fun savingThroughTheSheetClearsWhatItWaitedOn() = runTest {
        val m = recipients.resolve(mum)
        val failed = schedules.saveBatch(template(), listOf(m), now = now).saved.single()
        schedules.recordRun(failed.id, failed.nextAtMillis!!, SendStatus.FAILED, "search: Timed out", "Mum", tomorrowNine.plusSeconds(30))
        assertEquals(Attention.FAILED, schedules.get(failed.id)!!.attention)
        val later = tomorrowNine.plus(Duration.ofDays(1))
        val rescheduled = schedules.saveBatch(template(at = later), listOf(m), listOf(schedules.get(failed.id)!!), now).saved.single()
        assertNull(rescheduled.attention)
        assertEquals(later.toEpochMilli(), rescheduled.nextAtMillis)
    }

    @Test
    fun savingThroughTheSheetSchedulesEveryMember() = runTest {
        val (m, d) = listOf(mum, dad).map { recipients.resolve(it) }
        val paused = schedules.saveBatch(template(), listOf(m), now = now).saved.single()
        schedules.setEnabled(paused.id, false, now)
        // Adding Dad to the switched-off one-off: no member may be armed while another stays off.
        val change = schedules.saveBatch(template(), listOf(m, d), listOf(schedules.get(paused.id)!!), now)
        assertEquals(2, change.saved.size)
        assertTrue(change.saved.all { it.enabled })
    }

    @Test
    fun schedulesFromBeforeRecipientsGetLinkedOnce() = runTest {
        val legacy = schedules.save(
            template().copy(targetName = "Sam", phoneE164 = "+447400123458", isLocked = true, createdAtMillis = now.toEpochMilli()),
            now,
        )
        recipients.linkUnlinkedSchedules()
        recipients.linkUnlinkedSchedules()
        val linked = schedules.get(legacy.id)!!
        val recipient = recipients.get(linked.recipientId!!)!!
        assertTrue("Chat Lock carries over", recipient.isLocked)
        assertEquals(1, recipients.observeRecent().first().size)
    }
}
