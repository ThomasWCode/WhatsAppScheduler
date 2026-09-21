package com.thomaswcode.whatsappscheduler.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.SchedulerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-arms alarms after reboot, app update or clock/zone changes; alarms do not survive these. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val accepted = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
        if (intent.action !in accepted) return
        val container = SchedulerApp.container(context)
        container.log.info("Rescheduling all alarms after ${intent.action}")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                container.alarmScheduler.rescheduleAll()
            } catch (t: Throwable) {
                container.log.error("rescheduleAll failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
