package com.thomaswcode.whatsappscheduler

import android.content.Context
import com.thomaswcode.whatsappscheduler.data.RecipientRepository
import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.contacts.ContactSearch
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.settings.CryptoBox
import com.thomaswcode.whatsappscheduler.data.settings.SecretStore
import com.thomaswcode.whatsappscheduler.data.settings.SettingsStore
import com.thomaswcode.whatsappscheduler.scheduling.AlarmScheduler
import com.thomaswcode.whatsappscheduler.sending.FallbackNotifier
import com.thomaswcode.whatsappscheduler.sending.Notifications
import com.thomaswcode.whatsappscheduler.sending.PinGuard
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import com.thomaswcode.whatsappscheduler.sending.SendService
import com.thomaswcode.whatsappscheduler.sending.SendStateHolder
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val log: AutomationLog by lazy { AutomationLog(appContext) }
    val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler(appContext, repository, sendState, log) }

    /** For work that must outlive the screen that started it, e.g. alarm recovery across a rotation. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Queues a send through the foreground service. */
    fun send(request: SendRequest) = SendService.enqueue(appContext, request)
}
