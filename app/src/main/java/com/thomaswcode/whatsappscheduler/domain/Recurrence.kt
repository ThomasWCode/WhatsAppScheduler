package com.thomaswcode.whatsappscheduler.domain

import java.time.DayOfWeek

/** Repetition rule for a schedule. Serialised to a compact string for storage by [RecurrenceCodec]. */
sealed class Recurrence {
    data object Once : Recurrence()
    data object Daily : Recurrence()
    data class Weekly(val days: Set<DayOfWeek>) : Recurrence()
    data class Monthly(val dayOfMonth: Int) : Recurrence()
    data class EveryNHours(val hours: Int) : Recurrence()
    data class EveryNDays(val days: Int) : Recurrence()

    /** Human-readable label for lists. */
    fun label(): String = when (this) {
        Once -> "Once"
        Daily -> "Daily"
        is Weekly -> "Weekly on " + days.sortedBy { it.value }.joinToString(", ") { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
        is Monthly -> "Monthly on day $dayOfMonth"
        is EveryNHours -> "Every $hours h"
        is EveryNDays -> "Every $days days"
    }
}

object RecurrenceCodec {

    /** Encodes a rule as e.g. `WEEKLY:MONDAY,FRIDAY` or `MONTHLY:15`. */
    fun encode(rule: Recurrence): String = when (rule) {
        Recurrence.Once -> "ONCE"
        Recurrence.Daily -> "DAILY"
        is Recurrence.Weekly -> "WEEKLY:" + rule.days.sortedBy { it.value }.joinToString(",") { it.name }
        is Recurrence.Monthly -> "MONTHLY:${rule.dayOfMonth}"
        is Recurrence.EveryNHours -> "HOURS:${rule.hours}"
        is Recurrence.EveryNDays -> "DAYS:${rule.days}"
    }

    /** Decodes a string produced by [encode]; unknown input falls back to [Recurrence.Once]. */
    fun decode(text: String): Recurrence {
        val head = text.substringBefore(':')
        val tail = text.substringAfter(':', "")
        return when (head) {
            "DAILY" -> Recurrence.Daily
            "WEEKLY" -> Recurrence.Weekly(
                tail.split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }.toSet()
            )
            "MONTHLY" -> tail.toIntOrNull()?.let { Recurrence.Monthly(it.coerceIn(1, 31)) } ?: Recurrence.Once
            "HOURS" -> tail.toIntOrNull()?.let { Recurrence.EveryNHours(it.coerceAtLeast(1)) } ?: Recurrence.Once
            "DAYS" -> tail.toIntOrNull()?.let { Recurrence.EveryNDays(it.coerceAtLeast(1)) } ?: Recurrence.Once
            else -> Recurrence.Once
        }
    }
}
