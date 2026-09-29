package com.thomaswcode.whatsappscheduler.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaswcode.whatsappscheduler.SchedulerApp
import com.thomaswcode.whatsappscheduler.ui.AppTheme
import com.thomaswcode.whatsappscheduler.ui.MainActivity
import com.thomaswcode.whatsappscheduler.ui.Routes
import com.thomaswcode.whatsappscheduler.ui.quick.QuickEvent
import com.thomaswcode.whatsappscheduler.ui.quick.QuickScheduleSheet
import com.thomaswcode.whatsappscheduler.ui.quick.QuickScheduleViewModel

/**
 * "Schedule on WhatsApp" in Android's share sheet (quick-schedule.md §2). Shows the quick sheet over
 * the app the text was shared from, with the message filled in, then returns to that app.
 */
class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = SchedulerApp.container(this)
        // The app may be opened only this way after being force-stopped, so this recovers alarms too.
        if (savedInstanceState == null) container.recoverOnOpen()
        val shared = sharedText(intent)
        setContent {
            AppTheme {
                val vm: QuickScheduleViewModel = viewModel(factory = QuickScheduleViewModel.factory(container))
                // Saved, so a rotation doesn't re-open the sheet over what was typed since.
                var opened by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (!opened) {
                        vm.openShared(shared)
                        opened = true
                    }
                }
                LaunchedEffect(vm) {
                    vm.eventFlow.collect { event ->
                        when (event) {
                            is QuickEvent.Scheduled -> {
                                Toast.makeText(this@ShareTargetActivity, event.summary, Toast.LENGTH_LONG).show()
                                // A new chat found by name is offered its check here, as there is no list to offer it under.
                                if (vm.pendingCheckOffers.isNotEmpty()) return@collect
                            }
                            is QuickEvent.Deleted -> Unit
                            // Shown over whatever is in front; the sheet stays open.
                            is QuickEvent.Checked -> {
                                Toast.makeText(this@ShareTargetActivity, event.message, Toast.LENGTH_LONG).show()
                                return@collect
                            }
                            // Only the offered checks close this, after their results: one started from the open
                            // sheet leaves it open.
                            QuickEvent.ChecksDone -> if (vm.visible) return@collect
                            // Repeating schedules are made in the app's full editor.
                            is QuickEvent.OpenFullEditor -> startActivity(MainActivity.intent(this@ShareTargetActivity, Routes.repeat(event)))
                        }
                        finish()
                    }
                }
                // Swiped away, or scheduled with nothing to offer or check: there is nothing else here, so go
                // back to the app that shared. Whether checks run is the view model's, so it can't outlive
                // them, as a saved flag would after the process was killed mid-check.
                LaunchedEffect(opened, vm.visible, vm.pendingCheckOffers, vm.checksRunning) {
                    if (opened && !vm.visible && vm.pendingCheckOffers.isEmpty() && !vm.checksRunning) finish()
                }
                QuickScheduleSheet(vm)
                val offers = vm.pendingCheckOffers
                if (offers.isNotEmpty() && !vm.visible) {
                    AlertDialog(
                        onDismissRequest = { vm.answerCheckOffer(check = false) },
                        title = { Text("Check in WhatsApp?") },
                        text = { Text("Check ${QuickScheduleViewModel.quoted(offers)} in WhatsApp now? WhatsApp's search is run for the name while you watch; nothing is typed.") },
                        confirmButton = { TextButton(onClick = { vm.answerCheckOffer(check = true) }) { Text("Check") } },
                        dismissButton = { TextButton(onClick = { vm.answerCheckOffer(check = false) }) { Text("Not now") } },
                    )
                }
            }
        }
    }

    /** The shared text, with the subject (e.g. an article title) on its own line above it. */
    private fun sharedText(intent: Intent): String = listOfNotNull(
        intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() },
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() },
    ).distinct().joinToString("\n")
}
