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
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    /** Shared with the attention notifications' Discard (AttentionActions), so the two can't interleave. */
    private val admission: Mutex = Mutex(),
    /** The countdown before a scheduled send while the phone is in use (features F1). */
    private val headsUp: HeadsUp = HeadsUp.None,
) {
    private val lock = Any()
    private val queue = ArrayDeque<SendRequest>()
    private var current: SendRequest? = null

    /** Batch members whose occurrence a countdown already let through, so theirs doesn't count down again. */
    private val approved = mutableSetOf<Pair<Long, Long>>()

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
            val request = synchronized(lock) { queue.removeFirstOrNull()?.also { current = it; publish() } } ?: run {
                approved.clear()
                return
            }
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
        // Taken while holding [admission]: the request is published as busy by now, so a discard either
        // finished first (the row is gone) or will see it busy and leave it.
        var schedule = admission.withLock { store.get(request.scheduleId) } ?: run {
            log.warn("Schedule ${request.scheduleId} no longer exists")
            return
        }
        val occurrence = if (request.kind.consumesOccurrence) {
            // Only the alarm defers to the switch; Send now on a switched-off schedule is a deliberate
            // request, and must use up the occurrence so switching it back on can't send it again.
            if (request.kind == SendKind.SCHEDULED && !schedule.enabled) {
                log.info("Schedule ${schedule.id} is switched off; skipping")
                return
            }
            pendingOccurrence(request, schedule) ?: return
        } else {
            null
        }
        if (request.kind == SendKind.SCHEDULED && occurrence != null) {
            // Decided here, in the queue, rather than where alarms are re-armed: an alarm delivered at
            // the same moment as a re-arm then can't turn into both a send and a "missed" (B09 interim).
            val late = Duration.ofMillis(clock.millis() - occurrence)
            if (late > Duration.ofMinutes(settings.missedGraceMinutes.toLong())) {
                recordMissed(schedule, occurrence, late)
                return
            }
        }
        if (request.kind == SendKind.SCHEDULED && occurrence != null) {
            if (!countdown(schedule, occurrence, settings)) return
            // The countdown, or a call before it, may have taken a while: the row as it is now.
            schedule = stillWanted(request, occurrence) ?: return
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
        finish(request, occurrence, schedule, result, dryRun)
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

    /**
     * An occurrence whose alarm could not fire in time (phone off, app force-stopped, clock changed)
     * and is now past the grace window: it is not sent, and never skipped silently either (B09). It is
     * held for the user, who is asked: Send now, Postpone… or Discard.
     */
    private suspend fun recordMissed(schedule: Schedule, occurrence: Long, late: Duration) {
        log.info("Schedule ${schedule.id}: occurrence ${stamp(occurrence)} is ${late.toMinutes()} min late; holding it")
        val missed = store.recordMissed(schedule.id, occurrence, "missed by ${late.toMinutes()} min (phone off?)", schedule.targetName, clock.instant())
            ?: return
        alarms.sync(missed.schedule)
        notifier.missed(missed.held, occurrence)
    }

    /**
     * Before a scheduled send while the phone is in use, a countdown (features F1) with Send now,
     * Snooze 5 min and Postpone…, which a batch's members due together share. During a call the send
     * waits first, looking again every 15 s for up to half an hour. Returns whether to send now.
     */
    private suspend fun countdown(schedule: Schedule, occurrence: Long, settings: Settings): Boolean {
        // Shared by every wait below, so calls can hold a send up for half an hour in all.
        var looks = 0
        suspend fun waitOutCall() {
            while (headsUp.inCall() && looks < CALL_LOOKS) {
                if (looks == 0) {
                    log.info("Schedule ${schedule.id}: waiting for the call to end")
                    notifier.progress("Waiting for the call to end…")
                }
                delay(CALL_LOOK_MS)
                looks++
            }
        }
        // Let through by its batch's countdown: only a call since then holds it up.
        if (approved.remove(schedule.id to occurrence)) {
            waitOutCall()
            return true
        }
        repeat(MAX_COUNTDOWNS) {
            waitOutCall()
            if (!headsUp.inUse()) return true
            val batch = store.duePeers(schedule, occurrence)
            val choice = headsUp.ask(batch, clock.millis() + settings.headsUpSeconds * 1_000L) ?: HeadsUpChoice.SEND
            log.info("Schedule ${schedule.id}: countdown ${choice.name.lowercase()}" + if (batch.size > 1) " for ${batch.size} chats" else "")
            when (choice) {
                HeadsUpChoice.SEND -> {
                    // A call that began during the countdown is waited out, and the countdown runs again:
                    // the user may not have seen this one.
                    if (headsUp.inCall() && looks < CALL_LOOKS) return@repeat
                    batch.filter { it.id != schedule.id }.forEach { approved += it.id to occurrence }
                    return true
                }
                HeadsUpChoice.SNOOZE -> {
                    batch.forEach { member ->
                        store.snooze(member.id, occurrence, clock.instant().plus(SNOOZE), clock.instant())?.let(alarms::sync)
                    }
                    return false
                }
                HeadsUpChoice.POSTPONE -> {
                    val held = batch.mapNotNull { member ->
                        store.hold(member.id, occurrence, clock.instant())?.also { alarms.sync(it.schedule) }?.held
                    }
                    // One picker for them all, as the countdown was.
                    if (held.isNotEmpty()) notifier.held(held)
                    return false
                }
            }
        }
        // Every countdown was cut short by a call, now over or waited out as long as allowed.
        return true
    }

    /** Re-reads the schedule after a retry delay: it may have been deleted, switched off or edited meanwhile. */
    private suspend fun stillWanted(request: SendRequest, occurrence: Long?): Schedule? {
        val schedule = store.get(request.scheduleId)
        return when {
            schedule == null -> null.also { log.warn("Schedule ${request.scheduleId} was deleted while waiting to retry") }
            occurrence != null && ((request.kind == SendKind.SCHEDULED && !schedule.enabled) || schedule.nextAtMillis != occurrence) ->
                null.also { log.info("Schedule ${schedule.id} changed while waiting to retry; dropping this send") }
            else -> schedule
        }
    }

    private suspend fun finish(request: SendRequest, occurrence: Long?, schedule: Schedule, result: SendResult, dryRun: Boolean) {
        if (result is SendResult.PinRejected) {
            // Before anything that can fail or be cancelled: this record is what stops the same wrong
            // PIN being typed again when the occurrence is retried later (B04).
            withContext(NonCancellable) { pinGuard.recordRejection() }
            notifier.pinPaused(pinGuard.block()?.describe() ?: result.reason)
        }
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
        if (stored == null) {
            log.warn("Schedule ${schedule.id} was deleted while it was being sent (${status.name})")
            return
        }
        // After every kind of run, not only those that used up an occurrence: re-arming skips a
        // schedule while any request for it is in flight, so this is where its alarm is restored.
        alarms.sync(stored)
        when (result) {
            SendResult.Sent, SendResult.DryRun -> notifier.sent(schedule, dryRun = result == SendResult.DryRun)
            is SendResult.Uncertain -> notifier.uncertain(schedule, occurrence, result.reason)
            // A dry run (Test, or the global setting) never offers a manual send: its prompt would
            // invite a real message during what was meant as a rehearsal.
            is SendResult.Failed, is SendResult.PinRejected ->
                if (!untouched && !dryRun) notifier.failed(schedule, occurrence, detail ?: status.name)
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
        // Each attempt may first wait for a chat check holding the automation lock (LockedSender): as
        // long as a check may take, and its backing out of WhatsApp.
        val checkWait = settings.jobTimeoutMs + CHECK_EXIT_ALLOWANCE_MS
        val perAttempt = checkWait + settings.jobTimeoutMs + settings.stepTimeoutMs + TIDY_UP_ALLOWANCE_MS
        // Before the first attempt, a call may be waited out and the countdown run (features F1).
        val beforeSending = CALL_LOOKS * CALL_LOOK_MS + MAX_COUNTDOWNS * settings.headsUpSeconds * 1000L
        return beforeSending + (settings.retryCount + 1) * perAttempt + settings.retryCount * settings.retryDelaySeconds * 1000L + MARGIN_MS
    }

    /** Must be called holding [lock]. */
    private fun publish() {
        val queued = queue.map { it.scheduleId }
        val currentId = current?.scheduleId
        val holders = (queue + listOfNotNull(current)).filter { it.kind.consumesOccurrence }.map { it.scheduleId }.toSet()
        state.update {
            if (currentId == null) it.copy(queuedIds = queued, currentId = null, phase = null, attempt = 0, occurrenceHolderIds = holders)
            else it.copy(queuedIds = queued, currentId = currentId, occurrenceHolderIds = holders)
        }
    }

    private fun stamp(millis: Long?): String = millis?.let { Instant.ofEpochMilli(it).toString() } ?: "none"

    companion object {
        /** How early an alarm without an occurrence key may fire and still count as due. */
        const val LEGACY_ALARM_SLACK_MS = 60_000L
        private const val TIDY_UP_ALLOWANCE_MS = 10_000L
        /** During a call a scheduled send looks again this often, and this many times: half an hour. */
        private const val CALL_LOOK_MS = 15_000L
        private const val CALL_LOOKS = 120
        /** A countdown a call cuts short runs again once the call is over, this many times at most. */
        private const val MAX_COUNTDOWNS = 3
        private val SNOOZE: Duration = Duration.ofMinutes(5)
        /** A check backs out of WhatsApp in at most six tries of a two-second look and a Back press. */
        private const val CHECK_EXIT_ALLOWANCE_MS = 20_000L
        private const val MARGIN_MS = 60_000L
    }
}
