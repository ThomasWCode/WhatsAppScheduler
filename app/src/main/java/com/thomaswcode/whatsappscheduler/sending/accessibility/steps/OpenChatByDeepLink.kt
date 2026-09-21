package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.thomaswcode.whatsappscheduler.domain.ChatTarget
import com.thomaswcode.whatsappscheduler.sending.accessibility.Selectors
import com.thomaswcode.whatsappscheduler.sending.accessibility.UiDriver
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlinx.coroutines.delay

/** Opens a contact chat through the official `wa.me` link with the text pre-filled. */
class OpenChatByDeepLink(private val context: Context, private val driver: UiDriver, private val log: AutomationLog) {

    private val step = "deepLink"

    /** Launches the link and waits for the composer; the auth prompt (if any) is handled by the caller. */
    fun launch(target: ChatTarget, message: String, whatsappPackage: String) {
        val digits = target.phoneE164!!.filter { it.isDigit() }
        val uri = Uri.parse("https://wa.me/$digits").buildUpon().appendQueryParameter("text", message).build()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(whatsappPackage)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        log.info("$step: opening chat for ${target.name} via wa.me")
        context.startActivity(intent)
    }

    suspend fun awaitComposer(timeoutMs: Long) {
        val (which, node) = driver.waitForAny(step, Selectors.waEntry, Selectors.waInvalidNumberDialog, timeoutMs = timeoutMs)
        if (which === Selectors.waInvalidNumberDialog) {
            driver.find(Selectors.waDialogOk)?.let { driver.click(step, it) }
            driver.fail(step, "WhatsApp rejected the number: '${node.text}'")
        }
        delay(300)
    }
}
