package com.thomaswcode.whatsappscheduler.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** PIN entries by this app that did not unlock the phone, stored next to the PIN (B04). */
data class PinFailures(
    /** When the phone last rejected the stored PIN. Cleared by saving the PIN again. */
    val rejectedAtMillis: Long? = null,
    /** Rejections since the last PIN entry that worked, newest last. Saving the PIN again keeps them. */
    val recentMillis: List<Long> = emptyList(),
)

/** Why the app must not type the stored PIN right now. */
sealed interface PinBlock {
    /** The phone rejected the stored PIN; nothing is typed until it is saved again. */
    data class Rejected(val atMillis: Long) : PinBlock

    /** The PIN has been saved again, but the app already had its share of failures today. */
    data class DailyLimit(val untilMillis: Long) : PinBlock

    /** In 12-hour time when [is24Hour] is false, as the phone shows times. */
    fun describe(zone: ZoneId = ZoneId.systemDefault(), is24Hour: Boolean = true): String = when (this) {
        is Rejected -> "Automatic unlocking is paused: the phone did not accept the stored PIN. Update it in Settings."
        is DailyLimit -> "Automatic unlocking is paused until " +
            Instant.ofEpochMilli(untilMillis).atZone(zone).format(if (is24Hour) untilFormat else untilFormat12) +
            ": the stored PIN failed ${PinCircuitBreaker.MAX_REJECTIONS_PER_DAY} times in 24 hours."
    }
}

private val untilFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
private val untilFormat12: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a")

/**
 * Keeps a wrong stored PIN from being typed again and again. One UI lengthens the lockout after
 * five failed attempts and, with *Auto factory reset* on, wipes the phone after fifteen, so one
 * rejection stops all PIN entry until the PIN is saved again, and the app never makes more than
 * [MAX_REJECTIONS_PER_DAY] failed entries in 24 hours even if it is re-saved in between.
 */
object PinCircuitBreaker {

    const val MAX_REJECTIONS_PER_DAY = 2
    private val window = Duration.ofHours(24).toMillis()

    fun block(state: PinFailures, nowMillis: Long): PinBlock? {
        state.rejectedAtMillis?.let { return PinBlock.Rejected(it) }
        val recent = recent(state, nowMillis)
        if (recent.size >= MAX_REJECTIONS_PER_DAY) {
            return PinBlock.DailyLimit(recent[recent.size - MAX_REJECTIONS_PER_DAY] + window)
        }
        return null
    }

    fun afterRejection(state: PinFailures, nowMillis: Long) =
        PinFailures(rejectedAtMillis = nowMillis, recentMillis = recent(state, nowMillis) + nowMillis)

    /** Saving the PIN clears the rejection but not the day's count. */
    fun afterPinSaved(state: PinFailures, nowMillis: Long) = PinFailures(recentMillis = recent(state, nowMillis))

    /** An entry that unlocked the phone resets the phone's own failure counter, so the count starts over. */
    fun afterAccepted(state: PinFailures) = state.copy(recentMillis = emptyList())

    private fun recent(state: PinFailures, nowMillis: Long) = state.recentMillis.filter { nowMillis - it < window }.sorted()
}
