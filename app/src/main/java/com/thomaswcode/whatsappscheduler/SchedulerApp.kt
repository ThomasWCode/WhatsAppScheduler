package com.thomaswcode.whatsappscheduler

import android.app.Application
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class SchedulerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.ensureChannels()
        // Before any screen can remove a recipient (see AppContainer.linkRecipientsOnce).
        container.appScope.launch {
            runCatching { container.linkRecipientsOnce() }.onFailure { container.log.error("Linking recipients failed", it) }
        }
        // The share sheet's Direct Share targets follow the recent recipients (features F6).
        container.appScope.launch {
            runCatching {
                container.recipients.observeRecent().distinctUntilChanged().collect { container.shareShortcuts.publish(it) }
            }.onFailure { container.log.error("Publishing Direct Share targets failed", it) }
        }
    }

    companion object {
        /** Returns the app-wide container from any context. */
        fun container(context: android.content.Context): AppContainer =
            (context.applicationContext as SchedulerApp).container
    }
}
