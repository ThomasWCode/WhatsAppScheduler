package com.thomaswcode.whatsappscheduler.sending

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.SchedulerApp
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors

/**
 * Manual paths from the notifications. [ACTION_SEND] copies the message to the clipboard (only
 * allowed from a foreground activity) and opens the target chat with it pre-filled, or WhatsApp's
 * chat list for groups. [ACTION_CHECK] only opens the chat, with no text, so the user can see
 * whether a send that was interrupted after tapping Send went out.
 */
class FallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sending by hand deals with the prompt: gone, as when its body is tapped, and so from its button too.
        if (intent.action == ACTION_SEND) {
            intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L).takeIf { it >= 0 }?.let { SchedulerApp.container(this).fallbackNotifier.cancel(it) }
        }
        val checkOnly = intent.action == ACTION_CHECK || intent.action == ACTION_OPEN
        val message = if (checkOnly) null else intent.getStringExtra(EXTRA_MESSAGE) ?: ""
        val phone = intent.getStringExtra(EXTRA_PHONE)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        message?.let { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.fallback_clip_label), it)) }
        val pkg = listOf(Selectors.WHATSAPP, Selectors.WHATSAPP_BUSINESS).firstOrNull { runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
        val open = if (!phone.isNullOrBlank()) {
            val uri = Uri.parse("https://wa.me/${phone.filter { it.isDigit() }}").buildUpon()
                .apply { message?.let { appendQueryParameter("text", it) } }
                .build()
            Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
        } else {
            pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        }
        val hint = when {
            open == null -> getString(if (message != null) R.string.fallback_whatsapp_not_found_copied else R.string.fallback_whatsapp_not_found)
            intent.action == ACTION_OPEN -> null
            checkOnly -> getString(R.string.fallback_check_arrived, name)
            else -> getString(R.string.fallback_copied_open, name)
        }
        hint?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        open?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    companion object {
        const val ACTION_SEND = "com.thomaswcode.whatsappscheduler.action.MANUAL_SEND"
        const val ACTION_CHECK = "com.thomaswcode.whatsappscheduler.action.CHECK_CHAT"
        const val ACTION_OPEN = "com.thomaswcode.whatsappscheduler.action.OPEN_CHAT"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_NAME = "name"
        const val EXTRA_SCHEDULE_ID = "scheduleId"

        // The two differ in action as well as extras, because extras alone don't tell PendingIntents apart.

        /** Tap to send: the message on the clipboard and the chat opened with it. */
        fun manualSend(context: Context, schedule: Schedule): Intent = Intent(context, FallbackActivity::class.java)
            .setAction(ACTION_SEND)
            .putExtra(EXTRA_SCHEDULE_ID, schedule.id)
            .putExtra(EXTRA_MESSAGE, schedule.message)
            .putExtra(EXTRA_PHONE, schedule.phoneE164)
            .putExtra(EXTRA_NAME, schedule.targetName)

        /** Just opens the chat, e.g. from the "Sent" notification. */
        fun openChat(context: Context, schedule: Schedule): Intent = Intent(context, FallbackActivity::class.java)
            .setAction(ACTION_OPEN)
            .putExtra(EXTRA_PHONE, schedule.phoneE164)
            .putExtra(EXTRA_NAME, schedule.targetName)

        /** Open chat, to see whether a message that may have been sent arrived. */
        fun checkChat(context: Context, schedule: Schedule): Intent = Intent(context, FallbackActivity::class.java)
            .setAction(ACTION_CHECK)
            .putExtra(EXTRA_PHONE, schedule.phoneE164)
            .putExtra(EXTRA_NAME, schedule.targetName)
    }
}
