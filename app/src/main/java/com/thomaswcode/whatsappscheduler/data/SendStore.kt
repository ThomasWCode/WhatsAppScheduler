package com.thomaswcode.whatsappscheduler.data

import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import java.time.Instant

/** The slice of [ScheduleRepository] the send queue needs; JVM tests stand in a fake for it. */
interface SendStore {

    suspend fun get(id: Long): Schedule?

    /**
     * Logs the outcome of sending the occurrence due at [occurrenceAtMillis] and moves the schedule
     * past it, if the row still shows that occurrence. Returns the row as stored, or null when the
     * schedule has been deleted (the outcome is still logged).
     */
    suspend fun recordRun(id: Long, occurrenceAtMillis: Long, status: SendStatus, detail: String?, sentTo: String, ranAt: Instant): Schedule?

    /** Logs a send that was not for the pending occurrence; the next occurrence is left alone. */
    suspend fun recordManual(id: Long, status: SendStatus, detail: String?, sentTo: String, ranAt: Instant): Schedule?
}
