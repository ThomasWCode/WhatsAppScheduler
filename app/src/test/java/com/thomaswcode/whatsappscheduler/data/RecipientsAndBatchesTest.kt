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
    fun aMemberSentWhileItWasBeingEditedStopsTheSave() = runTest {
        val (m, d) = listOf(mum, dad).map { recipients.resolve(it) }
        val members = schedules.saveBatch(template(), listOf(m, d), now = now).saved
        val seen = members.associate { it.id to it.lastRunAtMillis }
        // Mum's goes out while the sheet is open.
        schedules.recordRun(members[0].id, members[0].nextAtMillis!!, SendStatus.SENT, null, "Mum", tomorrowNine.plusSeconds(20))
        val later = tomorrowNine.plus(Duration.ofHours(2))
        val stopped = runCatching { schedules.saveBatch(template("Lunch at 1?", later), listOf(m, d), members, now, seen) }.exceptionOrNull()
        assertEquals(listOf(members[0].id), (stopped as SentWhileEditing).members.map { it.id })
        // Nothing was saved: Mum's stays sent, Dad's as it was.
        assertNull(schedules.get(members[0].id)!!.nextAtMillis)
        assertEquals(tomorrowNine.toEpochMilli(), schedules.get(members[1].id)!!.nextAtMillis)
        // A failure meanwhile sent nothing, so saving goes ahead.
        schedules.recordRun(members[1].id, members[1].nextAtMillis!!, SendStatus.FAILED, "search: Timed out", "Dad", tomorrowNine.plusSeconds(20))
        val saved = schedules.saveBatch(template("Lunch at 1?", later), listOf(d), members.drop(1), now, seen).saved.single()
        assertEquals(later.toEpochMilli(), saved.nextAtMillis)
    }

    @Test
    fun aNamedChatIsOneRecipientWhateverItsKind() = runTest {
        val stored = recipients.resolve(RecipientDraft(TargetKind.CONTACT, "Sam's flat"))
        assertEquals(stored.id, recipients.resolve(RecipientDraft(TargetKind.GROUP, "sam's flat")).id)
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
    fun aChipNotLinkedYetIsFoundAsTheStoredRecipient() = runTest {
        val saved = recipients.resolve(mum)
        // The number typed again, or picked before the lookup finished: no id yet, but the same chat.
        assertEquals(saved.id, recipients.existing(RecipientDraft(TargetKind.CONTACT, "07400 123456?", phoneE164 = "+447400123456"))?.id)
        assertNull(recipients.existing(RecipientDraft(TargetKind.CONTACT, "Someone new", phoneE164 = "+447400123499")))
    }

    @Test
    fun aGroupTypedInAnotherCaseIsTheSavedOne() = runTest {
        val saved = recipients.resolve(crew)
        assertEquals(saved.id, recipients.resolve(RecipientDraft(TargetKind.GROUP, "work crew")).id)
        // Beyond ASCII too, which SQLite's NOCASE wouldn't fold.
        val equipe = recipients.resolve(RecipientDraft(TargetKind.GROUP, "Équipe", isLocked = true))
        assertEquals(equipe.id, recipients.resolve(RecipientDraft(TargetKind.GROUP, "équipe")).id)
        assertEquals(equipe.id, recipients.sameChatElsewhere(saved.copy(displayName = "ÉQUIPE"))?.id)
    }

    @Test
    fun aSeriesThatMayHaveBeenSentKeepsItsCheckPrompt() = runTest {
        val m = recipients.resolve(mum)
        val daily = schedules.saveBatch(template(), listOf(m), now = now).saved.single().let { schedules.save(it.copy(recurrence = "DAILY"), now) }
        schedules.recordRun(daily.id, daily.nextAtMillis!!, SendStatus.UNCERTAIN, "verify: Timed out", "Mum", tomorrowNine.plusSeconds(30))
        val changed = recipients.updateDetails(m.copy(phoneE164 = "+447400123459"))
        assertTrue("its prompt stays: the chat to check is the old one", changed.none { it.id == daily.id })
        assertEquals("its next sends use the new number", "+447400123459", schedules.get(daily.id)!!.phoneE164)
    }

    @Test
    fun aFailedMessageWaitingToBeSentByHandFollowsTheRecipientToo() = runTest {
        val m = recipients.resolve(mum)
        val failed = schedules.saveBatch(template(), listOf(m), now = now).saved.single()
        schedules.recordRun(failed.id, failed.nextAtMillis!!, SendStatus.FAILED, "search: Timed out", "Mum", tomorrowNine.plusSeconds(30))
        val uncertain = schedules.saveBatch(template(), listOf(m), now = now).saved.single()
        schedules.recordRun(uncertain.id, uncertain.nextAtMillis!!, SendStatus.UNCERTAIN, "verify: Timed out", "Mum", tomorrowNine.plusSeconds(30))
        val changed = recipients.updateDetails(m.copy(phoneE164 = "+447400123459"))
        assertEquals(listOf(failed.id), changed.map { it.id })
        assertEquals("+447400123459", schedules.get(failed.id)!!.phoneE164)
        // It may have gone to the old number: that is the chat to check.
        assertEquals("+447400123456", schedules.get(uncertain.id)!!.phoneE164)
    }

    @Test
    fun detailEditsReportThePendingMessagesTheyChanged() = runTest {
        val m = recipients.resolve(mum)
        val pending = schedules.saveBatch(template(), listOf(m), now = now).saved.single()
        val sent = schedules.saveBatch(template(), listOf(m), now = now).saved.single()
        schedules.recordRun(sent.id, sent.nextAtMillis!!, SendStatus.SENT, null, "Mum", tomorrowNine.plusSeconds(30))
        val changed = recipients.updateDetails(m.copy(phoneE164 = "+447400123459"))
        assertEquals(listOf(pending.id), changed.map { it.id })
        assertEquals("+447400123459", changed.single().phoneE164)
    }

    @Test
    fun oldSchedulesThatDisagreeGiveTheNewestDetails() = runTest {
        suspend fun legacy(name: String, locked: Boolean, createdAt: Instant) =
            schedules.save(template().copy(targetName = name, phoneE164 = "+447400123458", isLocked = locked, createdAtMillis = createdAt.toEpochMilli()), now)
        legacy("Sam", locked = false, createdAt = now.minus(Duration.ofDays(30)))
        legacy("Sam (work)", locked = true, createdAt = now.minus(Duration.ofDays(2)))
        recipients.linkUnlinkedSchedules()
        val recipient = recipients.observeRecent().first().single()
        assertEquals("Sam (work)", recipient.chatName)
        assertTrue(recipient.isLocked)
    }

    @Test
    fun anEditThatWouldMakeTwoIdentitiesForOneChatIsCaught() = runTest {
        val (m, d, c) = listOf(mum, dad, crew).map { recipients.resolve(it) }
        assertNull(recipients.sameChatElsewhere(m))
        // Dad's number edited to Mum's: Mum is already that chat.
        assertEquals(m.id, recipients.sameChatElsewhere(d.copy(phoneE164 = m.phoneE164))?.id)
        // A group renamed to another saved group's name.
        val club = recipients.resolve(RecipientDraft(TargetKind.GROUP, "Book club"))
        assertEquals(c.id, recipients.sameChatElsewhere(club.copy(displayName = "Work crew"))?.id)
    }

    @Test
    fun anEditKeepsARunThatFinishedWhileTheSheetWasOpen() = runTest {
        val m = recipients.resolve(mum)
        val readBeforeTheRun = schedules.saveBatch(template(), listOf(m), now = now).saved.single()
        val ranAt = tomorrowNine.plusSeconds(30)
        schedules.recordRun(readBeforeTheRun.id, readBeforeTheRun.nextAtMillis!!, SendStatus.FAILED, "search: Timed out", "Mum", ranAt)
        val later = tomorrowNine.plus(Duration.ofDays(1))
        val edited = schedules.saveBatch(template("Lunch at 1?", later), listOf(m), listOf(readBeforeTheRun), now).saved.single()
        assertEquals(SendStatus.FAILED, edited.lastStatus)
        assertEquals(ranAt.toEpochMilli(), edited.lastRunAtMillis)
        assertEquals(later.toEpochMilli(), edited.nextAtMillis)
    }

    @Test
    fun partOfABatchEditedOnItsOwnBecomesABatchOfItsOwn() = runTest {
        val (m, d, c) = listOf(mum, dad, crew).map { recipients.resolve(it) }
        val members = schedules.saveBatch(template(), listOf(m, d, c), now = now).saved
        val edited = schedules.saveBatch(template("Lunch at 1?"), listOf(m, d), members.take(2), now).saved
        assertNotNull(edited[0].batchId)
        assertEquals(edited[0].batchId, edited[1].batchId)
        assertTrue("apart from the member left out", edited[0].batchId != members[2].batchId)
        assertEquals(members[2].batchId, schedules.get(members[2].id)!!.batchId)
        // Edited as a whole, a batch keeps its id.
        val whole = schedules.saveBatch(template("Lunch at 2?"), listOf(m, d), schedules.batch(edited[0].batchId!!), now).saved
        assertEquals(edited[0].batchId, whole[0].batchId)
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

    @Test
    fun aChatFoundBehindChatLockIsMarkedWithTheRecipientsMessagesStillToGo() = runTest {
        val (m, d) = listOf(mum, dad).map { recipients.resolve(it) }
        val lunch = schedules.saveBatch(template("Lunch on Sunday?"), listOf(m), now = now).saved.single()
        val dinner = schedules.saveBatch(template("Dinner on Friday?"), listOf(m), now = now).saved.single()
        val tea = schedules.saveBatch(template("Tea?"), listOf(m), now = now).saved.single()
        schedules.recordRun(tea.id, tea.nextAtMillis!!, SendStatus.SENT, null, "Mum", tomorrowNine.plusSeconds(20))
        val toDad = schedules.saveBatch(template("Lunch on Sunday?"), listOf(d), now = now).saved.single()

        schedules.markLocked(lunch.id)

        assertTrue(recipients.get(m.id)!!.isLocked)
        assertTrue(schedules.get(lunch.id)!!.isLocked)
        assertTrue("the same chat's message still to go", schedules.get(dinner.id)!!.isLocked)
        assertFalse("a sent one keeps what it was sent with", schedules.get(tea.id)!!.isLocked)
        assertFalse("another chat", schedules.get(toDad.id)!!.isLocked)
        assertFalse(recipients.get(d.id)!!.isLocked)
    }

    @Test
    fun aMessageWithoutARecipientIsMarkedAlone() = runTest {
        // Its chat was removed from recents, so the message keeps its own copy of the details.
        val lone = schedules.save(template().copy(targetKind = TargetKind.GROUP, targetName = "Book club"), now)
        assertNull(lone.recipientId)
        schedules.markLocked(lone.id)
        assertTrue(schedules.get(lone.id)!!.isLocked)
        schedules.markLocked(999)
    }
}
