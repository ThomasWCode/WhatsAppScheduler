package com.thomaswcode.whatsappscheduler.sending

import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The countdown on the phone (features F1): a heads-up notification on the "Before sending" channel,
 * with Send now, Snooze 5 min and Postpone…, counting down to the send.
 */
class HeadsUpPrompt(
    private val context: Context,
    private val notifications: Notifications,
    private val answers: HeadsUpAnswers,
) : HeadsUp {

    override fun inUse(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive

    /** Ringing counts: the call's screen is in front, and the countdown would go unseen behind it. */
    override fun inCall(): Boolean = context.getSystemService(AudioManager::class.java).mode.let {
        it == AudioManager.MODE_RINGTONE || it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION ||
            (Build.VERSION.SDK_INT >= 30 && it == AudioManager.MODE_CALL_SCREENING)
    }

    override fun canShow(): Boolean = notifications.popsUp(Notifications.CHANNEL_HEADS_UP)

    override suspend fun ask(schedules: List<Schedule>, endsAtMillis: Long): HeadsUpChoice? {
        val first = schedules.first()
        val wait = answers.expect()
        val to = when (schedules.size) {
            1 -> first.targetName
            2 -> "${first.targetName} and ${schedules[1].targetName}"
            else -> schedules.dropLast(1).joinToString { it.targetName } + " and " + schedules.last().targetName
        }
        val text = first.message.lineSequence().firstOrNull().orEmpty().let { if (it.length > 80) it.take(79) + "…" else it }
        notifications.show(
            Notifications.ID_HEADS_UP,
            notifications.headsUp(
                title = "Sending to $to",
                text = text,
                endsAtMillis = endsAtMillis,
                sendNow = button(HeadsUpActionReceiver.ACTION_SEND_NOW, wait.token),
                snooze = button(HeadsUpActionReceiver.ACTION_SNOOZE, wait.token),
                postpone = button(HeadsUpActionReceiver.ACTION_POSTPONE, wait.token),
            ),
        )
        return try {
            withTimeoutOrNull((endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0)) { wait.answer.await() }
        } finally {
            answers.done(wait)
            notifications.cancel(Notifications.ID_HEADS_UP)
        }
    }

    /**
     * The token is in the request code as well as the extras: extras don't make a PendingIntent
     * different, so with one request code every countdown would rewrite the last one's buttons, and an
     * old notification still showing could answer the new countdown.
     */
    private fun button(action: String, token: Long): PendingIntent = PendingIntent.getBroadcast(
        context, token.toInt(), HeadsUpActionReceiver.intent(context, action, token), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
