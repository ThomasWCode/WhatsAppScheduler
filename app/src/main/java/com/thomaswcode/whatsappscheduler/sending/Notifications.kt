package com.thomaswcode.whatsappscheduler.sending

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
        // Pops up, so the countdown is seen, but with no sound: a short buzz at most (features F1).
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_HEADS_UP, context.getString(R.string.notification_channel_heads_up), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120)
            }
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

    /** The countdown before a scheduled send (features F1), counting down to [endsAtMillis]. */
    fun headsUp(
        title: String,
        text: String,
        endsAtMillis: Long,
        sendNow: PendingIntent,
        snooze: PendingIntent,
        postpone: PendingIntent,
    ): Notification =
        Notification.Builder(context, CHANNEL_HEADS_UP)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle(title)
            .setContentText(text)
            // What a screen reader says as it pops up: the clock alone reads as numbers.
            .setTicker("$title in ${((endsAtMillis - System.currentTimeMillis() + 500) / 1_000).coerceAtLeast(1)} seconds")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setWhen(endsAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter((endsAtMillis - System.currentTimeMillis()).coerceAtLeast(1_000L))
            .addAction(Notification.Action.Builder(null, "Send now", sendNow).build())
            .addAction(Notification.Action.Builder(null, "Snooze 5 min", snooze).build())
            .addAction(Notification.Action.Builder(null, "Postpone…", postpone).build())
            .build()

    /** On the "Needs your attention" channel (features F3), with the situation's buttons as [actions]. */
    fun fallback(
        title: String,
        text: String,
        tap: PendingIntent,
        actions: List<Pair<String, PendingIntent>> = emptyList(),
        /** How long it may stay when nothing else removes it; for as long as needed when null. */
        lifetimeMs: Long? = null,
        /** What it is about, kept on it (not shown) so it can be told from one about something else. */
        about: String? = null,
    ): Notification =
        Notification.Builder(context, CHANNEL_FALLBACK)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .apply { actions.forEach { (label, intent) -> addAction(Notification.Action.Builder(null, label, intent).build()) } }
            .apply { lifetimeMs?.let(::setTimeoutAfter) }
            .apply { about?.let { addExtras(Bundle().apply { putString(EXTRA_ABOUT, it) }) } }
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

    /**
     * Whether a notification on [channel] would pop up over what is on screen: not when the app's
     * notifications are off (on Android 13 and later, also when they were never allowed), nor when that
     * channel is, or has been turned down to stay in the shade, nor when Do Not Disturb hides it.
     */
    fun popsUp(channel: String): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val found = manager.getNotificationChannel(channel)
        if ((found?.importance ?: NotificationManager.IMPORTANCE_HIGH) < NotificationManager.IMPORTANCE_HIGH) return false
        return when (manager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> true
            // Any Do Not Disturb mode: only a channel allowed to break through it is sure to be seen. An app
            // can't tell whether the active mode would still let this one pop up, so it's taken not to.
            else -> found?.canBypassDnd() == true
        }
    }

    fun isShowing(id: Int): Boolean = runCatching { manager.activeNotifications.any { it.id == id } }.getOrDefault(false)

    /** What notification [id] is about ([fallback]'s about), if it is showing. */
    fun aboutOf(id: Int): String? = runCatching {
        manager.activeNotifications.firstOrNull { it.id == id }?.notification?.extras?.getString(EXTRA_ABOUT)
    }.getOrNull()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val CHANNEL_SENDING = "sending"
        const val CHANNEL_FALLBACK = "fallback"
        const val CHANNEL_UNLOCK = "unlock"
        const val CHANNEL_SENT = "sent"
        const val CHANNEL_HEADS_UP = "before_sending"
        const val ID_HEADS_UP = 6
        private const val EXTRA_ABOUT = "com.thomaswcode.whatsappscheduler.about"
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
        /** A pre-flight warning (features F5), apart from [ID_FALLBACK_BASE] so it can't replace a prompt about an earlier occurrence. */
        const val ID_PREFLIGHT_BASE = 300_000
    }
}
