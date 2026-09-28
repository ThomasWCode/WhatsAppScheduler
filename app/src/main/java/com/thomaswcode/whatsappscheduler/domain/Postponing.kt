package com.thomaswcode.whatsappscheduler.domain

import java.time.Instant
import java.time.ZoneId

/** What moving one occurrence of a series does to the rest of it (quick-schedule.md §9). */
object Postponing {

    /**
     * The occurrences after [from] (the one being moved) up to and including [to] (where it goes).
     * The series resumes after the moved send, so these are skipped: "This also skips Wed 09:00".
     * At most [limit] are returned; a one-off skips nothing.
     */
    fun skipped(rule: Recurrence, firstAt: Instant, zone: ZoneId, from: Instant, to: Instant, limit: Int = 10): List<Instant> {
        if (rule == Recurrence.Once) return emptyList()
        val skipped = mutableListOf<Instant>()
        var cursor = from
        while (skipped.size < limit) {
            val next = RecurrenceCalculator.nextOccurrence(rule, firstAt, after = cursor, zone = zone) ?: break
            if (next.isAfter(to)) break
            skipped += next
            cursor = next
        }
        return skipped
    }
}
