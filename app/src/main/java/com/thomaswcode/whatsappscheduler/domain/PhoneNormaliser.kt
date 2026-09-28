package com.thomaswcode.whatsappscheduler.domain

import android.telephony.PhoneNumberUtils

/** The outcome of reading a phone number as typed or picked. */
sealed interface PhoneParse {
    /** Valid according to the phone's number database. */
    data class Valid(val e164: String) : PhoneParse

    /**
     * Typed in full international form but unknown to the phone's number database, which can lag
     * behind newly issued ranges. Accepted, so a real contact is never blocked, with a warning.
     */
    data class Unverified(val e164: String) : PhoneParse

    data object Invalid : PhoneParse

    val e164OrNull: String? get() = when (this) {
        is Valid -> e164
        is Unverified -> e164
        Invalid -> null
    }
}

/**
 * Turns a number as typed or picked into E.164 (`+447400123456`), the form the `wa.me` link needs
 * (B06). Local numbers are read in the default country, a leading `00` is taken as the
 * international prefix, and anything that can't be a phone number is rejected, so a bad number
 * fails when the schedule is saved rather than hours later when it is sent.
 */
object PhoneNormaliser {

    private val separators = Regex("[\\s\\-.()/]")
    private val digitsOnly = Regex("\\+?\\d+")

    fun parse(input: String, defaultCountryIso: String): PhoneParse {
        val compact = input.trim().replace(separators, "")
        if (!compact.matches(digitsOnly)) return PhoneParse.Invalid
        val international = if (compact.startsWith("00")) "+" + compact.drop(2) else compact
        val valid = runCatching { PhoneNumberUtils.formatNumberToE164(international, defaultCountryIso.uppercase()) }.getOrNull()
        return when {
            valid != null -> PhoneParse.Valid(valid)
            // E.164 allows at most 15 digits; 8 is the shortest a mobile number with its country code gets.
            international.startsWith("+") && international.length - 1 in 8..15 -> PhoneParse.Unverified(international)
            else -> PhoneParse.Invalid
        }
    }

    /** E.164, or null when [input] can't be a phone number. */
    fun toE164(input: String, defaultCountryIso: String): String? = parse(input, defaultCountryIso).e164OrNull

    /** [e164] grouped for reading, e.g. "+44 7400 123456". */
    fun display(e164: String, defaultCountryIso: String): String =
        runCatching { PhoneNumberUtils.formatNumber(e164, defaultCountryIso.uppercase()) }.getOrNull() ?: e164
}
