package com.thomaswcode.whatsappscheduler.sending

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.ui.MainActivity

/** Notification channels and builders shared by the service, unlock flow and fallback path. */
class Notifications(private val context: Context) {

    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SENDING, context.getString(R.string.notification_channel_sending), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FALLBACK, context.getString(R.string.notification_channel_fallback), NotificationManager.IMPORTANCE_HIGH)
        )
        // Quiet: in the shade, no sound, no pop-up (features F2).
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SENT, context.getString(R.string.notification_channel_sent), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UNLOCK, context.getString(R.string.notification_channel_unlock), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /** Persistent notification shown while the foreground service is working. */
    fun sending(text: String): Notification =
        Notification.Builder(context, CHANNEL_SENDING)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle("WhatsApp Scheduler")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    /** Full-screen notification that launches the unlock activity even when the screen is off. */
    fun unlock(fullScreen: PendingIntent): Notification =
        Notification.Builder(context, CHANNEL_UNLOCK)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle("Sending scheduled WhatsApp message")
            .setContentText("Unlocking to send…")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .build()

    fun fallback(title: String, text: String, tap: PendingIntent): Notification =
        Notification.Builder(context, CHANNEL_FALLBACK)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

    /**
     * "Sent to Mum": the body opens the app on that row, Open chat opens the chat, and it goes by itself
     * after a day. [group] keeps real sends and test runs apart ([GROUP_SENT], [GROUP_DRY_RUN]).
     */
    fun sent(title: String, text: String, openRow: PendingIntent, openChat: PendingIntent, group: String): Notification =
        Notification.Builder(context, CHANNEL_SENT)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openRow)
            .addAction(Notification.Action.Builder(null, "Open chat", openChat).build())
            .setGroup(group)
            .setAutoCancel(true)
            .setTimeoutAfter(SENT_LIFETIME_MS)
            .build()

    fun sentSummary(text: String, group: String): Notification =
        Notification.Builder(context, CHANNEL_SENT)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle(text)
            .setGroup(group)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setTimeoutAfter(SENT_LIFETIME_MS)
            .build()

    /** How many of [group]'s notifications are showing, not counting its summary. */
    fun sentShowing(group: String): Int = runCatching {
        manager.activeNotifications.count { it.notification.group == group && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
    }.getOrDefault(0)

    fun show(id: Int, notification: Notification) = manager.notify(id, notification)

    fun cancel(id: Int) = manager.cancel(id)

    fun isShowing(id: Int): Boolean = runCatching { manager.activeNotifications.any { it.id == id } }.getOrDefault(false)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val CHANNEL_SENDING = "sending"
        const val CHANNEL_FALLBACK = "fallback"
        const val CHANNEL_UNLOCK = "unlock"
        const val CHANNEL_SENT = "sent"
        const val GROUP_SENT = "sent"
        const val ID_SENT_SUMMARY = 4
        /** Test runs are grouped apart, so a collapsed group can't read "Sent" over messages that weren't (B11). */
        const val GROUP_DRY_RUN = "dry_run"
        const val ID_DRY_RUN_SUMMARY = 5
        /** Apart from [ID_SENT_BASE], so a schedule's test run and real send are two notifications in their own groups. */
        const val ID_DRY_RUN_BASE = 700_000
        /** Well above the fallback prompts' ids ([ID_FALLBACK_BASE] + schedule id). */
        const val ID_SENT_BASE = 500_000
        const val SENT_LIFETIME_MS = 24 * 60 * 60 * 1000L
        const val ID_SENDING = 1
        const val ID_UNLOCK = 2
        const val ID_PIN_PAUSED = 3
        const val ID_FALLBACK_BASE = 1000
    }
}
