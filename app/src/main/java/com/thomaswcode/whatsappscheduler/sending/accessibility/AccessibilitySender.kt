package com.thomaswcode.whatsappscheduler.sending.accessibility

import android.content.Context
import com.thomaswcode.whatsappscheduler.data.settings.SecretStore
import com.thomaswcode.whatsappscheduler.data.settings.SettingsStore
import com.thomaswcode.whatsappscheduler.domain.SendJob
import com.thomaswcode.whatsappscheduler.domain.SendResult
import com.thomaswcode.whatsappscheduler.sending.MessageSender
import com.thomaswcode.whatsappscheduler.sending.Notifications
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

/** Composes the automation steps into one send: wake, unlock, open chat, type, send, verify, tidy up. */
class AccessibilitySender(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val secrets: SecretStore,
    private val log: AutomationLog,
    notifications: Notifications,
) : MessageSender {

    private val wake = WakeUnlockController(context, log, notifications)

    override suspend fun send(job: SendJob): SendResult {
        val service = WaAccessibilityService.instance.value
            ?: return SendResult.Failed("precheck", "Accessibility service is not enabled")
        val whatsapp = installedWhatsApp() ?: return SendResult.Failed("precheck", "WhatsApp is not installed")
        val settings = settingsStore.current()
        val pin = secrets.pin()
        val secretCode = secrets.secretCode()
        val driver = UiDriver(service, log, settings.stepTimeoutMs)
        val t = settings.stepTimeoutMs
        val target = job.target
        log.info("job ${job.scheduleId} attempt ${job.attempt}: to '${target.name}' kind=${target.kind} locked=${target.isLocked} dryRun=${job.dryRun}")

        service.active.set(true)
        var unlock = com.thomaswcode.whatsappscheduler.sending.UnlockOutcome(false, false)
        try {
            withTimeout(settings.jobTimeoutMs) {
                unlock = wake.ensureUnlocked(driver, pin, t)

                val deepLink = OpenChatByDeepLink(context, driver, log)
                val search = OpenChatBySearch(context, driver, log)
                val auth = HandleAuthPrompt(driver, log)
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
                TypeAndSend(driver, log).run(job.message, job.dryRun, t)
                if (!job.dryRun) VerifySent(driver, log).run(job.message, t)
            }
            tidyUp(driver, unlock.wasLocked && settings.relockAfterSend, unlock.screenWasOff)
            log.info("job ${job.scheduleId}: ${if (job.dryRun) "dry run complete" else "sent"}")
            return if (job.dryRun) SendResult.DryRun else SendResult.Sent
        } catch (e: StepFailure) {
            runCatching { tidyUp(driver, unlock.wasLocked && settings.relockAfterSend, unlock.screenWasOff) }
            return SendResult.Failed(e.step, e.message ?: "failed", e.dump)
        } catch (e: TimeoutCancellationException) {
            val dump = runCatching { driver.dump() }.getOrNull()
            dump?.let { log.saveDump("job timeout", it) }
            runCatching { tidyUp(driver, unlock.wasLocked && settings.relockAfterSend, unlock.screenWasOff) }
            return SendResult.Failed("job", "Job exceeded ${settings.jobTimeoutMs}ms", dump)
        } finally {
            service.active.set(false)
            wake.releaseActivity()
            wake.releaseWakeLock()
        }
    }

    private suspend fun tidyUp(driver: UiDriver, relock: Boolean, screenWasOff: Boolean) {
        repeat(2) { if (driver.find(Selectors.waEntry) != null) driver.back() }
        if (relock || screenWasOff) {
            wake.relock(driver)
        } else {
            driver.home()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedWhatsApp(): String? =
        listOf(Selectors.WHATSAPP, Selectors.WHATSAPP_BUSINESS).firstOrNull { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
}
