package com.thomaswcode.whatsappscheduler.sending

import com.thomaswcode.whatsappscheduler.data.db.Schedule

/** Everything the send queue tells the user, behind an interface so JVM tests can record it. */
interface SendNotifier {

    /** Text of the ongoing "sending" notification. */
    fun progress(text: String)

    /**
     * The message went out, or a dry run finished. After a real send any prompt for this schedule is
     * stale and removed (B10); after a dry run it stays, because nothing was sent.
     */
    fun sent(schedule: Schedule, dryRun: Boolean)

    /** Automation failed before Send was tapped: offer the one-tap manual send. */
    fun failed(schedule: Schedule, occurrenceAtMillis: Long?, reason: String)

    /** Something failed after Send was tapped: ask the user to check the chat, never to send again (B02). */
    fun uncertain(schedule: Schedule, occurrenceAtMillis: Long?, reason: String)

    /** The stored PIN did not unlock the phone, so PIN entry is paused until it is saved again (B04). */
    fun pinPaused(reason: String)

    /** An occurrence came due too late to send (the alarm could not fire in time); offer the manual send. */
    fun missed(schedule: Schedule, occurrenceAtMillis: Long)

    /**
     * Held until the user picks a time (Postpone… on the countdown, features F1): one Postpone picker
     * opens for all of [schedules] (a batch shares its countdown), and each keeps "Not sent: choose a
     * new time" if the picker is dismissed.
     */
    fun held(schedules: List<Schedule>, pick: Boolean)

    /** [schedule]'s alarm couldn't be set, so its next message won't go by itself unless it is set again. */
    fun unarmed(schedule: Schedule)
}

/** Runs around each job. The service holds a partial wake lock sized to the job's worst case (B12). */
interface JobGuard {
    suspend fun around(budgetMs: Long, block: suspend () -> Unit)

    object None : JobGuard {
        override suspend fun around(budgetMs: Long, block: suspend () -> Unit) = block()
    }
}
