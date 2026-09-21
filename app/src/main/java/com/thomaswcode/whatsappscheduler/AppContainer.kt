package com.thomaswcode.whatsappscheduler

import android.content.Context
import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.db.AppDatabase
import com.thomaswcode.whatsappscheduler.data.settings.CryptoBox
import com.thomaswcode.whatsappscheduler.data.settings.SecretStore
import com.thomaswcode.whatsappscheduler.data.settings.SettingsStore
import com.thomaswcode.whatsappscheduler.scheduling.AlarmScheduler
import com.thomaswcode.whatsappscheduler.sending.FallbackNotifier
import com.thomaswcode.whatsappscheduler.sending.Notifications
import com.thomaswcode.whatsappscheduler.util.AutomationLog

/** Hand-wired dependency container; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val repository: ScheduleRepository by lazy { ScheduleRepository(database.scheduleDao(), database.sendLogDao()) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val secrets: SecretStore by lazy { SecretStore(appContext, CryptoBox()) }
    val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler(appContext, repository, settings) }
    val notifications: Notifications by lazy { Notifications(appContext) }
    val fallbackNotifier: FallbackNotifier by lazy { FallbackNotifier(appContext, notifications) }
    val log: AutomationLog by lazy { AutomationLog(appContext) }
}
