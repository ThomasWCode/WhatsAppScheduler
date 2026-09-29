package com.thomaswcode.whatsappscheduler.domain

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * A repeating rule said as a sentence (improvements I7): "Every Mon and Thu at 18:30, starting
 * Mon 5 Oct". Once it has started, it says when it goes next instead.
 */
object RuleSentence {

    /**
     * [rule] beginning at [firstAt], as it reads at [now]; null when it has no time to go (a one-off,
     * or a weekly rule without days).
     */
    fun describe(rule: Recurrence, firstAt: ZonedDateTime, now: ZonedDateTime, locale: Locale, time: (LocalTime) -> String): String? {
        val at = time(firstAt.toLocalTime())
        val what = when (rule) {
            Recurrence.Once -> return null
            Recurrence.Daily -> "Every day at $at"
            is Recurrence.Weekly -> when (rule.days.size) {
                0 -> return null
                7 -> "Every day at $at"
                else -> "Every ${list(rule.days.sortedBy(DayOfWeek::getValue).map { it.getDisplayName(TextStyle.SHORT, locale) })} at $at"
            }
            is Recurrence.Monthly -> "On the ${ordinal(rule.dayOfMonth)} of every month at $at" +
                if (rule.dayOfMonth > 28) " (the last day, in shorter months)" else ""
            is Recurrence.EveryNHours -> if (rule.hours == 1) "Every hour from $at" else "Every ${rule.hours} hours from $at"
            is Recurrence.EveryNDays -> if (rule.days == 1) "Every day at $at" else "Every ${rule.days} days at $at"
        }
        val zone = firstAt.zone
        val first = RecurrenceCalculator.nextOccurrence(rule, firstAt.toInstant(), firstAt.toInstant().minusNanos(1), zone) ?: return what
        val next = RecurrenceCalculator.nextOccurrence(rule, firstAt.toInstant(), now.toInstant(), zone) ?: return what
        // Every few hours goes more than once a day, so the next one says its time too.
        val withTime = rule is Recurrence.EveryNHours
        val nextDay = day(next.atZone(zone), now, locale) + if (withTime) " " + time(next.atZone(zone).toLocalTime()) else ""
        return if (next == first) "$what, starting $nextDay" else "$what, next $nextDay"
    }

    /** "today", "tomorrow", or "Mon 5 Oct". */
    private fun day(at: ZonedDateTime, now: ZonedDateTime, locale: Locale): String = when (at.toLocalDate()) {
        now.toLocalDate() -> "today"
        now.toLocalDate().plusDays(1) -> "tomorrow"
        else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
    }

    /** "Mon", "Mon and Thu", "Mon, Wed and Fri". */
    private fun list(items: List<String>): String =
        if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()

    /** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st, 22nd, 23rd … 31st. */
    fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
}
