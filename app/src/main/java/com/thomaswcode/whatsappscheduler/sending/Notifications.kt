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

    fun show(id: Int, notification: Notification) = manager.notify(id, notification)

    fun cancel(id: Int) = manager.cancel(id)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val CHANNEL_SENDING = "sending"
        const val CHANNEL_FALLBACK = "fallback"
        const val CHANNEL_UNLOCK = "unlock"
        const val ID_SENDING = 1
        const val ID_UNLOCK = 2
        const val ID_PIN_PAUSED = 3
        const val ID_FALLBACK_BASE = 1000
    }
}
