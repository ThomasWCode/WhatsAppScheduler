package com.thomaswcode.whatsappscheduler.sending

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.SchedulerApp
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.FriendlyTime
import com.thomaswcode.whatsappscheduler.ui.AppTheme
import com.thomaswcode.whatsappscheduler.ui.quick.rememberTimeFormat
import com.thomaswcode.whatsappscheduler.ui.schedules.PostponeDialog
import java.time.ZonedDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Postpone… from a "Needs your attention" notification (features F3), or for what a countdown held
 * (features F1): the list's picker, over whatever is on screen, for the one-offs it is given, moved
 * together. It closes once a time is chosen or the picker is dismissed.
 */
class PostponeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = SchedulerApp.container(this)
        // A notification posted before the picker took several schedules carries one, under the old key.
        val ids = intent.getLongArrayExtra(EXTRA_SCHEDULE_IDS)?.toList()
            ?: intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L).takeIf { it > 0 }?.let { listOf(it) }.orEmpty()
        setContent {
            AppTheme {
                // The rows as they were when the picker first opened: the move is refused if one is sent or
                // changed meanwhile. Their timing is saved, so a rotation reads them again without forgetting that.
                var seen by remember { mutableStateOf<List<Schedule>?>(null) }
                var seenTiming by rememberSaveable { mutableStateOf<LongArray?>(null) }
                val scope = rememberCoroutineScope()
                val timeFormat = rememberTimeFormat()
                // Moves on each minute, so a time that has become less than a minute away can't be picked.
                val now by produceState(ZonedDateTime.now()) {
                    while (true) {
                        val tick = ZonedDateTime.now()
                        value = tick
                        delay(60_000L - tick.second * 1_000L - tick.nano / 1_000_000)
                    }
                }
                LaunchedEffect(ids) {
                    val rows = ids.mapNotNull { container.repository.get(it) }
                    // Id, next time and last run of each, as first seen.
                    val timing = seenTiming ?: rows.flatMap { listOf(it.id, it.nextAtMillis ?: -1L, it.lastRunAtMillis ?: -1L) }.toLongArray().also { seenTiming = it }
                    val first = timing.toList().chunked(3).associate { (id, next, run) -> id to (next to run) }
                    seen = rows.mapNotNull { row ->
                        first[row.id]?.let { (next, run) -> row.copy(nextAtMillis = next.takeIf { it >= 0 }, lastRunAtMillis = run.takeIf { it >= 0 }) }
                    }.ifEmpty { null }
                    if (seen == null) {
                        Toast.makeText(this@PostponeActivity, R.string.postpone_deleted, Toast.LENGTH_LONG).show()
                        ids.forEach(container.fallbackNotifier::cancel)
                        finish()
                    }
                }
                seen?.let { group ->
                    PostponeDialog(
                        schedule = group.first(),
                        now = now,
                        onDismiss = ::finish,
                        onMove = { at ->
                            scope.launch {
                                // Not cancelled by the activity closing: the move and its alarm belong together.
                                val problem = withContext(NonCancellable) { container.attentionActions.postpone(group, at) }
                                val done = problem ?: getString(R.string.moved_to, FriendlyTime.dayAndTime(at.atZone(now.zone), ZonedDateTime.now(), timeFormat))
                                Toast.makeText(this@PostponeActivity, done, Toast.LENGTH_LONG).show()
                                finish()
                            }
                        },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SCHEDULE_IDS = "scheduleIds"
        private const val EXTRA_SCHEDULE_ID = "scheduleId"

        fun intent(context: Context, scheduleId: Long): Intent = intent(context, listOf(scheduleId))

        /** One picker for all of [scheduleIds], moved together, e.g. a batch its countdown held. */
        fun intent(context: Context, scheduleIds: List<Long>): Intent =
            Intent(context, PostponeActivity::class.java).putExtra(EXTRA_SCHEDULE_IDS, scheduleIds.toLongArray())
    }
}
