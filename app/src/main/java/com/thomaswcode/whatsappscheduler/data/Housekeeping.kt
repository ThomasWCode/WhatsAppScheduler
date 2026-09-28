package com.thomaswcode.whatsappscheduler.data

import com.thomaswcode.whatsappscheduler.util.AutomationLog
import java.time.Duration
import java.time.Instant

/**
 * Keeps the list and the history from growing forever (quick-schedule.md §8, B28): sent one-offs go
 * after the days set in Settings, with their history kept, and history goes after [HISTORY_DAYS].
 * Runs on app start and whenever the send queue has emptied; no WorkManager needed.
 */
class Housekeeping(
    private val repository: ScheduleRepository,
    /** Settings → Sending → "Keep sent messages for"; 0 keeps them. */
    private val keepSentDays: suspend () -> Int,
    private val log: AutomationLog,
) {

    suspend fun run(now: Instant = Instant.now()) {
        val keepDays = keepSentDays()
        val cleared = if (keepDays > 0) repository.clearSent(now.minus(Duration.ofDays(keepDays.toLong()))) else 0
        repository.pruneLogs(now.minus(Duration.ofDays(HISTORY_DAYS)))
        if (cleared > 0) log.info("housekeeping: cleared $cleared sent message(s) older than $keepDays days")
    }

    companion object {
        const val HISTORY_DAYS = 90L
    }
}
