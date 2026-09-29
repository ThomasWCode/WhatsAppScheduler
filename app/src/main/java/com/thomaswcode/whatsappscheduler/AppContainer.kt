package com.thomaswcode.whatsappscheduler

import android.content.Context
import com.thomaswcode.whatsappscheduler.data.Housekeeping
import com.thomaswcode.whatsappscheduler.data.RecipientRepository
import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.contacts.ContactSearch
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.settings.CryptoBox
import com.thomaswcode.whatsappscheduler.data.settings.SecretStore
import com.thomaswcode.whatsappscheduler.data.settings.SettingsStore
import com.thomaswcode.whatsappscheduler.scheduling.AlarmScheduler
import com.thomaswcode.whatsappscheduler.sending.ChatChecker
import com.thomaswcode.whatsappscheduler.sending.AttentionActions
import com.thomaswcode.whatsappscheduler.sending.FallbackNotifier
import com.thomaswcode.whatsappscheduler.sending.HeadsUpAnswers
import com.thomaswcode.whatsappscheduler.sending.StoppedOccurrences
import com.thomaswcode.whatsappscheduler.sending.SentNotifier
import com.thomaswcode.whatsappscheduler.sending.accessibility.AccessibilitySender
import com.thomaswcode.whatsappscheduler.sending.Notifications
import com.thomaswcode.whatsappscheduler.sending.PinGuard
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import com.thomaswcode.whatsappscheduler.sending.SendService
import com.thomaswcode.whatsappscheduler.sending.SendStateHolder
import com.thomaswcode.whatsappscheduler.ui.share.ShareShortcuts
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Hand-wired dependency container; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val repository: ScheduleRepository by lazy { ScheduleRepository(database) }
    val recipients: RecipientRepository by lazy { RecipientRepository(database) }
    val contactSearch: ContactSearch by lazy { ContactSearch(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val secrets: SecretStore by lazy { SecretStore(appContext, CryptoBox()) }
    val pinGuard: PinGuard by lazy { PinGuard(secrets, Clock.systemUTC()) }

    /** What the send service is doing; outlives any one service instance so the UI can observe it. */
    val sendState = SendStateHolder()
    val notifications: Notifications by lazy { Notifications(appContext) }
    val fallbackNotifier: FallbackNotifier by lazy { FallbackNotifier(appContext, notifications) }
    val sentNotifier: SentNotifier by lazy { SentNotifier(appContext, notifications) }
    /** Taken by a send as it takes its row, and by Discard from a notification, so the two can't interleave. */
    val sendAdmission = Mutex()

    /** The recent recipients as Direct Share targets (features F6). */
    val shareShortcuts: ShareShortcuts by lazy { ShareShortcuts(appContext) }

    /** Where the countdown's buttons answer the countdown that is waiting (features F1). */
    val headsUpAnswers = HeadsUpAnswers()

    /** Batch occurrences whose Snooze or Postpone couldn't be saved, kept for alarms that come later. */
    val stoppedOccurrences = StoppedOccurrences(Clock.systemUTC())
    val attentionActions: AttentionActions by lazy {
        AttentionActions(repository, alarmScheduler, { sendState.state.value }, ::send, fallbackNotifier::cancel, admission = sendAdmission)
    }
    val log: AutomationLog by lazy { AutomationLog(appContext) }
    val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler(appContext, repository, sendState, log) { id, unlessAbout -> fallbackNotifier.cancelWillFail(id, unlessAbout) }
    }
    /** Held by whatever drives WhatsApp, a send or a chat check, so the two never overlap. */
    val automationLock = Mutex()
    val chatChecker: ChatChecker by lazy {
        ChatChecker(AccessibilitySender(appContext, settings, secrets, pinGuard, log, notifications), automationLock, recipients)
    }
    val housekeeping: Housekeeping by lazy { Housekeeping(repository, { settings.current().keepSentDays }, log) }

    /** For work that must outlive the screen that started it, e.g. alarm recovery across a rotation. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Queues a send through the foreground service. */
    fun send(request: SendRequest) = SendService.enqueue(appContext, request)

    private val linking = Mutex()

    /**
     * Links schedules from before recipients existed to recipients, once per install. It runs when the
     * app starts, whichever screen it starts on (the Share sheet too), and is awaited before a
     * recipient is removed: removing one unlinks its schedules, and linking afterwards would take them
     * for old ones and bring the recipient back.
     */
    suspend fun linkRecipientsOnce() = linking.withLock {
        if (!settings.recipientsLinked()) {
            recipients.linkUnlinkedSchedules()
            settings.markRecipientsLinked()
        }
    }

    /**
     * What a fresh start of any screen the app can be opened on (the launcher's, the Share target) owes:
     * every alarm re-armed, since Android drops them when the app is force-stopped and nothing else sets
     * them again, with an occurrence missed meanwhile handed to the send queue; once, old schedules
     * linked to recipients; and old outcomes typed. In the app's scope, so a recreate while it works
     * doesn't cancel it.
     */
    fun recoverOnOpen() {
        appScope.launch {
            runCatching { alarmScheduler.rescheduleAll() }.onFailure { log.error("rescheduleAll failed", it) }
            // Usually done already when the app started; this waits for it if not.
            runCatching { linkRecipientsOnce() }.onFailure { log.error("Linking recipients failed", it) }
            // Failures from before schema v2 show under Needs attention too.
            runCatching { repository.typeLegacyOutcomes() }.onFailure { log.error("Typing old outcomes failed", it) }
        }
    }
}
