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
    fun headlinesSayWhatHappenedToWhom() {
        assertEquals("Sent to Mum", PlainResult.headline("SENT", "Mum"))
        assertEquals("Couldn't send to Mum", PlainResult.headline("FAILED", "Mum"))
        assertEquals("May have been sent to Mum", PlainResult.headline("UNCERTAIN", "Mum"))
        assertEquals("Test run for Mum: not sent", PlainResult.headline("DRY_RUN", "Mum"))
        assertEquals("Missed: your message to Mum", PlainResult.headline("MISSED", "Mum"))
        assertEquals("Not sent to Mum", PlainResult.headline("SKIPPED", "Mum"))
    }
}
