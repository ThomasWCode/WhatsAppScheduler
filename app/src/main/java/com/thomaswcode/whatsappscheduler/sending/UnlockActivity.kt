package com.thomaswcode.whatsappscheduler.sending

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred

/**
 * Invisible activity that turns the screen on over the lock screen and asks the system to show
 * the PIN bouncer, which [com.thomaswcode.whatsappscheduler.sending.accessibility.steps.UnlockKeyguard]
 * then fills in. Finishes when the send job releases it.
 */
class UnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "Sending scheduled WhatsApp message…"
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        })
        UnlockBroker.attach(this)
        val keyguard = getSystemService(KeyguardManager::class.java)
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = UnlockBroker.dismissed(true, null)
            override fun onDismissCancelled() = UnlockBroker.dismissed(false, "cancelled")
            override fun onDismissError() = UnlockBroker.dismissed(false, "error")
        })
    }

    override fun onDestroy() {
        UnlockBroker.detach(this)
        super.onDestroy()
    }
}

/** Rendezvous between the running send job and the transient [UnlockActivity]. */
object UnlockBroker {
    @Volatile private var activity: UnlockActivity? = null
    @Volatile var dismissal: CompletableDeferred<Pair<Boolean, String?>> = CompletableDeferred()

    fun reset() { dismissal = CompletableDeferred() }

    fun attach(a: UnlockActivity) { activity = a }

    fun detach(a: UnlockActivity) { if (activity === a) activity = null }

    fun dismissed(ok: Boolean, reason: String?) { dismissal.let { if (!it.isCompleted) it.complete(ok to reason) } }

    fun finish() { activity?.finish(); activity = null }
}
