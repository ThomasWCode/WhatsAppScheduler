package com.thomaswcode.whatsappscheduler.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure occurrence arithmetic. All wall-clock rules are evaluated in the schedule's zone so that
 * a 09:00 message stays at 09:00 across daylight-saving changes.
 */
object RecurrenceCalculator {

    /**
     * Returns the first occurrence strictly after [after] that is not before [firstAt], or null when
     * the rule has no further occurrences.
     */
    fun nextOccurrence(rule: Recurrence, firstAt: Instant, after: Instant, zone: ZoneId): Instant? {
        val floor = if (after.isBefore(firstAt)) firstAt.minusNanos(1) else after
        val firstLocal = firstAt.atZone(zone)
        val time = firstLocal.toLocalTime()
        return when (rule) {
            Recurrence.Once -> firstAt.takeIf { it.isAfter(floor) }
            Recurrence.Daily -> nextDailyLike(floor, zone, time) { true }
            is Recurrence.Weekly -> if (rule.days.isEmpty()) null else nextDailyLike(floor, zone, time) { it.dayOfWeek in rule.days }
            is Recurrence.Monthly -> nextMonthly(floor, zone, time, rule.dayOfMonth)
            is Recurrence.EveryNHours -> nextByDuration(firstAt, floor, Duration.ofHours(rule.hours.toLong()))
            is Recurrence.EveryNDays -> nextEveryNDays(firstLocal, floor, zone, rule.days)
        }
    }

    private fun nextDailyLike(floor: Instant, zone: ZoneId, time: LocalTime, accept: (LocalDate) -> Boolean): Instant? {
        var date = floor.atZone(zone).toLocalDate()
        repeat(8) {
            if (accept(date)) {
                val candidate = ZonedDateTime.of(date, time, zone).toInstant()
                if (candidate.isAfter(floor)) return candidate
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun nextMonthly(floor: Instant, zone: ZoneId, time: LocalTime, dayOfMonth: Int): Instant {
        var month = floor.atZone(zone).toLocalDate().withDayOfMonth(1)
        while (true) {
            val day = dayOfMonth.coerceAtMost(month.lengthOfMonth())
            val candidate = ZonedDateTime.of(month.withDayOfMonth(day), time, zone).toInstant()
            if (candidate.isAfter(floor)) return candidate
            month = month.plusMonths(1)
        }
    }

    private fun nextByDuration(firstAt: Instant, floor: Instant, step: Duration): Instant {
        if (firstAt.isAfter(floor)) return firstAt
        val elapsed = Duration.between(firstAt, floor)
        val steps = elapsed.toMillis() / step.toMillis() + 1
        return firstAt.plus(step.multipliedBy(steps))
    }

    private fun nextEveryNDays(firstLocal: ZonedDateTime, floor: Instant, zone: ZoneId, n: Int): Instant {
        val startDate = firstLocal.toLocalDate()
        val time = firstLocal.toLocalTime()
        val floorDate = floor.atZone(zone).toLocalDate()
        val daysElapsed = ChronoUnit.DAYS.between(startDate, floorDate).coerceAtLeast(0)
        var k = daysElapsed / n
        while (true) {
            val candidate = ZonedDateTime.of(startDate.plusDays(k * n), time, zone).toInstant()
            if (candidate.isAfter(floor)) return candidate
            k++
        }
    }
}
