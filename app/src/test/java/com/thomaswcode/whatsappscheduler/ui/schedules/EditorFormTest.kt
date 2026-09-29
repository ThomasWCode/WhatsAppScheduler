package com.thomaswcode.whatsappscheduler.ui.schedules

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.saveable.SaverScope
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.thomaswcode.whatsappscheduler.data.RecipientDraft
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for the resources: a problem names a string, and the test reads what it says.
@RunWith(RobolectricTestRunner::class)
class EditorFormTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun say(@StringRes id: Int?): String? = id?.let(context::getString)

    private val london = ZoneId.of("Europe/London")
    private val workCrew = RecipientDraft(TargetKind.GROUP, "Work crew", whatsappName = "Work crew 🛠", isLocked = true, recipientId = 7)

    @Test
    fun defaultTimeJustBeforeMidnightIsTomorrow() {
        // B23: at 23:57 the default used to be 00:02 *today*, already in the past.
        val form = EditorForm.blank(LocalDateTime.parse("2026-09-28T23:57:40").atZone(london))
        assertEquals(LocalDate.parse("2026-09-29"), form.date)
        assertEquals(LocalTime.parse("00:02"), form.time)
        assertEquals(setOf(DayOfWeek.TUESDAY), form.weekdays)
        // The full editor is for repeating messages.
        assertEquals(RuleKind.DAILY, form.ruleKind)
    }

    @Test
    fun formSurvivesBeingSavedAndRestored() {
        // B17: what the Saver writes into the SavedStateHandle comes back unchanged, the recipient's
        // details (Chat Lock, WhatsApp name, stored id) with it.
        val form = EditorForm(
            recipient = workCrew, message = "Stand-up in 5",
            date = LocalDate.parse("2026-10-05"), time = LocalTime.parse("09:30"), ruleKind = RuleKind.WEEKLY,
            weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), monthDay = "5", every = "3",
        )
        val saved = with(EditorForm.Saver) { SaverScope { true }.save(form) }!!
        assertEquals(form, EditorForm.Saver.restore(saved))
        val noOne = form.copy(recipient = null)
        assertEquals(noOne, EditorForm.Saver.restore(with(EditorForm.Saver) { SaverScope { true }.save(noOne) }!!))
    }

    @Test
    fun storedScheduleFillsTheForm() {
        val schedule = Schedule(
            message = "Bins!", targetKind = TargetKind.CONTACT, targetName = "Dad", phoneE164 = "+447400123456", isLocked = false,
            firstAtMillis = LocalDateTime.parse("2026-10-01T19:00").atZone(london).toInstant().toEpochMilli(), zoneId = london.id,
            recurrence = "WEEKLY:THURSDAY", nextAtMillis = null,
        )
        val dad = RecipientDraft(TargetKind.CONTACT, "Dad", phoneE164 = "+447400123456", recipientId = 3)
        val form = EditorForm.from(schedule, dad)
        assertEquals(dad, form.recipient)
        assertEquals("Bins!", form.message)
        assertEquals(RuleKind.WEEKLY, form.ruleKind)
        assertEquals(setOf(DayOfWeek.THURSDAY), form.weekdays)
        assertEquals(LocalTime.parse("19:00"), form.time)
        assertEquals(Recurrence.Weekly(setOf(DayOfWeek.THURSDAY)), form.rule())
    }

    @Test
    fun problemsSayWhatIsMissingByField() {
        val ok = EditorForm.blank(LocalDateTime.parse("2026-09-28T12:00").atZone(london)).copy(recipient = workCrew, message = "Hi")
        assertTrue(ok.problems().isEmpty())
        assertEquals("Choose who it's for.", say(ok.copy(recipient = null).problems()[EditorForm.Field.RECIPIENT]))
        assertEquals("Enter a message.", say(ok.copy(message = " ").problems()[EditorForm.Field.MESSAGE]))
        assertEquals("Pick at least one day.", say(ok.copy(ruleKind = RuleKind.WEEKLY, weekdays = emptySet()).problems()[EditorForm.Field.RULE]))
        assertEquals("A day from 1 to 31.", say(ok.copy(ruleKind = RuleKind.MONTHLY, monthDay = "32").problems()[EditorForm.Field.RULE]))
        assertEquals("A day from 1 to 31.", say(ok.copy(ruleKind = RuleKind.MONTHLY, monthDay = "").problems()[EditorForm.Field.RULE]))
        assertEquals("At least 1 hour.", say(ok.copy(ruleKind = RuleKind.HOURS, every = "0").problems()[EditorForm.Field.RULE]))
        assertEquals("At least 1 day.", say(ok.copy(ruleKind = RuleKind.DAYS, every = "").problems()[EditorForm.Field.RULE]))
        assertNull(ok.copy(ruleKind = RuleKind.MONTHLY, monthDay = "31").problems()[EditorForm.Field.RULE])
    }

    @Test
    fun repeatHandsOverTheSheetsRecipientMessageAndTime() {
        val at = LocalDateTime.parse("2026-10-02T18:30").atZone(london)
        val handle = SavedStateHandle(
            mapOf(
                "kind" to "GROUP", "name" to "Book club", "wa" to "Book club 📚", "rid" to 12L, "locked" to true,
                "message" to "Chapter 3 tonight", "at" to at.toInstant().toEpochMilli(),
            ),
        )
        val form = ScheduleEditorViewModel.prefillFrom(handle, london, LocalDateTime.parse("2026-09-28T12:00").atZone(london))!!
        // Both names, and the stored recipient it is, as the sheet had them.
        assertEquals(RecipientDraft(TargetKind.GROUP, "Book club", whatsappName = "Book club 📚", isLocked = true, recipientId = 12), form.recipient)
        assertEquals("Chapter 3 tonight", form.message)
        assertEquals(LocalDate.parse("2026-10-02"), form.date)
        assertEquals(LocalTime.parse("18:30"), form.time)
        assertEquals(RuleKind.DAILY, form.ruleKind)
        assertEquals(setOf(DayOfWeek.FRIDAY), form.weekdays)
        assertNull(ScheduleEditorViewModel.prefillFrom(SavedStateHandle(), london))
    }
}
