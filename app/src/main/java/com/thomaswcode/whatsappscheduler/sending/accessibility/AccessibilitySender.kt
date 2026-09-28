package com.thomaswcode.whatsappscheduler.sending.accessibility

import android.content.Context
import com.thomaswcode.whatsappscheduler.data.settings.SecretStore
import com.thomaswcode.whatsappscheduler.data.settings.SettingsStore
import com.thomaswcode.whatsappscheduler.domain.SendJob
import com.thomaswcode.whatsappscheduler.domain.SendResult
import com.thomaswcode.whatsappscheduler.domain.SendResult.Failed.Companion.PRECHECK
import com.thomaswcode.whatsappscheduler.sending.MessageSender
import com.thomaswcode.whatsappscheduler.sending.Notifications
import com.thomaswcode.whatsappscheduler.sending.PinGuard
import com.thomaswcode.whatsappscheduler.sending.UnlockOutcome
import com.thomaswcode.whatsappscheduler.sending.WakeUnlockController
import com.thomaswcode.whatsappscheduler.sending.accessibility.steps.HandleAuthPrompt
import com.thomaswcode.whatsappscheduler.sending.accessibility.steps.OpenChatByDeepLink
import com.thomaswcode.whatsappscheduler.sending.accessibility.steps.OpenChatBySearch
import com.thomaswcode.whatsappscheduler.sending.accessibility.steps.OpenLockedChat
import com.thomaswcode.whatsappscheduler.sending.accessibility.steps.TypeAndSend
import com.thomaswcode.whatsappscheduler.sending.accessibility.steps.VerifySent
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Composes the automation steps into one send: wake, unlock, open chat, type, send, verify, tidy up.
 * Failures are classified by how far the attempt got ([JobProgress]): only those from before Send
 * was tapped come back as [SendResult.Failed], the only kind the queue retries.
 */
class AccessibilitySender(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val secrets: SecretStore,
    private val pinGuard: PinGuard,
    private val log: AutomationLog,
    notifications: Notifications,
) : MessageSender {

    private val wake = WakeUnlockController(context, log, notifications)

    override suspend fun send(job: SendJob): SendResult {
        val service = WaAccessibilityService.instance.value
            ?: return SendResult.Failed(PRECHECK, "Accessibility service is not running")
        val whatsapp = installedWhatsApp() ?: return SendResult.Failed(PRECHECK, "WhatsApp is not installed", retryable = false)
        val settings = settingsStore.current()
        val pinBlock = pinGuard.block()?.describe()
        // Checked before the screen is woken: the lock screen needs the PIN, and PIN entry is paused (B04).
        if (pinBlock != null && wake.needsPin()) return SendResult.Failed(PRECHECK, pinBlock, retryable = false)
        val pin = secrets.pin()
        val secretCode = secrets.secretCode()
        val driver = UiDriver(service, log, settings.stepTimeoutMs)
        val progress = JobProgress()
        val t = settings.stepTimeoutMs
        val target = job.target
        log.info("job ${job.scheduleId} attempt ${job.attempt}: to '${target.name}' kind=${target.kind} locked=${target.isLocked} dryRun=${job.dryRun}")

        service.active.set(true)
        val before = wake.snapshot()
        val relock = before.wasLocked && settings.relockAfterSend
        try {
            withTimeout(settings.jobTimeoutMs) {
                wake.ensureUnlocked(driver, before, pin, pinBlock, progress, t)

                val deepLink = OpenChatByDeepLink(context, driver, log)
                val search = OpenChatBySearch(context, driver, log)
                val auth = HandleAuthPrompt(driver, log, progress, pinBlock)
                when {
                    target.isLocked && !(settings.deepLinkForLockedChats && target.canDeepLink) -> {
                        search.goHome(whatsapp, t)
                        OpenLockedChat(driver, log, search, auth).run(target.name, pin, secretCode, t)
                    }
                    target.canDeepLink -> {
                        deepLink.launch(target, job.message, whatsapp)
                        if (target.isLocked) auth.run(pin, appearTimeoutMs = 4_000, stepTimeoutMs = t)
                        deepLink.awaitComposer(t)
                    }
                    else -> {
                        search.goHome(whatsapp, t)
                        search.search(target.name, t)
                        search.openResult(target.name, t)
                    }
                }
                wake.releaseActivity()
                TypeAndSend(driver, log, progress).run(job.message, job.dryRun, t)
            }
            // Outside the job timeout, which can therefore only expire before the tap. Running out of
            // time after it would otherwise look like a failed send and be retried as a duplicate (B02).
            if (!job.dryRun) VerifySent(driver, log).run(job.message, t)
            tidyUp(driver, before, relock, unsentDraft = null)
            log.info("job ${job.scheduleId}: ${if (job.dryRun) "dry run complete" else "sent"}")
            return if (job.dryRun) SendResult.DryRun else SendResult.Sent
        } catch (e: StepFailure) {
            val result = progress.classify(e.step, e.message ?: "failed", e.dump, e.retryable)
            runCatching { tidyUp(driver, before, relock, unsentDraft = job.message.takeIf { result is SendResult.Failed }) }
            return result
        } catch (e: TimeoutCancellationException) {
            val dump = runCatching { driver.dump() }.getOrNull()
            dump?.let { log.saveDump("job timeout", it) }
            val result = progress.classify("job", "Job exceeded ${settings.jobTimeoutMs}ms", dump, retryable = true)
            runCatching { tidyUp(driver, before, relock, unsentDraft = job.message.takeIf { result is SendResult.Failed }) }
            return result
        } finally {
            service.active.set(false)
            wake.releaseActivity()
            wake.releaseWakeLock()
            // A PIN entry that unlocked the phone reset its failure counter, so the app's count starts over too.
            if (progress.pinAccepted) runCatching { pinGuard.recordAccepted() }
        }
    }

    /**
     * Leaves WhatsApp and puts the phone back as it was. After a failure from before the tap,
     * [unsentDraft] is cleared from the composer first, so a message pre-filled by the chat link
     * doesn't wait in WhatsApp as a draft that could be sent by accident later (I10).
     */
    private suspend fun tidyUp(driver: UiDriver, before: UnlockOutcome, relock: Boolean, unsentDraft: String?) {
        unsentDraft?.let { clearDraft(driver, it) }
        repeat(2) { if (driver.find(Selectors.waEntry) != null) driver.back() }
        if (relock || before.screenWasOff) {
            wake.relock(driver)
        } else {
            driver.home()
        }
    }

    /** Only text that is exactly the scheduled message is cleared, never a draft of the user's own. */
    private suspend fun clearDraft(driver: UiDriver, message: String) {
        val entry = driver.find(Selectors.waEntry) ?: return
        entry.refresh()
        if (entry.text?.toString()?.trim() != message.trim()) return
        runCatching { driver.setText("tidyUp", entry, "") }
            .onSuccess { log.info("tidyUp: cleared the unsent message from the composer") }
            .onFailure { log.warn("tidyUp: could not clear the unsent message: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    private fun installedWhatsApp(): String? =
        listOf(Selectors.WHATSAPP, Selectors.WHATSAPP_BUSINESS).firstOrNull { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
}
