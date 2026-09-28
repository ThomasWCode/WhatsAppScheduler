package com.thomaswcode.whatsappscheduler.ui.schedules

import androidx.compose.runtime.saveable.SaverScope
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorFormTest {

    private val london = ZoneId.of("Europe/London")

    @Test
    fun defaultTimeJustBeforeMidnightIsTomorrow() {
        // B23: at 23:57 the default used to be 00:02 *today*, already in the past.
        val form = EditorForm.blank(LocalDateTime.parse("2026-09-28T23:57:40").atZone(london))
        assertEquals(LocalDate.parse("2026-09-29"), form.date)
        assertEquals(LocalTime.parse("00:02"), form.time)
        assertEquals(setOf(DayOfWeek.TUESDAY), form.weekdays)
    }

    @Test
    fun formSurvivesBeingSavedAndRestored() {
        // B17: what the Saver writes into the SavedStateHandle comes back unchanged.
        val form = EditorForm(
            kind = TargetKind.GROUP, name = "Work crew", phone = "", locked = true, message = "Stand-up in 5",
            date = LocalDate.parse("2026-10-05"), time = LocalTime.parse("09:30"), ruleKind = RuleKind.WEEKLY,
            weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), monthDay = "5", every = "3", pickedName = "Work",
            lockedTouched = true,
        )
        val saved = with(EditorForm.Saver) { SaverScope { true }.save(form) }!!
        assertEquals(form, EditorForm.Saver.restore(saved))
    }

    @Test
    fun chatLockComesFromTheChatUnlessSwitchedHere() {
        val lockedChat = Recipient(id = 7, kind = TargetKind.CONTACT, displayName = "Sam", phoneE164 = "+447400123458", isLocked = true)
        val form = EditorForm.blank(ZonedDateTime.of(2026, 9, 28, 12, 0, 0, 0, london)).copy(name = "Sam", phone = "+447400123458")
        val elsewhere = Schedule(
            message = "Hi", targetKind = TargetKind.CONTACT, targetName = "Dad", phoneE164 = "+447400123457", isLocked = false,
            firstAtMillis = 0L, zoneId = london.id, recurrence = "DAILY", nextAtMillis = null, recipientId = 3,
        )
        // New, or moved to a chat already known to be locked: the untouched switch doesn't unlock it.
        assertTrue(ScheduleEditorViewModel.lockFor(form, lockedChat, existing = null))
        assertTrue(ScheduleEditorViewModel.lockFor(form, lockedChat, elsewhere))
        // Still the schedule's own chat: the schedule's own setting stands.
        assertFalse(ScheduleEditorViewModel.lockFor(form, lockedChat, elsewhere.copy(recipientId = 7)))
        // Switched here: that decides.
        assertFalse(ScheduleEditorViewModel.lockFor(form.copy(lockedTouched = true), lockedChat, null))
    }

    @Test
    fun storedScheduleFillsTheForm() {
        val schedule = Schedule(
            message = "Bins!", targetKind = TargetKind.CONTACT, targetName = "Dad", phoneE164 = "+447400123456", isLocked = false,
            firstAtMillis = LocalDateTime.parse("2026-10-01T19:00").atZone(london).toInstant().toEpochMilli(), zoneId = london.id,
            recurrence = "WEEKLY:THURSDAY", nextAtMillis = null,
        )
        val form = EditorForm.from(schedule)
        assertEquals(RuleKind.WEEKLY, form.ruleKind)
        assertEquals(setOf(DayOfWeek.THURSDAY), form.weekdays)
        assertEquals(LocalTime.parse("19:00"), form.time)
        assertEquals(Recurrence.Weekly(setOf(DayOfWeek.THURSDAY)), form.rule())
    }

    @Test
    fun validationExplainsWhatIsMissing() {
        val now = Instant.parse("2026-09-28T12:00:00Z")
        val ok = EditorForm.blank(now.atZone(london)).copy(name = "Mum", message = "Hi")
        val at = now.plusSeconds(3600)
        assertNull(ScheduleEditorViewModel.validate(ok, null, Recurrence.Once, at, now))
        assertEquals("Enter a message.", ScheduleEditorViewModel.validate(ok.copy(message = " "), null, Recurrence.Once, at, now))
        assertEquals(
            "A one-off message must be in the future.",
            ScheduleEditorViewModel.validate(ok, null, Recurrence.Once, now.minusSeconds(1), now),
        )
        val badPhone = ok.copy(phone = "07700 900123")
        assertEquals(true, ScheduleEditorViewModel.validate(badPhone, null, Recurrence.Once, at, now)?.startsWith("That phone number isn't valid"))
        assertEquals("Pick at least one weekday.", ScheduleEditorViewModel.validate(ok, null, Recurrence.Weekly(emptySet()), at, now))
    }
}
