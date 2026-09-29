package com.thomaswcode.whatsappscheduler.sending.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleTextTest {

    @Test
    fun zeroWidthAndDirectionMarksAreIgnored() {
        // What WhatsApp's search box reported after being set to "Book club" (seen on One UI 8).
        assertTrue(sameVisibleText("​Book club", "Book club"))
        assertTrue(sameVisibleText("‪Hello‬", "Hello"))
        assertTrue(sameVisibleText(" Hello ", "Hello"))
        assertEquals("", visibleText("​﻿"))
        assertEquals("", visibleText(null))
    }

    @Test
    fun visibleDifferencesStillCount() {
        assertFalse(sameVisibleText("Book club", "Book  club"))
        assertFalse(sameVisibleText("book club", "Book club"))
    }

    @Test
    fun joinersAreKeptBecauseTheyChangeWhatANameIs() {
        // Technologist (one emoji, joined) and woman + laptop (two emoji) are different names.
        assertFalse(sameVisibleText("👩‍💻 Work", "👩💻 Work"))
        assertFalse(sameVisibleText("م‌ن", "من"))
    }

    @Test
    fun directionIsolatesAreIgnored() {
        assertTrue(sameVisibleText("⁨Family⁩", "Family"))
    }

    @Test
    fun invisibleCharactersAreSpelledOutForLogs() {
        assertEquals("\\u200BBook club", showInvisible("​Book club"))
    }
}
