package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerClearedTest {

    @Test
    fun emptyOrHintShowingComposerIsCleared() {
        assertTrue(composerCleared(null, showingHint = false, message = "See you at 6"))
        assertTrue(composerCleared("", showingHint = false, message = "See you at 6"))
        assertTrue(composerCleared("Message", showingHint = true, message = "See you at 6"))
    }

    @Test
    fun messageStartingWithMessageIsNotMistakenForTheHint() {
        assertFalse(composerCleared("Message me when you land", showingHint = false, message = "Message me when you land"))
    }

    @Test
    fun hintInAnotherLanguageCountsAsCleared() {
        listOf("Mensaje", "Nachricht", "Message", "Сообщение").forEach {
            assertTrue(it, composerCleared(it, showingHint = false, message = "See you at 6"))
        }
    }

    @Test
    fun messageStillInTheComposerIsNotCleared() {
        assertFalse(composerCleared("See you at 6", showingHint = false, message = "See you at 6"))
        assertFalse(composerCleared("See you at 6 ", showingHint = false, message = "See you at 6"))
    }

    @Test
    fun invisibleCharactersDoNotMakeAnUnsentMessageLookCleared() {
        assertFalse(composerCleared("​See you at 6", showingHint = false, message = "See you at 6"))
        assertFalse(composerCleared("See you at 6‎", showingHint = false, message = "See you at 6"))
        assertTrue(composerCleared("​", showingHint = false, message = "See you at 6"))
    }

    @Test
    fun messageThatIsTheHintWordIsOnlyClearedWhenTheFieldSaysSo() {
        assertFalse(composerCleared("Message", showingHint = false, message = "Message"))
        assertTrue(composerCleared("Message", showingHint = true, message = "Message"))
    }
}
