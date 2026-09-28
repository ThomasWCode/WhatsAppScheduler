package com.thomaswcode.whatsappscheduler.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                            is QuickEvent.Scheduled -> Toast.makeText(this@ShareTargetActivity, event.summary, Toast.LENGTH_LONG).show()
                            is QuickEvent.Deleted -> Unit
                            // Repeating schedules are made in the app's full editor.
                            is QuickEvent.OpenFullEditor -> startActivity(MainActivity.intent(this@ShareTargetActivity, Routes.repeat(event)))
                        }
                        finish()
                    }
                }
                // Swiped away: there is nothing else here, so go back to the app that shared.
                LaunchedEffect(opened, vm.visible) { if (opened && !vm.visible) finish() }
                QuickScheduleSheet(vm)
            }
        }
    }

    /** The shared text, with the subject (e.g. an article title) on its own line above it. */
    private fun sharedText(intent: Intent): String = listOfNotNull(
        intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() },
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() },
    ).distinct().joinToString("\n")
}
