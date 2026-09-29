package com.thomaswcode.whatsappscheduler.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Times as people say them (I1): "Today 20:00", "Tomorrow 09:00", "Sat 3 Oct 18:30", and how far
 * away they are, "in 18 h 52 min". Pure; the caller passes the 12/24-hour time format.
 */
object FriendlyTime {

    private val hourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun dayAndTime(
        at: ZonedDateTime,
        now: ZonedDateTime,
        time: (LocalTime) -> String = { it.format(hourMinute) },
        locale: Locale = Locale.getDefault(),
    ): String {
        val local = at.withZoneSameInstant(now.zone)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), local.toLocalDate())
        val day = when (days) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            else -> local.format(DateTimeFormatter.ofPattern(if (local.year == now.year) "EEE d MMM" else "EEE d MMM yyyy", locale))
        }
        return "$day ${time(local.toLocalTime())}"
    }

    /** "in 18 h 52 min", "in 5 min", "in 3 days", or "5 min ago" for the past. Future minutes round up. */
    fun relative(at: Instant, now: Instant): String {
        val seconds = Duration.between(now, at).seconds
        if (seconds in -59..59) return if (seconds >= 0) "in under a minute" else "just now"
        val future = seconds > 0
        val minutes = if (future) (seconds + 59) / 60 else -seconds / 60
        val text = when {
            minutes < 60 -> "$minutes min"
            minutes < 24 * 60 -> "${minutes / 60} h" + (minutes % 60).let { if (it > 0) " $it min" else "" }
            minutes / (24 * 60) == 1L -> "1 day"
            else -> "${minutes / (24 * 60)} days"
        }
        return if (future) "in $text" else "$text ago"
    }
}
