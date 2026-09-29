package com.thomaswcode.whatsappscheduler.sending

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.thomaswcode.whatsappscheduler.SchedulerApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The buttons on "Needs your attention" notifications that act without opening anything (features
 * F3): Send now, Send again, Discard and Mark as sent. Postpone… opens [PostponeActivity] instead.
 */
class AttentionActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L).takeIf { it >= 0 } ?: return
        val container = SchedulerApp.container(context)
        val actions = container.attentionActions
        val pending = goAsync()
        container.appScope.launch {
            try {
                val message = when (intent.action) {
                    ACTION_SEND_NOW -> actions.sendNow(id)
                    ACTION_DISCARD -> actions.discard(id)
                    ACTION_MARK_SENT -> actions.markSent(id)
                    else -> null
                }
                message?.let { withContext(Dispatchers.Main) { Toast.makeText(context.applicationContext, it, Toast.LENGTH_LONG).show() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.log.error("Notification action ${intent.action} failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SEND_NOW = "com.thomaswcode.whatsappscheduler.action.ATTENTION_SEND_NOW"
        const val ACTION_DISCARD = "com.thomaswcode.whatsappscheduler.action.ATTENTION_DISCARD"
        const val ACTION_MARK_SENT = "com.thomaswcode.whatsappscheduler.action.ATTENTION_MARK_SENT"
        private const val EXTRA_SCHEDULE_ID = "scheduleId"

        fun intent(context: Context, action: String, scheduleId: Long): Intent =
            Intent(context, AttentionActionReceiver::class.java).setAction(action).putExtra(EXTRA_SCHEDULE_ID, scheduleId)
    }
}
