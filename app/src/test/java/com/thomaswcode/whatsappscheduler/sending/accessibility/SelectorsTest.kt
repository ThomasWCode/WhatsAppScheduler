package com.thomaswcode.whatsappscheduler.sending.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the text regexes; view ids can only be verified on a device. */
class SelectorsTest {

    @Test
    fun useCredentialButtonMatchesCommonLabels() {
        val re = Selectors.authUseCredentialButton.text!!
        listOf("Use PIN", "USE PIN", "Use device PIN", "Use password", "Use screen lock", "PIN", "Enter PIN").forEach {
            assertTrue(it, re.matches(it))
        }
        listOf("Cancel", "Use fingerprint", "Unlock with face").forEach { assertFalse(it, re.matches(it)) }
    }

    @Test
    fun keypadDigitMatchesPlainAndLabelledKeys() {
        val re = Selectors.keypadDigit('7').text!!
        assertTrue(re.matches("7"))
        assertTrue(re.matches("7 PQRS"))
        assertTrue(re.matches("7, PQRS"))
        assertFalse(re.matches("17"))
        assertFalse(re.matches("70"))
        assertFalse(re.matches("PQRS 7"))
    }

    @Test
    fun searchLabelMatchesTheBarButNotMessagePreviews() {
        val re = Selectors.waSearchByLabel.text!!
        listOf("Search", "Search…", "Search...", "Ask Meta AI or Search", "Search or ask Meta AI").forEach { assertTrue(it, re.matches(it)) }
        listOf("Let me search", "Search for it later", "Searching", "Research").forEach { assertFalse(it, re.matches(it)) }
    }

    @Test
    fun wrongPinMessageIsRecognised() {
        val re = Selectors.keyguardWrongPin.text!!
        listOf("Incorrect PIN", "Wrong PIN", "Incorrect PIN entered. Try again.").forEach { assertTrue(it, re.matches(it)) }
        listOf("Enter PIN", "Your PIN contains at least 4 digits.").forEach { assertFalse(it, re.matches(it)) }
    }

    @Test
    fun keyguardEnterMatchesSubmitLabels() {
        val re = Selectors.keyguardEnterByText.text!!
        listOf("Enter", "OK", "Done", "Confirm").forEach { assertTrue(it, re.matches(it)) }
        assertFalse(re.matches("Emergency call"))
    }

    @Test
    fun lockedChatsRowIsExact() {
        val re = Selectors.waLockedChatsRow.text!!
        assertTrue(re.matches("Locked chats"))
        assertFalse(re.matches("Locked chats settings"))
    }

    @Test
    fun withTextEscapesRegexCharacters() {
        val s = Selectors.waConversationRowTitle.withText("Family (UK) [2026]")
        assertTrue(s.text!!.matches("Family (UK) [2026]"))
        assertFalse(s.text!!.matches("Family UK 2026"))
    }
}
