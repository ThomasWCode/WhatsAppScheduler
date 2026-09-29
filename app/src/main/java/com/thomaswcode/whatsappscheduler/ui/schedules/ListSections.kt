package com.thomaswcode.whatsappscheduler.ui.schedules

import com.thomaswcode.whatsappscheduler.data.LatestRuns
import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.db.Attention
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.FriendlyTime
import com.thomaswcode.whatsappscheduler.domain.PlainResult
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class Section { ATTENTION, UPCOMING, REPEATING, SENT }

/**
 * The list's sections (quick-schedule.md §7). Each entry is a batch's members in that section, or a
 * single schedule; in Needs attention always a single one. A batch can be split: the member that
 * failed needs attention while the others show as sent. A repeating schedule that needs attention shows in both places: the attention is
 * about one occurrence, and the series carries on.
 */
data class ListSections(
    val attention: List<List<Schedule>> = emptyList(),
    val upcoming: List<List<Schedule>> = emptyList(),
    val repeating: List<List<Schedule>> = emptyList(),
    val sent: List<List<Schedule>> = emptyList(),
) {
    val isEmpty: Boolean get() = attention.isEmpty() && upcoming.isEmpty() && repeating.isEmpty() && sent.isEmpty()

    companion object {
        fun of(schedules: List<Schedule>): ListSections {
            val oneOffs = schedules.filter { it.rule == Recurrence.Once && it.attention == null }
            return ListSections(
                // One entry per recipient, never a batch: Open chat, Mark as sent and Discard are each about one chat.
                attention = schedules.filter { it.attention != null }.map { listOf(it) }.sortedByDescending { it.single().shownAtMillis },
                upcoming = batches(oneOffs.filter { it.nextAtMillis != null }).sortedBy { group -> group.minOf { it.nextAtMillis ?: Long.MAX_VALUE } },
                // Running series first, soonest first; paused and finished ones after them.
                repeating = batches(schedules.filter { it.rule != Recurrence.Once })
                    .sortedWith(compareBy({ it.first().let { s -> !s.enabled || s.nextAtMillis == null } }, { it.first().nextAtMillis ?: Long.MAX_VALUE })),
                sent = batches(oneOffs.filter { it.nextAtMillis == null }) { it.shownStatus }.sortedByDescending { group -> group.maxOf { it.shownAtMillis } },
            )
        }

        /**
         * Members of one batch together, in the order they were added; a schedule without a batch alone.
         * Members with a different [outcome] get their own entry, so one row never speaks for recipients
         * whose messages ended differently.
         */
        private fun batches(schedules: List<Schedule>, outcome: (Schedule) -> Any? = { null }): List<List<Schedule>> =
            schedules.groupBy { (it.batchId ?: "#${it.id}") to outcome(it) }.values.map { group -> group.sortedBy { it.id } }

        private val Schedule.shownAtMillis: Long get() = lastRunAtMillis ?: firstAtMillis
    }
}

/** The last run's outcome; for rows from before schema v2, read from the text of [Schedule.lastResult]. */
val Schedule.shownStatus: SendStatus?
    get() = lastStatus ?: ScheduleRepository.statusFromResult(lastResult)

/**
 * The line under a row's recipients: when it goes, when it went, or why it needs you. [runs] maps a
 * schedule to its latest scheduled runs, the one behind what it waits on saying when that was due: a missed message says when it was
 * due, while a failed one says when it was tried, which differs after Send now.
 */
