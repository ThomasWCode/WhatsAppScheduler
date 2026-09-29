package com.thomaswcode.whatsappscheduler.sending.accessibility

import android.content.Context
import com.thomaswcode.whatsappscheduler.data.settings.SecretStore
import com.thomaswcode.whatsappscheduler.data.settings.SettingsStore
import com.thomaswcode.whatsappscheduler.domain.ChatCheck
import com.thomaswcode.whatsappscheduler.domain.SendJob
import com.thomaswcode.whatsappscheduler.domain.SendResult
import com.thomaswcode.whatsappscheduler.domain.SendResult.Failed.Companion.PRECHECK
import com.thomaswcode.whatsappscheduler.sending.ChatCheckRunner
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
    private val notifications: Notifications,
) : MessageSender, ChatCheckRunner {

    private val wake = WakeUnlockController(context, log, notifications)

    override suspend fun send(job: SendJob): SendResult {
        val service = WaAccessibilityService.instance.value
            ?: return SendResult.Failed(PRECHECK, "Accessibility service is not running")
        val whatsapp = installedWhatsApp() ?: return SendResult.Failed(PRECHECK, "WhatsApp is not installed", retryable = false)
        val settings = settingsStore.current()
        val pinBlock = pinGuard.block()?.describe()
        // Checked on every send, including ones that won't need the PIN: a daily limit expires by
        // itself, and its "paused" notice must not outlive it just because the phone was unlocked.
        if (pinBlock == null) notifications.cancel(Notifications.ID_PIN_PAUSED)
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
                val auth = HandleAuthPrompt(driver, log, progress, pinBlock, secretCode)
                when {
                    target.isLocked && !(settings.deepLinkForLockedChats && target.canDeepLink) -> {
                        search.goHome(whatsapp, t)
                        OpenLockedChat(driver, log, search, auth).run(target.name, pin, secretCode, t)
                    }
                    target.canDeepLink -> {
                        // A Chat Lock prompt cancelled earlier leaves WhatsApp's unlock screen in its task,
                        // and while it is there WhatsApp won't open a locked chat from a link. Bringing the
                        // chat list to the front first closes it (seen on One UI 8 / WhatsApp 2.26).
                        if (target.isLocked) search.goHome(whatsapp, t)
                        deepLink.launch(target, job.message, whatsapp)
                        if (target.isLocked) auth.run(pin, appearTimeoutMs = t, stepTimeoutMs = t, alreadyPast = deepLink::composerInFront)
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
            // A PIN entry that unlocked the phone reset its failure counter, so the app's count starts over
            // too, and a "paused" notice left from an expired daily limit is no longer true.
            if (progress.pinAccepted) {
                runCatching {
                    pinGuard.recordAccepted()
                    if (pinGuard.block() == null) notifications.cancel(Notifications.ID_PIN_PAUSED)
                }
            }
        }
    }

    /**
     * F4 "Check this chat now": finds the chat by name the way a send would, checks the title of the
     * chat that opens, then backs out of WhatsApp to the app it was started from. Nothing is typed.
     * The user has just tapped the button, so the phone is unlocked and nothing needs waking.
     */
    override suspend fun checkChat(name: String): ChatCheck {
        val service = WaAccessibilityService.instance.value ?: return ChatCheck.NotChecked("auto-send isn't running")
        val whatsapp = installedWhatsApp() ?: return ChatCheck.NotChecked("WhatsApp isn't installed")
        val settings = settingsStore.current()
        val t = settings.stepTimeoutMs
        val driver = UiDriver(service, log, t)
        log.info("check: looking for '$name'")
        service.active.set(true)
        return try {
            withTimeout(settings.jobTimeoutMs) {
                val search = OpenChatBySearch(context, driver, log)
                // Getting to the search is WhatsApp's side: a failure there says nothing about the name.
                try {
                    search.goHome(whatsapp, t)
                    search.search(name, t)
                } catch (e: StepFailure) {
                    log.warn("check: couldn't search for '$name': ${e.message}")
                    return@withTimeout ChatCheck.Failed(e.message ?: e.step)
                }
                // Only the result row and the chat's title are about the name.
                try {
                    search.openResult(name, t)
                    log.info("check: found '$name'")
                    ChatCheck.Found
                } catch (e: StepFailure) {
                    log.warn("check: '$name' not found: ${e.message}")
                    ChatCheck.NotFound(e.message ?: "not found")
                }
            }
        } catch (e: TimeoutCancellationException) {
            ChatCheck.Failed("it took too long")
        } finally {
            runCatching { leaveWhatsApp(driver, whatsapp) }
            service.active.set(false)
        }
    }

    /**
     * Back out of the chat, the search and WhatsApp itself, one step at a time and only while WhatsApp
     * is in front, so the app the check was started from comes back and nothing of the user's is left.
     */
    private suspend fun leaveWhatsApp(driver: UiDriver, whatsapp: String) {
        repeat(MAX_BACKS) {
            driver.waitUntil(1_000) { driver.foregroundPackage() != null }
            if (driver.foregroundPackage() != whatsapp) return
            driver.back()
        }
    }

    /**
     * Leaves WhatsApp and puts the phone back as it was. After a failure from before the tap,
     * [unsentDraft] is cleared from the composer first, so a message pre-filled by the chat link
     * doesn't wait in WhatsApp as a draft that could be sent by accident later (I10).
     */
    private suspend fun tidyUp(driver: UiDriver, before: UnlockOutcome, relock: Boolean, unsentDraft: String?) {
        unsentDraft?.let { clearDraft(driver, it) }
        // Out of the chat; out of any search a failure left open, which may hold the Chat Lock secret
        // code; and out of any Chat Lock prompt or secret-code sheet, which would derail the next attempt.
        repeat(5) {
            if (driver.findAny(Selectors.waEntry, Selectors.waSearchInput, Selectors.waSecretCodeField, Selectors.authNegativeButton) != null) driver.back()
        }
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
        if (entry.isShowingHintText || !sameVisibleText(entry.text, message)) return
        runCatching { driver.setText("tidyUp", entry, "") }
            .onSuccess { log.info("tidyUp: cleared the unsent message from the composer") }
            .onFailure { log.warn("tidyUp: could not clear the unsent message: ${it.message}") }
    }

    private companion object {
        /** Chat, search, search box, chat list, WhatsApp itself, and one spare. */
        const val MAX_BACKS = 6
    }

    @Suppress("DEPRECATION")
    private fun installedWhatsApp(): String? =
        listOf(Selectors.WHATSAPP, Selectors.WHATSAPP_BUSINESS).firstOrNull { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
}
