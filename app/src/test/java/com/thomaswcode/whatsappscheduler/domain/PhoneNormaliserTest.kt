package com.thomaswcode.whatsappscheduler.domain

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric, because the parsing is the platform's own (libphonenumber inside the framework). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PhoneNormaliserTest {

    private fun parse(input: String, country: String = "GB") = PhoneNormaliser.parse(input, country)

    @Test
    fun localNumbersAreReadInTheDefaultCountry() {
        assertEquals(PhoneParse.Valid("+447400123456"), parse("07400 123456"))
        assertEquals(PhoneParse.Valid("+447400123456"), parse("07400-123-456", "gb"))
        assertEquals(PhoneParse.Valid("+12022345678"), parse("(202) 234-5678", "US"))
    }

    @Test
    fun internationalFormsAreAccepted() {
        assertEquals(PhoneParse.Valid("+447400123456"), parse("+44 7400 123456"))
        assertEquals(PhoneParse.Valid("+447400123456"), parse("0044 7400 123456"))
        assertEquals(PhoneParse.Valid("+447400123456"), parse("0044 7400 123456", "US"))
        assertEquals(PhoneParse.Valid("+33612345678"), parse("+33 6 12 34 56 78"))
    }

    @Test
    fun fullInternationalNumbersUnknownToTheDatabaseAreKeptButFlagged() {
        // Ofcom's drama range: well-formed, but deliberately not a valid number.
        assertEquals(PhoneParse.Unverified("+447700900123"), parse("+44 7700 900123"))
        assertEquals(PhoneParse.Unverified("+447700900123"), parse("0044 7700 900123"))
    }

    @Test
    fun localNumbersThatDoNotParseAreRejected() {
        // The old code saved this as "+07700900123", which WhatsApp rejects at send time.
        assertEquals(PhoneParse.Invalid, parse("07700 900123"))
    }

    @Test
    fun anythingElseIsRejected() {
        // A country code never starts with 0, however many digits follow.
        listOf("", "   ", "12345", "+44 123", "call me", "+44 7400 123456 ext 4", "0740O 123456", "+1234567890123456", "+00000000", "0000000000").forEach {
            assertEquals(it, PhoneParse.Invalid, parse(it))
        }
    }

    @Test
    fun displayGroupsTheDigits() {
        assertEquals("+44 7400 123456", PhoneNormaliser.display("+447400123456", "GB"))
    }
}
