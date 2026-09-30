package com.thomaswcode.whatsappscheduler.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlainResultTest {

    @Test
    fun eachStepIsPutInWords() {
        assertEquals("Auto-send is off", PlainResult.reason("precheck: Accessibility service is not running"))
        assertEquals("WhatsApp isn't installed", PlainResult.reason("precheck: WhatsApp is not installed"))
        assertEquals("Couldn't unlock the phone", PlainResult.reason("unlockKeyguard: PIN pad not found"))
        assertEquals("Couldn't unlock the phone", PlainResult.reason("wake: Screen didn't turn on"))
        assertEquals("Couldn't find the chat 'Work crew'", PlainResult.reason("search: Timed out after 8000ms waiting for wa.searchInput", "Work crew"))
        assertEquals("Couldn't find the chat", PlainResult.reason("lockedChat: No result"))
        assertEquals("WhatsApp didn't open the chat for its number", PlainResult.reason("deepLink: Composer not shown"))
        assertEquals("Couldn't open the locked chat", PlainResult.reason("authPrompt: Prompt not dismissed"))
        assertEquals("Couldn't tap Send", PlainResult.reason("typeAndSend: Send button not found"))
        assertEquals("Sending took too long", PlainResult.reason("job: Job exceeded 90000ms"))
    }

    @Test
    fun aPrecheckThatIsAlreadyASentenceIsKept() {
        assertEquals("Android didn't let it start sending in the background", PlainResult.reason("precheck: Android didn't let it start sending in the background"))
    }

    @Test
    fun afterTheTapItMayHaveGone() {
        assertEquals("Check the chat before sending it again", PlainResult.reason("verify: Message not seen (after Send was tapped)"))
    }

    @Test
    fun aRunByHandReadsTheSame() {
        assertEquals("Couldn't tap Send", PlainResult.reason("(manual) typeAndSend: Send button not found"))
        assertEquals("Couldn't tap Send", PlainResult.fromResult("manual FAILED: typeAndSend: Send button not found"))
        assertEquals("Couldn't find the chat 'Mum'", PlainResult.fromResult("FAILED: search: No result", "Mum"))
    }

    @Test
    fun nothingPlainToSayIsNull() {
        assertNull(PlainResult.reason(null))
        assertNull(PlainResult.reason(""))
        assertNull(PlainResult.reason("(manual) "))
        assertNull(PlainResult.reason("somethingNew: whatever happened"))
        assertNull(PlainResult.fromResult("SENT"))
        assertNull(PlainResult.fromResult(null))
    }

    @Test
    fun chatLockFailuresSayWhatWasMissing() {
        assertEquals("Couldn't open Locked chats", PlainResult.reason("lockedChat: 'Locked chats' folder not visible (hidden by a secret code that is not configured?)"))
        assertEquals("Couldn't open Locked chats", PlainResult.reason("lockedChat: 'Locked chats' didn't appear for the stored secret code (is it the one set in WhatsApp?)"))
        assertEquals("Couldn't open Locked chats", PlainResult.reason("lockedChat: Locked chats folder did not open after authentication"))
        assertEquals("Chat Lock asked for its secret code, which isn't stored", PlainResult.reason("authPrompt: The locked chat asks for its Chat Lock secret code, but none is stored in Settings"))
        assertEquals("Chat Lock asked for the phone's PIN, which isn't stored", PlainResult.reason("authPrompt: Locked chat needs the PIN but none is stored"))
        assertEquals("WhatsApp didn't accept the stored secret code", PlainResult.reason("authPrompt: WhatsApp did not accept the stored secret code"))
        assertEquals("Couldn't find the chat 'Book club'", PlainResult.reason("search: No chat named 'Book club' in search results or in Locked chats", "Book club"))
    }

    @Test
    fun aChatNotFoundSaysWhatToDoAboutChatLock() {
        // The app had no way into Locked chats: no secret code stored, and no folder showing.
        assertEquals(
            "If the chat is behind Chat Lock, store the Chat Lock secret code in Settings, so the app can look in Locked chats.",
            PlainResult.nextStep("search: No chat named 'Book club' in search results (0 rows)"),
        )
        // It looked there too.
        val nowhere = "search: No chat named 'Book club' in search results or in Locked chats"
        assertEquals("Check the name is exactly as WhatsApp shows it.", PlainResult.nextStep(nowhere))
        assertEquals("Check the name is exactly as WhatsApp shows it.", PlainResult.nextStepFromResult("FAILED: $nowhere"))
        assertEquals(
            "Check the Chat Lock secret code in Settings is the one set in WhatsApp.",
            PlainResult.nextStep("lockedChat: 'Locked chats' didn't appear for the stored secret code (is it the one set in WhatsApp?)"),
        )
        assertEquals(
            "If Locked chats is hidden, store the Chat Lock secret code in Settings.",
            PlainResult.nextStep("lockedChat: 'Locked chats' folder not visible (hidden by a secret code that is not configured?)"),
        )
    }

    @Test
    fun aLockedChatThatCouldNotBeOpenedSaysWhatToStore() {
        assertEquals(
            "Store the Chat Lock secret code in Settings, then send it again.",
            PlainResult.nextStep("(manual) authPrompt: The locked chat asks for its Chat Lock secret code, but none is stored in Settings"),
        )
        assertEquals("Store the phone's PIN in Settings, then send it again.", PlainResult.nextStep("authPrompt: Locked chat needs the PIN but none is stored"))
        assertEquals("Check the Chat Lock secret code in Settings, then send it again.", PlainResult.nextStep("authPrompt: WhatsApp did not accept the stored secret code"))
    }

    @Test
    fun otherFailuresSuggestNothing() {
        assertNull(PlainResult.nextStep("search: Timed out after 8000ms waiting for wa.searchInput"))
        assertNull(PlainResult.nextStep("deepLink: Timed out after 8000ms waiting for wa.entry, wa.invalidNumber"))
        assertNull(PlainResult.nextStep("lockedChat: Locked chats folder did not open after authentication"))
        assertNull(PlainResult.nextStep("typeAndSend: Send button not found"))
        assertNull(PlainResult.nextStep("search: No chat named 'Book club' in search results (0 rows) (after Send was tapped)"))
        assertNull(PlainResult.nextStep(null))
        assertNull(PlainResult.nextStepFromResult("SENT"))
    }

    @Test
    fun headlinesSayWhatHappenedToWhom() {
        assertEquals("Sent to Mum", PlainResult.headline("SENT", "Mum"))
        assertEquals("Couldn't send to Mum", PlainResult.headline("FAILED", "Mum"))
        assertEquals("May have been sent to Mum", PlainResult.headline("UNCERTAIN", "Mum"))
        assertEquals("Test run for Mum: not sent", PlainResult.headline("DRY_RUN", "Mum"))
        assertEquals("Missed: your message to Mum", PlainResult.headline("MISSED", "Mum"))
        assertEquals("Not sent to Mum", PlainResult.headline("SKIPPED", "Mum"))
    }
}
