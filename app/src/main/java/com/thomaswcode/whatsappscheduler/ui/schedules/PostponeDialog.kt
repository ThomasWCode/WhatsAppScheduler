package com.thomaswcode.whatsappscheduler.ui.schedules

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.FriendlyTime
import com.thomaswcode.whatsappscheduler.domain.Postponing
import com.thomaswcode.whatsappscheduler.ui.quick.QuickScheduleViewModel
import com.thomaswcode.whatsappscheduler.ui.quick.rememberTimeFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Postpone… (quick-schedule.md §9): a date and a time for this one occurrence, starting at its
 * current send time. Times less than a minute ahead can't be chosen, and for a series the dialog
 * says which later occurrences the move absorbs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostponeDialog(schedule: Schedule, now: ZonedDateTime, onDismiss: () -> Unit, onMove: (Instant) -> Unit) {
    val context = LocalContext.current
    val timeFormat = rememberTimeFormat()
    val due = Instant.ofEpochMilli(schedule.nextAtMillis ?: schedule.firstAtMillis).atZone(now.zone)
    val start = remember(schedule.id) { postponeStart(due, now) }
    var epochDay by rememberSaveable { mutableStateOf(start.toLocalDate().toEpochDay()) }
    var minuteOfDay by rememberSaveable { mutableStateOf(start.hour * 60 + start.minute) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val at = LocalDate.ofEpochDay(epochDay).atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(now.zone)
    val sendable = QuickScheduleViewModel.isSendable(at, now)
    // Where it is due already: nothing to move.
    val unchanged = schedule.nextAtMillis == at.toInstant().toEpochMilli()
    val skipped = remember(at, schedule) {
        schedule.nextAtMillis?.let { from ->
            Postponing.skipped(schedule.rule, Instant.ofEpochMilli(schedule.firstAtMillis), ZoneId.of(schedule.zoneId), Instant.ofEpochMilli(from), at.toInstant())
        }.orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Postpone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Due ${FriendlyTime.dayAndTime(due, now, timeFormat)}", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingDate = true }) { Text(at.format(DateTimeFormatter.ofPattern("EEE d MMM"))) }
                    OutlinedButton(onClick = { pickingTime = true }) { Text(timeFormat(at.toLocalTime())) }
                }
                if (sendable) {
                    Text(
                        "Moves to ${FriendlyTime.dayAndTime(at, now, timeFormat)} · ${FriendlyTime.relative(at.toInstant(), now.toInstant())}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("Pick a time at least a minute from now.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (skipped.isNotEmpty()) {
                    Text("This also skips ${skippedText(skipped, now, timeFormat)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMove(at.toInstant()) }, enabled = sendable && !unchanged) { Text("Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingDate) {
        val today = now.toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // Past days can't be picked.
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)

                override fun isSelectableYear(year: Int): Boolean = year >= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { epochDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
    if (pickingTime) {
        val state = rememberTimePickerState(initialHour = minuteOfDay / 60, initialMinute = minuteOfDay % 60, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    minuteOfDay = state.hour * 60 + state.minute
                    pickingTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}

/**
 * Where the picker starts: the current send time, or, once that has passed (a missed message), the
 * same time of day on the first day it is still more than a minute ahead.
 */
internal fun postponeStart(due: ZonedDateTime, now: ZonedDateTime): ZonedDateTime {
    if (due.isAfter(now.plusMinutes(1))) return due
    val today = now.toLocalDate().atTime(due.toLocalTime()).atZone(now.zone)
    return if (today.isAfter(now.plusMinutes(1))) today else today.plusDays(1)
}

/**
 * "Tue 09:00", "Tue 09:00 and Wed 09:00", or "Tue 09:00, Wed 09:00 and 3 more"; "at least" when the
 * list reached [Postponing.LIMIT] and may have been cut short.
 */
internal fun skippedText(skipped: List<Instant>, now: ZonedDateTime, time: (LocalTime) -> String = { it.toString() }): String {
    fun day(at: Instant) = FriendlyTime.dayAndTime(at.atZone(now.zone), now, time)
    val atLeast = if (skipped.size >= Postponing.LIMIT) "at least " else ""
    return when (skipped.size) {
        0 -> ""
        1 -> day(skipped[0])
        2 -> "${day(skipped[0])} and ${day(skipped[1])}"
        else -> "${day(skipped[0])}, ${day(skipped[1])} and $atLeast${skipped.size - 2} more"
    }
}