fun statusLine(
    section: Section,
    group: List<Schedule>,
    now: ZonedDateTime,
    runs: Map<Long, LatestRuns>,
    time: (LocalTime) -> String = { it.format(hourMinute) },
): String {
    val schedule = group.first()
    fun day(millis: Long) = FriendlyTime.dayAndTime(Instant.ofEpochMilli(millis).atZone(now.zone), now, time)
    return when (section) {
        Section.ATTENTION -> {
            // The run that left it waiting: its occurrence is when it was due, and it says when it was tried and why.
            val run = runs[schedule.id]?.behind(schedule.attention)
            val due = run?.occurrenceAtMillis ?: if (schedule.rule == Recurrence.Once) schedule.firstAtMillis else schedule.lastRunAtMillis ?: schedule.firstAtMillis
            val tried = run?.ranAtMillis ?: schedule.lastRunAtMillis ?: due
            when (schedule.attention) {
                Attention.MISSED -> "Missed · ${day(due)}"
                // Why, in words, where the step that failed is known (improvements I2): from that occurrence's
                // own run, since a later try by hand changes the last result. Rows from before runs were
                // logged by occurrence have only the last result.
                Attention.FAILED -> {
                    val why = if (run != null) PlainResult.reason(run.detail) else PlainResult.fromResult(schedule.lastResult)
                    "${why ?: "Couldn't send"} · ${day(tried)}"
                }
                Attention.UNCERTAIN -> "May have been sent · ${day(tried)}: check the chat"
                Attention.HELD -> "Not sent: choose a new time"
                null -> day(due)
            }
        }
        Section.UPCOMING -> {
            val next = schedule.nextAtMillis ?: return "Finished"
            val at = Instant.ofEpochMilli(next).atZone(now.zone)
            val relative = if (at.toLocalDate() == now.toLocalDate()) " · " + FriendlyTime.relative(at.toInstant(), now.toInstant()) else ""
            val moved = schedule.postponedFromMillis?.let { " · moved from ${day(it)}" } ?: ""
            (if (schedule.enabled) "" else "Paused · ") + day(next) + relative + moved
        }
        Section.REPEATING -> {
            val label = schedule.rule.label()
            val next = schedule.nextAtMillis
            when {
                !schedule.enabled -> "$label · paused"
                next == null -> "$label · finished"
                else -> "$label · next ${day(next)}" + (schedule.postponedFromMillis?.let { " · moved from ${day(it)}" } ?: "")
            }
        }
        Section.SENT -> {
            val ranAt = schedule.lastRunAtMillis
            when {
                ranAt == null -> "Not sent · was due ${day(schedule.firstAtMillis)}"
                schedule.shownStatus == SendStatus.DRY_RUN -> "Sent ${day(ranAt)} (dry run)"
                schedule.shownStatus == SendStatus.SENT -> "Sent ${day(ranAt)}"
                else -> "Finished ${day(ranAt)}"
            }
        }
    }
}

/**
 * The list's items in order, by the keys the list screen gives them, so a row can be found to scroll
 * to. The Sent section's rows are there only while it is open.
 */
fun ListSections.keys(sentOpen: Boolean): List<String> = buildList {
    add("health")
    if (isEmpty) add("empty")
    listOf(Section.ATTENTION to attention, Section.UPCOMING to upcoming, Section.REPEATING to repeating).forEach { (section, groups) ->
        if (groups.isNotEmpty()) {
            add("header-$section")
            groups.forEach { add("$section-${it.first().id}") }
        }
    }
    if (sent.isNotEmpty()) {
        add("header-SENT")
        if (sentOpen) sent.forEach { add("SENT-${it.first().id}") }
    }
}

/** The first section schedule [id] shows in, and its row's key there; null when it isn't in the list. */
fun ListSections.rowOf(id: Long): Pair<Section, String>? =
    listOf(Section.ATTENTION to attention, Section.UPCOMING to upcoming, Section.REPEATING to repeating, Section.SENT to sent)
        .firstNotNullOfOrNull { (section, groups) -> groups.firstOrNull { group -> group.any { it.id == id } }?.let { section to "$section-${it.first().id}" } }

/** "Mum", "Mum and Dad", or "Mum, Dad +1". */
fun groupTitle(group: List<Schedule>): String = when (group.size) {
    1 -> group[0].targetName
    2 -> "${group[0].targetName} and ${group[1].targetName}"
    else -> "${group[0].targetName}, ${group[1].targetName} +${group.size - 2}"
}

/**
 * Up to two letters for a row's avatar: "Mum" → "M", "Work crew" → "WC", "Mum (mobile)" → "MM".
 * Whole code points, so letters outside the BMP aren't split; a name with no letters gets "?".
 */
fun initials(name: String): String {
    val firsts = name.split(' ').mapNotNull { word ->
        word.codePoints().filter { Character.isLetterOrDigit(it) }.findFirst().let { if (it.isPresent) it.asInt else null }
    }.take(2)
    return if (firsts.isEmpty()) "?" else firsts.joinToString("") { String(Character.toChars(it)).uppercase() }
}

private val hourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
