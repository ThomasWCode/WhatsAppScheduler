package com.thomaswcode.whatsappscheduler.sending

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.data.db.Schedule

/** When automation fails, offers a one-tap manual send so the message is delayed rather than lost. */
class FallbackNotifier(private val context: Context, private val notifications: Notifications) {

    fun notify(schedule: Schedule, reason: String) {
        val intent = Intent(context, FallbackActivity::class.java)
            .putExtra(FallbackActivity.EXTRA_MESSAGE, schedule.message)
            .putExtra(FallbackActivity.EXTRA_PHONE, schedule.phoneE164)
            .putExtra(FallbackActivity.EXTRA_NAME, schedule.targetName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context, schedule.id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notifications.show(
            Notifications.ID_FALLBACK_BASE + schedule.id.toInt(),
            notifications.fallback(
                title = "Tap to send to ${schedule.targetName}",
                text = "Auto-send failed ($reason). Tap to open the chat with the message ready.",
                tap = tap,
            ),
        )
    }
}
