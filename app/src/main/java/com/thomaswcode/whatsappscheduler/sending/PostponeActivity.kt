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
import androidx.compose.runtime.setValue
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
 * Postpone… from a "Needs your attention" notification (features F3): the list's picker, over
 * whatever is on screen, for the one-off the notification is about. It closes once a time is chosen
 * or the picker is dismissed.
 */
class PostponeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = SchedulerApp.container(this)
        val id = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
        setContent {
            AppTheme {
                // The row as it was when the picker opened: the move is refused if it is sent or changed meanwhile.
                var seen by remember { mutableStateOf<Schedule?>(null) }
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
                LaunchedEffect(id) {
                    seen = container.repository.get(id)
                    if (seen == null) {
                        Toast.makeText(this@PostponeActivity, "It has been deleted.", Toast.LENGTH_LONG).show()
                        container.fallbackNotifier.cancel(id)
                        finish()
                    }
                }
                seen?.let { schedule ->
                    PostponeDialog(
                        schedule = schedule,
                        now = now,
                        onDismiss = ::finish,
                        onMove = { at ->
                            scope.launch {
                                // Not cancelled by the activity closing: the move and its alarm belong together.
                                val problem = withContext(NonCancellable) { container.attentionActions.postpone(schedule, at) }
                                val done = problem ?: "Moved to ${FriendlyTime.dayAndTime(at.atZone(now.zone), ZonedDateTime.now(), timeFormat)}"
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
        private const val EXTRA_SCHEDULE_ID = "scheduleId"

        fun intent(context: Context, scheduleId: Long): Intent =
            Intent(context, PostponeActivity::class.java).putExtra(EXTRA_SCHEDULE_ID, scheduleId)
    }
}
