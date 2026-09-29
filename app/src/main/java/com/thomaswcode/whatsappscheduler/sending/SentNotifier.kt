package com.thomaswcode.whatsappscheduler.sending

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import java.util.Date

/**
 * The quiet "Sent to Mum" confirmation (features F2), or "Dry run: not sent to Mum" after a test run,
 * which makes a forgotten test mode visible (B11). Low importance, no sound, gone after a day; one
 * per schedule, grouped under "Sent to 3 chats".
 */
class SentNotifier(private val context: Context, private val notifications: Notifications) {

    fun notifySent(schedule: Schedule, dryRun: Boolean, atMillis: Long = System.currentTimeMillis()) {
        val id = Notifications.ID_SENT_BASE + schedule.id.toInt()
        val title = if (dryRun) "Dry run: not sent to ${schedule.targetName}" else "Sent to ${schedule.targetName}"
        val firstLine = schedule.message.lineSequence().firstOrNull().orEmpty().let { if (it.length > 80) it.take(79) + "…" else it }
        val text = DateFormat.getTimeFormat(context).format(Date(atMillis)) + " · " + firstLine
        val openChat = PendingIntent.getActivity(
            context, id, FallbackActivity.openChat(context, schedule).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notifications.show(id, notifications.sent(title, text, openChat))
        val count = notifications.sentShowing()
        if (count > 1) notifications.show(Notifications.ID_SENT_SUMMARY, notifications.sentSummary("Sent to $count chats"))
    }
}
