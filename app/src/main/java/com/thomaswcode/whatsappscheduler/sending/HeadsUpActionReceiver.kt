package com.thomaswcode.whatsappscheduler.sending

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.SchedulerApp

/** The countdown's buttons (features F1): each answers the countdown that is waiting, if any. */
class HeadsUpActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val choice = when (intent.action) {
            ACTION_SEND_NOW -> HeadsUpChoice.SEND
            ACTION_SNOOZE -> HeadsUpChoice.SNOOZE
            ACTION_POSTPONE -> HeadsUpChoice.POSTPONE
            else -> return
        }
        SchedulerApp.container(context).headsUpAnswers.answer(choice)
    }

    companion object {
        const val ACTION_SEND_NOW = "com.thomaswcode.whatsappscheduler.action.HEADS_UP_SEND_NOW"
        const val ACTION_SNOOZE = "com.thomaswcode.whatsappscheduler.action.HEADS_UP_SNOOZE"
        const val ACTION_POSTPONE = "com.thomaswcode.whatsappscheduler.action.HEADS_UP_POSTPONE"

        fun intent(context: Context, action: String): Intent = Intent(context, HeadsUpActionReceiver::class.java).setAction(action)
    }
}
