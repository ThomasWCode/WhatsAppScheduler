package com.thomaswcode.whatsappscheduler.ui.quick

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.domain.FriendlyTime
import com.thomaswcode.whatsappscheduler.domain.TimeChip
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The phone's own 12/24-hour choice, for chip labels and pickers (I1). */
@Composable
fun rememberTimeFormat(): (LocalTime) -> String {
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    return remember(is24) { timeFormat(is24) }
}

/** A time of day as "20:00", or as "8:00 pm" when [is24Hour] is false. */
fun timeFormat(is24Hour: Boolean): (LocalTime) -> String {
    val format = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a")
    return { it.format(format) }
}

/**
 * The When row (quick-schedule.md §4): suggested times, then "Pick date & time…". A picked time
 * that isn't one of the chips shows as its own selected chip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeChips(chips: List<TimeChip>, chosen: TimeChoice?, now: ZonedDateTime, onChoose: (TimeChoice) -> Unit, enabled: Boolean = true) {
    val context = LocalContext.current
    val timeFormat = rememberTimeFormat()
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickedDate by rememberSaveable { mutableStateOf<Long?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip ->
            FilterChip(selected = chosen == TimeChoice.Chip(chip.kind), onClick = { onChoose(TimeChoice.Chip(chip.kind)) }, label = { Text(chip.label) }, enabled = enabled)
        }
        val exact = chosen as? TimeChoice.Exact
        if (exact != null) {
            val at = Instant.ofEpochMilli(exact.atMillis).atZone(now.zone)
            FilterChip(selected = true, onClick = { pickingDate = true }, label = { Text(FriendlyTime.dayAndTime(at, now, timeFormat)) }, enabled = enabled)
        }
        FilterChip(selected = false, onClick = { pickingDate = true }, label = { Text(stringResource(R.string.quick_pick_date_time)) }, enabled = enabled)
    }

    // Opens at the chosen time, or an hour from now when nothing is chosen yet.
    val start: ZonedDateTime = when (chosen) {
        is TimeChoice.Exact -> Instant.ofEpochMilli(chosen.atMillis).atZone(now.zone)
        is TimeChoice.Chip -> chips.firstOrNull { it.kind == chosen.kind }?.at ?: now.plusHours(1)
        null -> now.plusHours(1)
    }
    if (pickingDate) {
        val today = now.toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
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
                    pickedDate = state.selectedDateMillis
                    pickingDate = false
                }) { Text(stringResource(R.string.action_next)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }
    pickedDate?.let { dateMillis ->
        val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val state = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            confirmButton = {
                TextButton(onClick = {
                    val at = date.atTime(state.hour, state.minute).atZone(now.zone)
                    onChoose(TimeChoice.Exact(at.toInstant().toEpochMilli()))
                    pickedDate = null
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = state) },
        )
    }
}

/** The line under the chips: "Tue 29 Sep · 09:00 · in 18 h 52 min". */
fun resolvedLine(at: ZonedDateTime, now: ZonedDateTime, timeFormat: (LocalTime) -> String): String {
    val day = if (at.toLocalDate() == now.toLocalDate()) "Today" else at.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    return "$day · ${timeFormat(at.toLocalTime())} · ${FriendlyTime.relative(at.toInstant(), now.toInstant())}"
}
