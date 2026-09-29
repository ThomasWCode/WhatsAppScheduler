package com.thomaswcode.whatsappscheduler.ui.quick

import androidx.compose.runtime.saveable.SaverScope
import com.thomaswcode.whatsappscheduler.data.RecipientDraft
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.domain.TimeChip
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickDraftTest {

    private val dad = Recipient(id = 1, kind = TargetKind.CONTACT, displayName = "Dad", phoneE164 = "+447400123456")
    private val crew = Recipient(id = 2, kind = TargetKind.GROUP, displayName = "Work crew")
    private val self = Recipient(id = 3, kind = TargetKind.CONTACT, displayName = "Sam", phoneE164 = "+447400654321", isLocked = true)

    private fun roundTrip(draft: QuickDraft): QuickDraft? =
        QuickDraft.Saver.restore(with(QuickDraft.Saver) { SaverScope { true }.save(draft) }!!)

    @Test
    fun draftSurvivesBeingSavedAndRestored() {
        // B17: the sheet's draft comes back unchanged after rotation or process death.
        val draft = QuickDraft(
            recipients = listOf(
                RecipientDraft(TargetKind.CONTACT, "Sam", whatsappName = "Sam (work)", phoneE164 = "+447400654321", isLocked = true, recipientId = 3),
                RecipientDraft(TargetKind.GROUP, "Work crew"),
            ),
            message = "Stand-up moved to 10",
            time = TimeChoice.Chip(TimeChip.Kind.entries.first()),
            editingIds = listOf(7L, 8L),
            editingRuns = listOf(-1L, 1_790_000_000_000L),
        )
        assertEquals(draft, roundTrip(draft))
        assertEquals(mapOf(7L to null, 8L to 1_790_000_000_000L), draft.lastRunsSeen())
        val exact = QuickDraft(message = "Hi", time = TimeChoice.Exact(1_790_000_000_000L))
        assertEquals(exact, roundTrip(exact))
        assertEquals(QuickDraft(), roundTrip(QuickDraft()))
        // A check offer is kept the same way.
        val offers = draft.recipients
        assertEquals(offers, RecipientsSaver.restore(with(RecipientsSaver) { SaverScope { true }.save(offers) }!!))
    }

    @Test
    fun onlyTimesAtLeastAMinuteAheadCanBeScheduled() {
        val now = ZonedDateTime.of(2026, 9, 28, 17, 0, 30, 0, ZoneId.of("Europe/London"))
        // A time picked earlier today: the button stays off rather than failing on tap.
        assertFalse(QuickScheduleViewModel.isSendable(now.minusMinutes(5), now))
        assertFalse(QuickScheduleViewModel.isSendable(now.plusSeconds(59), now))
        assertTrue(QuickScheduleViewModel.isSendable(now.plusSeconds(60), now))
    }

    @Test
    fun sameChatIsTheSameRecipientNumberOrNamedChat() {
        assertTrue(dad.toDraft().sameChatAs(RecipientDraft(TargetKind.CONTACT, "Father", recipientId = 1)))
        // A contact picked from search is the saved recipient with that number.
        assertTrue(RecipientDraft(TargetKind.CONTACT, "Dad (mobile)", phoneE164 = "+447400123456").sameChatAs(dad.toDraft()))
        assertTrue(RecipientDraft(TargetKind.GROUP, "Work crew").sameChatAs(crew.toDraft()))
        // A contact without a number is found by name too: the same name is the same chat.
        assertTrue(RecipientDraft(TargetKind.CONTACT, "Work crew").sameChatAs(crew.toDraft()))
        // Typed in another case: WhatsApp's search would still open the same group.
        assertTrue(RecipientDraft(TargetKind.GROUP, " work Crew").sameChatAs(crew.toDraft()))
        // By number and by name are different ways to reach a chat, so they are not assumed equal.
        assertFalse(RecipientDraft(TargetKind.CONTACT, "Dad").sameChatAs(dad.toDraft()))
    }

    @Test
    fun chatsOfferedACheckAreNamed() {
        val club = RecipientDraft(TargetKind.GROUP, "Book club")
        val work = RecipientDraft(TargetKind.GROUP, "Work crew")
        val quiz = RecipientDraft(TargetKind.GROUP, "Quiz night")
        val chess = RecipientDraft(TargetKind.GROUP, "Chess")
        assertEquals("“Book club”", QuickScheduleViewModel.quoted(listOf(club)))
        assertEquals("“Book club” and “Work crew”", QuickScheduleViewModel.quoted(listOf(club, work)))
        assertEquals("“Book club”, “Work crew” and 2 more", QuickScheduleViewModel.quoted(listOf(club, work, quiz, chess)))
    }

    @Test
    fun aTypedNumberIsANumberNotAChatName() {
        assertTrue(QuickScheduleViewModel.looksLikeNumber("07400 123456"))
        assertTrue(QuickScheduleViewModel.looksLikeNumber("+44 (0)7400-123456"))
        assertFalse(QuickScheduleViewModel.looksLikeNumber("Work crew"))
        assertFalse(QuickScheduleViewModel.looksLikeNumber("Flat 42"))
        assertFalse(QuickScheduleViewModel.looksLikeNumber("2024 reunion"))
    }

    @Test
    fun aChatChosenTwiceIsKeptOnce() {
        // Details edited so that Sam now has Dad's number: the message must not go to that chat twice.
        val chosen = listOf(dad.toDraft(), RecipientDraft(TargetKind.CONTACT, "Sam", phoneE164 = "+447400123456", recipientId = 9), crew.toDraft())
        assertEquals(listOf(dad.toDraft(), crew.toDraft()), chosen.distinctChats())
    }

    @Test
    fun recentsLeaveOutWhoIsChosenAndTheChatMeStandsFor() {
        val recents = listOf(dad, crew, self)
        assertEquals(recents, availableRecents(recents, chosen = emptyList(), me = null))

        val dadFromContacts = RecipientDraft(TargetKind.CONTACT, "Dad", phoneE164 = "+447400123456")
        val me = RecipientDraft(TargetKind.CONTACT, "Me", phoneE164 = "+447400654321", isLocked = true, recipientId = 3)
        assertEquals(listOf(crew), availableRecents(recents, chosen = listOf(dadFromContacts), me = me))

        // Me set before anything was sent to it: matched by number alone.
        val newMe = RecipientDraft(TargetKind.CONTACT, "Me", phoneE164 = "+447400654321")
        assertEquals(listOf(dad, crew), availableRecents(recents, chosen = emptyList(), me = newMe))
    }
}
