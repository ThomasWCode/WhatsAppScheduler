package com.thomaswcode.whatsappscheduler.sending

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors

/**
 * Manual-send path: copies the message to the clipboard (only allowed from a foreground activity)
 * and opens the target chat, or WhatsApp's chat list for groups.
 */
class FallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
        val phone = intent.getStringExtra(EXTRA_PHONE)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", message))
        val pkg = listOf(Selectors.WHATSAPP, Selectors.WHATSAPP_BUSINESS).firstOrNull { runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
        val open = if (!phone.isNullOrBlank()) {
            val uri = Uri.parse("https://wa.me/${phone.filter { it.isDigit() }}").buildUpon().appendQueryParameter("text", message).build()
            Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
        } else {
            pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        }
        if (open != null) {
            Toast.makeText(this, "Message copied. Open '$name' and paste if it is not pre-filled.", Toast.LENGTH_LONG).show()
            startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Toast.makeText(this, "WhatsApp not found. Message copied to clipboard.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_NAME = "name"
    }
}
