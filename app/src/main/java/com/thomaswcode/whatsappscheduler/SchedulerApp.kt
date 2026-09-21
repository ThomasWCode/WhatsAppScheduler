package com.thomaswcode.whatsappscheduler

import android.app.Application

class SchedulerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.ensureChannels()
    }

    companion object {
        /** Returns the app-wide container from any context. */
        fun container(context: android.content.Context): AppContainer =
            (context.applicationContext as SchedulerApp).container
    }
}
