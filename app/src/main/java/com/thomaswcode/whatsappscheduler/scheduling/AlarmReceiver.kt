package com.thomaswcode.whatsappscheduler.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.SchedulerApp
import com.thomaswcode.whatsappscheduler.sending.SendService

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
        if (id < 0) return
        SchedulerApp.container(context).log.info("Alarm fired for schedule $id")
        SendService.enqueue(context, id)
    }

    companion object {
        const val ACTION_FIRE = "com.thomaswcode.whatsappscheduler.FIRE"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
    }
}
