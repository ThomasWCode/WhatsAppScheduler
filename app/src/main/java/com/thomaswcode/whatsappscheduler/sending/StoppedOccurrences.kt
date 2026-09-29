package com.thomaswcode.whatsappscheduler.sending

import java.time.Clock
import java.time.Duration

/**
 * Batch occurrences whose Snooze or Postpone… couldn't be saved (features F1): none of their members
 * goes, however late its own alarm comes, until the occurrence is a day old. Process-wide, since a late
 * alarm may start a new send service, and with it a new queue.
 */
class StoppedOccurrences(private val clock: Clock) {
    private val stopped = mutableSetOf<Pair<Long, Long>>()

    @Synchronized
    fun stop(ids: List<Long>, occurrenceAtMillis: Long) {
        ids.forEach { stopped += it to occurrenceAtMillis }
    }

    @Synchronized
    fun isStopped(id: Long, occurrenceAtMillis: Long): Boolean {
        val staleBefore = clock.millis() - STALE_AFTER.toMillis()
        stopped.removeAll { (_, occurrence) -> occurrence < staleBefore }
        return (id to occurrenceAtMillis) in stopped
    }

    private companion object {
        val STALE_AFTER: Duration = Duration.ofDays(1)
    }
}
