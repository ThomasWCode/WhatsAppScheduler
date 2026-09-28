package com.thomaswcode.whatsappscheduler.sending.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleTextTest {

    @Test
    fun zeroWidthAndDirectionMarksAreIgnored() {
        // What WhatsApp's search box reported after being set to "Matlock fun" (seen on One UI 8).
        assertTrue(sameVisibleText("​Matlock fun", "Matlock fun"))
        assertTrue(sameVisibleText("‪Hello‬", "Hello"))
        assertTrue(sameVisibleText(" Hello ", "Hello"))
        assertEquals("", visibleText("​﻿"))
        assertEquals("", visibleText(null))
    }

    @Test
    fun visibleDifferencesStillCount() {
        assertFalse(sameVisibleText("Matlock fun", "Matlock  fun"))
        assertFalse(sameVisibleText("matlock fun", "Matlock fun"))
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
        assertEquals("\\u200BMatlock fun", showInvisible("​Matlock fun"))
    }
}
