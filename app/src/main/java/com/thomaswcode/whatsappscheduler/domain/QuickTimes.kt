package com.thomaswcode.whatsappscheduler.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One suggested send time in the quick sheet. */
data class TimeChip(val kind: Kind, val label: String, val at: ZonedDateTime) {
    enum class Kind { IN_30_MIN, IN_1_H, IN_3_H, THIS_MORNING, TONIGHT, TOMORROW_MORNING, NEXT_MONDAY }
}

/**
 * The quick sheet's time chips (quick-schedule.md §4): relative offsets first, then named slots.
 * Pure, so the awkward moments (just before midnight, the small hours, weekends, DST) are tested.
 */
object QuickTimes {

    val DEFAULT_MORNING: LocalTime = LocalTime.of(9, 0)
    val DEFAULT_EVENING: LocalTime = LocalTime.of(20, 0)

    /** Before this, "tomorrow morning" would be ambiguous, so this morning is offered by name. */
    private val SMALL_HOURS_END: LocalTime = LocalTime.of(5, 0)
    private val TONIGHT_LEAD: Duration = Duration.ofMinutes(30)

    private val hourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun suggest(
        now: ZonedDateTime,
        morning: LocalTime = DEFAULT_MORNING,
        evening: LocalTime = DEFAULT_EVENING,
        time: (LocalTime) -> String = { it.format(hourMinute) },
        locale: Locale = Locale.getDefault(),
    ): List<TimeChip> {
        val zone = now.zone
        val today = now.toLocalDate()
        val chips = mutableListOf(
            TimeChip(TimeChip.Kind.IN_30_MIN, "In 30 min", inMinutes(now, 30)),
            TimeChip(TimeChip.Kind.IN_1_H, "In 1 h", inMinutes(now, 60)),
            TimeChip(TimeChip.Kind.IN_3_H, "In 3 h", inMinutes(now, 180)),
        )
        val thisMorning = today.atTime(morning).atZone(zone)
        if (now.toLocalTime() < SMALL_HOURS_END && thisMorning.isAfter(now)) {
            chips += TimeChip(TimeChip.Kind.THIS_MORNING, "This morning ${time(morning)}", thisMorning)
        }
        val tonight = today.atTime(evening).atZone(zone)
        if (!now.isAfter(tonight.minus(TONIGHT_LEAD))) {
            chips += TimeChip(TimeChip.Kind.TONIGHT, "Tonight ${time(evening)}", tonight)
        }
        chips += TimeChip(TimeChip.Kind.TOMORROW_MORNING, "Tomorrow ${time(morning)}", today.plusDays(1).atTime(morning).atZone(zone))
        // On Sunday, Monday is already "tomorrow".
        if (now.dayOfWeek == DayOfWeek.FRIDAY || now.dayOfWeek == DayOfWeek.SATURDAY) {
            val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(morning).atZone(zone)
            chips += TimeChip(TimeChip.Kind.NEXT_MONDAY, "${DayOfWeek.MONDAY.getDisplayName(TextStyle.SHORT, locale)} ${time(morning)}", monday)
        }
        return chips
    }

    /**
     * [now] plus [minutes] of real time, rounded up to the next whole minute. Absolute, so "In 1 h"
     * is an hour away even across a clock change.
     */
    fun inMinutes(now: ZonedDateTime, minutes: Long): ZonedDateTime {
        val raw = now.toInstant().plus(Duration.ofMinutes(minutes))
        val whole = raw.truncatedTo(ChronoUnit.MINUTES)
        return (if (whole == raw) whole else whole.plus(1, ChronoUnit.MINUTES)).atZone(now.zone)
    }
}
