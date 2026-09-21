package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNameMatchTest {

    @Test
    fun matchesExactNameAndMessageYourselfSuffix() {
        assertTrue(chatNameMatches("Thomas White", "Thomas White"))
        assertTrue(chatNameMatches(" Thomas White ", "Thomas White"))
        assertTrue(chatNameMatches("Thomas White (You)", "Thomas White"))
        assertTrue(chatNameMatches("Family (UK)", "Family (UK)"))
    }

    @Test
    fun rejectsOtherChats() {
        assertFalse(chatNameMatches("Thomas Whiteley", "Thomas White"))
        assertFalse(chatNameMatches("Thomas White (Work)", "Thomas White"))
        assertFalse(chatNameMatches("thomas white", "Thomas White"))
        assertFalse(chatNameMatches(null, "Thomas White"))
    }

    @Test
    fun caseIsIgnoredOnlyOnRequest() {
        assertTrue(chatNameMatches("thomas white (you)", "Thomas White", ignoreCase = true))
    }
}
