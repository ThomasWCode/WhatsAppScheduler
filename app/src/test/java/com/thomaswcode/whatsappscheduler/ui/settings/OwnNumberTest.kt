package com.thomaswcode.whatsappscheduler.ui.settings

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings' own-number field: what leaving it saves. Robolectric, for the platform's number parsing. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OwnNumberTest {

    private val stored = "+447400123456"

    @Test
    fun theFieldAppearingEmptyBeforeTheNumberLoadsSavesNothing() {
        // Seen on the phone: opening Settings erased the number saved there.
        assertNull(ownNumberChange("", "GB", stored = null))
    }

    @Test
    fun theStoredNumberInAnyFormSavesNothing() {
        assertNull(ownNumberChange("+44 7400 123456", "GB", stored))
        assertNull(ownNumberChange("07400 123456", "GB", stored))
    }

    @Test
    fun aNewNumberIsSaved() {
        assertEquals(OwnNumberChange("+447400654321"), ownNumberChange("07400 654321", "GB", stored))
        assertEquals(OwnNumberChange("+447400654321"), ownNumberChange("07400 654321", "GB", stored = null))
    }

    @Test
    fun clearingTheFieldRemovesTheNumber() {
        assertEquals(OwnNumberChange(null), ownNumberChange("  ", "GB", stored))
    }

    @Test
    fun textThatIsNotANumberSavesNothing() {
        // The field says so instead, and the stored number stays.
        assertNull(ownNumberChange("12345", "GB", stored))
    }
}
