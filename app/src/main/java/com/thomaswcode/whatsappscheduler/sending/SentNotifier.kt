package com.thomaswcode.whatsappscheduler.sending

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.ui.MainActivity
import java.util.Date

/**
 * The quiet "Sent to Mum" confirmation (features F2), or "Dry run: not sent to Mum" after a test run,
 * which makes a forgotten test mode visible (B11). Low importance, no sound, gone after a day; one
 * per schedule, grouped under "Sent to 3 chats", with test runs in a group of their own.
 */
class SentNotifier(private val context: Context, private val notifications: Notifications) {

    fun notifySent(schedule: Schedule, dryRun: Boolean, atMillis: Long = System.currentTimeMillis()) {
        val id = (if (dryRun) Notifications.ID_DRY_RUN_BASE else Notifications.ID_SENT_BASE) + schedule.id.toInt()
        val title = if (dryRun) "Dry run: not sent to ${schedule.targetName}" else "Sent to ${schedule.targetName}"
        val firstLine = schedule.message.lineSequence().firstOrNull().orEmpty().let { if (it.length > 80) it.take(79) + "…" else it }
        val text = DateFormat.getTimeFormat(context).format(Date(atMillis)) + " · " + firstLine
        val openChat = PendingIntent.getActivity(
            context, id, FallbackActivity.openChat(context, schedule).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The body opens the app on that row (features F2).
        val openRow = PendingIntent.getActivity(
            context, id, MainActivity.rowIntent(context, schedule.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val group = if (dryRun) Notifications.GROUP_DRY_RUN else Notifications.GROUP_SENT
        notifications.show(id, notifications.sent(title, text, openRow, openChat, group))
        val count = notifications.sentShowing(group)
        if (count > 1) {
            if (dryRun) {
                notifications.show(Notifications.ID_DRY_RUN_SUMMARY, notifications.sentSummary("Dry run: not sent to $count chats", group))
            } else {
                notifications.show(Notifications.ID_SENT_SUMMARY, notifications.sentSummary("Sent to $count chats", group))
            }
        }
    }
}
