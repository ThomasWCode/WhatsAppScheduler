package com.thomaswcode.whatsappscheduler.scheduling

import com.thomaswcode.whatsappscheduler.data.db.Schedule

/** Arms or clears a schedule's alarm to match its stored state; [AlarmScheduler] in the app. */
interface ScheduleAlarms {
    fun sync(schedule: Schedule)
}
