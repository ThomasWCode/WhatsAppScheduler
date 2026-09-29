package com.thomaswcode.whatsappscheduler.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.SchedulerApp
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.sending.SendKind
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import com.thomaswcode.whatsappscheduler.sending.SendService
import java.time.Instant
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
        if (id < 0) return
        // Alarms armed by a version without occurrence keys have none; the queue copes with that.
        val occurrence = intent.getLongExtra(EXTRA_OCCURRENCE_AT, -1L).takeIf { it >= 0 }
        val container = SchedulerApp.container(context)
        container.log.info("Alarm fired for schedule $id")
        try {
            SendService.enqueue(context, SendRequest(id, SendKind.SCHEDULED, occurrence))
        } catch (e: IllegalStateException) {
            // Android 12 won't start a foreground service from the inexact alarm set when exact alarms are
            // denied (B19). The occurrence is recorded as not sent, so opening the app later can't send it
            // again after a send by hand, and the user is offered one.
            container.log.error("Schedule $id: couldn't start sending from its alarm", e)
            val pending = goAsync()
            container.appScope.launch {
                try {
                    reportNotStarted(container, id, occurrence)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private suspend fun reportNotStarted(container: AppContainer, id: Long, occurrenceAtMillis: Long?) {
        val schedule = container.repository.get(id) ?: return
        val occurrence = occurrenceAtMillis ?: schedule.nextAtMillis ?: return
        // A send started meanwhile, e.g. by opening the app, has this occurrence in hand.
        if (container.sendState.state.value.holdsOccurrence(id)) return
        val detail = "precheck: Android didn't let it start sending in the background"
        val stored = container.repository.recordNotStarted(id, occurrence, detail, Instant.now()) ?: return
        runCatching { container.alarmScheduler.sync(stored) }
        container.fallbackNotifier.notifyFailed(stored, occurrence, detail)
    }

    companion object {
        const val ACTION_FIRE = "com.thomaswcode.whatsappscheduler.FIRE"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_OCCURRENCE_AT = "occurrence_at"
    }
}
