package com.thomaswcode.whatsappscheduler.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.SchedulerApp
import com.thomaswcode.whatsappscheduler.sending.SendKind
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import com.thomaswcode.whatsappscheduler.sending.SendService

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
        if (id < 0) return
        // Alarms armed by a version without occurrence keys have none; the queue copes with that.
        val occurrence = intent.getLongExtra(EXTRA_OCCURRENCE_AT, -1L).takeIf { it >= 0 }
        SchedulerApp.container(context).log.info("Alarm fired for schedule $id")
        SendService.enqueue(context, SendRequest(id, SendKind.SCHEDULED, occurrence))
    }

    companion object {
        const val ACTION_FIRE = "com.thomaswcode.whatsappscheduler.FIRE"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_OCCURRENCE_AT = "occurrence_at"
    }
}
