package com.thomaswcode.whatsappscheduler.sending

import com.thomaswcode.whatsappscheduler.data.SendStore
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.data.settings.Settings
import com.thomaswcode.whatsappscheduler.domain.SendJob
import com.thomaswcode.whatsappscheduler.domain.SendResult
import com.thomaswcode.whatsappscheduler.scheduling.ScheduleAlarms
import com.thomaswcode.whatsappscheduler.util.TraceLog
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * The send queue without the Android service around it (T3). It makes three promises:
 * - a send is carried out once: duplicate requests are dropped, and a request for an occurrence
 *   the schedule has already moved past is skipped (B01);
 * - only failures from before Send was tapped are retried; anything later is reported as
 *   uncertain (B02), and a rejected PIN stops all PIN entry (B04);
 * - the outcome is recorded against the row as it is now, not as it was when the send began (B08).
 */
class SendQueueProcessor(
    private val store: SendStore,
    private val sender: MessageSender,
    private val alarms: ScheduleAlarms,
    private val notifier: SendNotifier,
    private val pinGuard: PinGuard,
    private val state: SendStateHolder,
    private val log: TraceLog,
    private val settings: suspend () -> Settings,
    private val clock: Clock,
    private val jobGuard: JobGuard = JobGuard.None,
) {
    private val lock = Any()
    private val queue = ArrayDeque<SendRequest>()
    private var current: SendRequest? = null

    /** Queues [request] unless the same send is already queued or running; returns whether it was queued. */
    fun enqueue(request: SendRequest): Boolean = synchronized(lock) {
        val key = request.dedupKey
        if (current?.dedupKey == key || queue.any { it.dedupKey == key }) {
            log.info("Schedule ${request.scheduleId}: ${request.kind} is already queued or sending; ignoring the duplicate")
            false
        } else {
            queue.addLast(request)
            publish()
            true
        }
    }

    /** Sends queued requests one at a time until the queue is empty. */
    suspend fun drain() {
        while (true) {
            val request = synchronized(lock) { queue.removeFirstOrNull()?.also { current = it; publish() } } ?: return
            try {
                val settings = settings()
                jobGuard.around(budgetMs(settings)) { send(request, settings) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log.error("Unhandled error in job ${request.scheduleId}", t)
            } finally {
                synchronized(lock) { current = null; publish() }
            }
        }
    }

    private suspend fun send(request: SendRequest, settings: Settings) {
        var schedule = store.get(request.scheduleId) ?: run {
            log.warn("Schedule ${request.scheduleId} no longer exists")
            return
        }
        val occurrence = if (request.kind.consumesOccurrence) {
            if (!schedule.enabled) {
                log.info("Schedule ${schedule.id} is switched off; skipping")
                return
            }
            pendingOccurrence(request, schedule) ?: return
        } else {
            null
        }
        val dryRun = settings.dryRun || request.kind == SendKind.TEST
        val attempts = settings.retryCount + 1
        var result: SendResult = SendResult.Failed("init", "not attempted")
        for (attempt in 1..attempts) {
            state.update { it.copy(phase = SendPhase.SENDING, attempt = attempt) }
            notifier.progress(if (attempt == 1) "Sending to ${schedule.targetName}…" else "Sending to ${schedule.targetName}, attempt $attempt of $attempts…")
            result = sender.send(SendJob(schedule.id, schedule.target, schedule.message, dryRun, attempt))
            val failed = result as? SendResult.Failed ?: break
            log.warn("Attempt $attempt failed at ${failed.step}: ${failed.reason}")
            if (!failed.retryable || attempt == attempts) break
            state.update { it.copy(phase = SendPhase.WAITING_TO_RETRY) }
            notifier.progress("Retrying ${schedule.targetName} in ${settings.retryDelaySeconds} s…")
            delay(settings.retryDelaySeconds * 1000L)
            schedule = stillWanted(request, occurrence) ?: return
        }
        finish(request, occurrence, schedule, result)
    }

    /** The occurrence [request] is for, or null (logged) when it is no longer the schedule's pending one. */
    private fun pendingOccurrence(request: SendRequest, schedule: Schedule): Long? {
        val pending = schedule.nextAtMillis
        val wanted = request.occurrenceAtMillis
            // An alarm armed before occurrence keys existed carries none: take the pending occurrence once it is due.
            ?: pending?.takeIf { it <= clock.millis() + LEGACY_ALARM_SLACK_MS }
        if (wanted == null || wanted != pending) {
            log.info("Schedule ${schedule.id}: occurrence ${stamp(wanted)} is no longer pending (next is ${stamp(pending)}); not sending it")
            return null
        }
        return wanted
    }

    /** Re-reads the schedule after a retry delay: it may have been deleted, switched off or edited meanwhile. */
    private suspend fun stillWanted(request: SendRequest, occurrence: Long?): Schedule? {
        val schedule = store.get(request.scheduleId)
        return when {
            schedule == null -> null.also { log.warn("Schedule ${request.scheduleId} was deleted while waiting to retry") }
            occurrence != null && (!schedule.enabled || schedule.nextAtMillis != occurrence) ->
                null.also { log.info("Schedule ${schedule.id} changed while waiting to retry; dropping this send") }
            else -> schedule
        }
    }

    private suspend fun finish(request: SendRequest, occurrence: Long?, schedule: Schedule, result: SendResult) {
        val (status, detail) = describe(result)
        // A manual send that failed its precheck did nothing on the phone, and the user is looking at the
        // app: it is logged, but the pending occurrence stays as it was and nothing is posted.
        val untouched = request.kind.manual && result is SendResult.Failed && result.step == SendResult.Failed.PRECHECK
        val now = clock.instant()
        val stored = if (occurrence != null && !untouched) {
            store.recordRun(schedule.id, occurrence, status, detail, schedule.targetName, now)
        } else {
            store.recordManual(schedule.id, status, detail, schedule.targetName, now)
        }
        if (result is SendResult.PinRejected) {
            pinGuard.recordRejection()
            notifier.pinPaused(pinGuard.block()?.describe() ?: result.reason)
        }
        if (stored == null) {
            log.warn("Schedule ${schedule.id} was deleted while it was being sent (${status.name})")
            return
        }
        if (occurrence != null) alarms.sync(stored)
        when (result) {
            SendResult.Sent, SendResult.DryRun -> notifier.sent(schedule, dryRun = result == SendResult.DryRun)
            is SendResult.Uncertain -> notifier.uncertain(schedule, occurrence, result.reason)
            is SendResult.Failed, is SendResult.PinRejected ->
                if (!untouched && request.kind != SendKind.TEST) notifier.failed(schedule, occurrence, detail ?: status.name)
        }
    }

    private fun describe(result: SendResult): Pair<SendStatus, String?> = when (result) {
        SendResult.Sent -> SendStatus.SENT to null
        SendResult.DryRun -> SendStatus.DRY_RUN to "composer filled, send not tapped"
        is SendResult.Failed -> SendStatus.FAILED to "${result.step}: ${result.reason}"
        is SendResult.Uncertain -> SendStatus.UNCERTAIN to "${result.step}: ${result.reason} (after Send was tapped)"
        is SendResult.PinRejected -> SendStatus.FAILED to "${result.step}: ${result.reason} (PIN not accepted; PIN entry paused)"
    }

    /** Worst case for one job: every attempt running to its timeout, verification and tidying up, plus the retry delays. */
    private fun budgetMs(settings: Settings): Long {
        val perAttempt = settings.jobTimeoutMs + settings.stepTimeoutMs + TIDY_UP_ALLOWANCE_MS
        return (settings.retryCount + 1) * perAttempt + settings.retryCount * settings.retryDelaySeconds * 1000L + MARGIN_MS
    }

    /** Must be called holding [lock]. */
    private fun publish() {
        val queued = queue.map { it.scheduleId }
        val currentId = current?.scheduleId
        state.update {
            if (currentId == null) it.copy(queuedIds = queued, currentId = null, phase = null, attempt = 0)
            else it.copy(queuedIds = queued, currentId = currentId)
        }
    }

    private fun stamp(millis: Long?): String = millis?.let { Instant.ofEpochMilli(it).toString() } ?: "none"

    companion object {
        /** How early an alarm without an occurrence key may fire and still count as due. */
        const val LEGACY_ALARM_SLACK_MS = 60_000L
        private const val TIDY_UP_ALLOWANCE_MS = 10_000L
        private const val MARGIN_MS = 60_000L
    }
}
