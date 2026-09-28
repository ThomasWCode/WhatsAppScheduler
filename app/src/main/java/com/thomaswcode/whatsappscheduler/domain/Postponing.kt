package com.thomaswcode.whatsappscheduler.domain

import java.time.Instant
import java.time.ZoneId

/** What moving one occurrence of a series does to the rest of it (quick-schedule.md §9). */
object Postponing {

    /**
     * The occurrences after [from] (the one being moved) and before [to] (where it goes). The series
     * resumes after the moved send, so these are skipped: "This also skips Wed 09:00". One exactly at
     * [to] is absorbed too, but the moved message goes then, so nothing is missed and it isn't listed.
     * At most [limit] are returned, so a list that long may be cut short; a one-off skips nothing.
     */
    fun skipped(rule: Recurrence, firstAt: Instant, zone: ZoneId, from: Instant, to: Instant, limit: Int = LIMIT): List<Instant> {
        if (rule == Recurrence.Once) return emptyList()
        val skipped = mutableListOf<Instant>()
        var cursor = from
        while (skipped.size < limit) {
            val next = RecurrenceCalculator.nextOccurrence(rule, firstAt, after = cursor, zone = zone) ?: break
            if (!next.isBefore(to)) break
            skipped += next
            cursor = next
        }
        return skipped
    }

    /** Enough for any move the picker offers, e.g. an hourly series moved a month; beyond it, "at least". */
    const val LIMIT = 1_000
}
