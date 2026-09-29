package com.thomaswcode.whatsappscheduler.sending

import androidx.annotation.StringRes
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.data.SendStore
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.data.settings.Settings
import com.thomaswcode.whatsappscheduler.domain.PlainResult
import com.thomaswcode.whatsappscheduler.domain.SendJob
import com.thomaswcode.whatsappscheduler.domain.SendResult
import com.thomaswcode.whatsappscheduler.scheduling.ScheduleAlarms
import com.thomaswcode.whatsappscheduler.util.Texts
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
    /** Batch occurrences whose Snooze or Postpone couldn't be saved; shared by every queue in the process. */
    private val stopped: StoppedOccurrences = StoppedOccurrences(clock),
    /** What the sending notification says, from strings.xml. */
    private val texts: Texts,
) {
    private val lock = Any()
    private val queue = ArrayDeque<SendRequest>()
    private var current: SendRequest? = null

    /**
     * Batch members whose occurrence a countdown already let through, so theirs doesn't count down
     * again. Each is queued as it is let through, so the queue can't run empty, and this be cleared,
     * before it has been used.
     */
    private val approved = mutableSetOf<Pair<Long, Long>>()


    /** Queues [request] unless the same send is already queued or running; returns whether it was queued. */
    fun enqueue(request: SendRequest): Boolean = synchronized(lock) {
        val key = request.dedupKey
        if (current?.dedupKey == key || queue.any { it.dedupKey == key }) {
            log.info("Schedule ${request.scheduleId}: ${request.kind} is already queued or sending; ignoring the duplicate")
            false
        } else {
            queue.addLast(request.copy(receivedAtMillis = request.receivedAtMillis ?: clock.millis()))
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
        // Let through by its batch's countdown (features F1): it doesn't count down again, and however
        // long that countdown, and the batch before it, took isn't lateness.
        val letThrough = request.kind == SendKind.SCHEDULED && occurrence != null && approved.remove(schedule.id to occurrence)
        if (request.kind == SendKind.SCHEDULED && occurrence != null && !letThrough) {
            // Decided here, in the queue, rather than where alarms are re-armed: an alarm delivered at
            // the same moment as a re-arm then can't turn into both a send and a "missed" (B09 interim).
            // Measured to when the queue took it: waiting behind other sends isn't the alarm being late.
            val late = Duration.ofMillis((request.receivedAtMillis ?: clock.millis()) - occurrence)
            if (late > Duration.ofMinutes(settings.missedGraceMinutes.toLong())) {
                recordMissed(schedule, occurrence, late)
                return
            }
        }
        if (request.kind == SendKind.SCHEDULED && occurrence != null) {
            if (stopped.isStopped(schedule.id, occurrence)) {
                log.info("Schedule ${schedule.id}: its batch couldn't be snoozed or held; not sending it")
                return
            }
            // Not "Sending…" yet: it may be snoozed or held without a send ever starting.
            state.update { it.copy(phase = SendPhase.WAITING, attempt = 0) }
            if (!countdown(schedule, occurrence, settings, letThrough)) return
            // The countdown, or a call before it, may have taken a while: the row as it is now.
            schedule = stillWanted(request, occurrence) ?: return
        }
        val dryRun = settings.dryRun || request.kind == SendKind.TEST
        val attempts = settings.retryCount + 1
        var result: SendResult = SendResult.Failed("init", "not attempted")
        for (attempt in 1..attempts) {
            state.update { it.copy(phase = SendPhase.SENDING, attempt = attempt) }
            notifier.progress(
                if (attempt == 1) texts.get(R.string.notif_progress_sending, schedule.targetName)
                else texts.get(R.string.notif_progress_attempt, schedule.targetName, attempt, attempts),
            )
            result = sender.send(SendJob(schedule.id, schedule.target, schedule.message, dryRun, attempt))
            val failed = result as? SendResult.Failed ?: break
            log.warn("Attempt $attempt failed at ${failed.step}: ${failed.reason}")
            if (!failed.retryable || attempt == attempts) break
            state.update { it.copy(phase = SendPhase.WAITING_TO_RETRY) }
            notifier.progress(texts.get(R.string.notif_progress_retrying, schedule.targetName, settings.retryDelaySeconds))
            delay(settings.retryDelaySeconds * 1000L)
            schedule = stillWanted(request, occurrence) ?: return
            // A call that began meanwhile isn't interrupted: the send waits five minutes, and counts down again.
            if (request.kind == SendKind.SCHEDULED && occurrence != null && headsUp.inCall()) {
                log.info("Schedule ${schedule.id}: a call began before attempt ${attempt + 1}; snoozing")
                snooze(listOf(schedule), occurrence)
                return
            }
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
    private suspend fun countdown(schedule: Schedule, occurrence: Long, settings: Settings, letThrough: Boolean): Boolean {
        // Shared by every wait below, so together they can hold a send up for half an hour at most.
        var looks = 0

        /**
         * Waits while [condition] holds, looking every 15 s; returns whether it still did when time ran out.
         * [what] is for the trace, [progress] what the sending notification says meanwhile.
         */
        suspend fun waitWhile(what: String, @StringRes progress: Int, condition: () -> Boolean): Boolean {
            var waiting = false
            while (condition()) {
                if (looks >= CALL_LOOKS) return true
                if (!waiting) {
                    log.info("Schedule ${schedule.id}: waiting for $what")
                    notifier.progress(texts.get(progress))
                    waiting = true
                }
                delay(CALL_LOOK_MS)
                looks++
            }
            return false
        }

        /**
         * Waits out a call, which a send never interrupts: one still going on after the half hour
         * snoozes the batch, to wait again then. Returns whether the send may go on.
         */
        suspend fun callOver(): Boolean {
            if (!waitWhile("the call to end", R.string.notif_progress_waiting_call) { headsUp.inCall() }) return true
            log.info("Schedule ${schedule.id}: still in a call after half an hour; snoozing")
            snooze(store.duePeers(schedule, occurrence), occurrence)
            return false
        }

        // Let through by its batch's countdown: only a call since then holds it up.
        if (letThrough) return callOver()
        repeat(MAX_COUNTDOWNS) {
            if (!callOver()) return false
            if (!headsUp.inUse()) return true
            val batch = store.duePeers(schedule, occurrence)
            if (!headsUp.canShow()) {
                // With notifications off or no pop-up for Before sending, nobody would see a countdown, so
                // its running out can't be taken as a yes: the send waits for the phone to be put down
                // instead, and if that doesn't happen in the half hour, for the user to choose a time.
                log.info("Schedule ${schedule.id}: the countdown can't be shown (its notifications are off or don't pop up)")
                if (waitWhile("the phone to be put down", R.string.notif_progress_waiting_put_down) { headsUp.inUse() || headsUp.inCall() }) {
                    // Quietly: a picker opening over what the user is doing would be a takeover too.
                    hold(batch, occurrence, HELD_UNSEEN, pick = false)
                    return false
                }
                approve(schedule, batch, occurrence)
                return true
            }
            val choice = headsUp.ask(batch, clock.millis() + settings.headsUpSeconds * 1_000L) ?: HeadsUpChoice.SEND
            log.info("Schedule ${schedule.id}: countdown ${choice.name.lowercase()}" + if (batch.size > 1) " for ${batch.size} chats" else "")
            when (choice) {
                HeadsUpChoice.SEND -> {
                    // A call that began during the countdown is waited out, and the countdown runs again:
                    // the user may not have seen this one.
                    if (headsUp.inCall()) return@repeat
                    approve(schedule, batch, occurrence)
                    return true
                }
                HeadsUpChoice.SNOOZE -> {
                    snooze(batch, occurrence)
                    return false
                }
                HeadsUpChoice.POSTPONE -> {
                    // Not opened over a call that began meanwhile: the held notification offers it then.
                    hold(batch, occurrence, HELD_POSTPONED, pick = !headsUp.inCall())
                    return false
                }
            }
        }
        // A call cut every countdown short. Sending the moment the last one ends would take over the
        // screen with no countdown, so it's snoozed: in five minutes it counts down again.
        log.info("Schedule ${schedule.id}: calls cut short every countdown; snoozing")
        snooze(store.duePeers(schedule, occurrence), occurrence)
        return false
    }

    /**
     * The rest of [schedule]'s batch goes as well, without counting down again. They're queued now
     * rather than left to their own alarms, which could come after this queue had emptied and the
     * approval had gone with it.
     */
    private fun approve(schedule: Schedule, batch: List<Schedule>, occurrence: Long) {
        batch.filter { it.id != schedule.id }.forEach { member ->
            approved += member.id to occurrence
            enqueue(SendRequest(member.id, SendKind.SCHEDULED, occurrence))
        }
    }

    /**
     * Snooze: one time for them all, so the batch's alarms still come due together and share the next
     * countdown. One whose alarm can't be set is held for the user instead, where it can be seen,
     * rather than left waiting for an alarm that won't come.
     */
    private suspend fun snooze(batch: List<Schedule>, occurrence: Long) {
        val now = clock.instant()
        val to = now.plus(SNOOZE)
        val moved = saved(batch, occurrence, "Snooze") { store.snooze(batch.map { it.id }, occurrence, to, now) } ?: return
        val unarmed = moved.filterNot(::arm)
        if (unarmed.isNotEmpty()) hold(unarmed, to.toEpochMilli(), HELD_UNARMED, pick = false)
    }

    /**
     * Saves what the user chose for [batch] with [write]. If that fails nothing moved, and the batch
     * would still be due: none of it goes now, as the user asked, and each is reported as not sent,
     * with the ways to send it or pick a time. Null then.
     */
    private suspend fun <T> saved(batch: List<Schedule>, occurrence: Long, what: String, write: suspend () -> T): T? = try {
        write()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("$what for schedule(s) ${batch.map { it.id }} couldn't be saved", e)
        stopped.stop(batch.map { it.id }, occurrence)
        batch.forEach { member -> notifier.failed(member, occurrence, "precheck: $what couldn't be saved (${e.message ?: e.javaClass.simpleName})") }
        null
    }

    /**
     * Holds [batch]'s [occurrence] until the user picks a time: "Not sent: choose a new time", for each.
     * After Postpone… ([pick]) one picker opens for them all, as the countdown was; otherwise nothing
     * opens over what the user is doing.
     */
    private suspend fun hold(batch: List<Schedule>, occurrence: Long, detail: String, pick: Boolean) {
        val runs = saved(batch, occurrence, "Postpone") { store.hold(batch.map { it.id }, occurrence, clock.instant(), detail) } ?: return
        runs.forEach { run ->
            // A series goes on to its next time; if that can't be armed, its next message wouldn't go by
            // itself, and nothing would say so.
            if (!arm(run.schedule) && run.schedule.id != run.held.id) notifier.unarmed(run.schedule)
        }
        if (runs.isNotEmpty()) notifier.held(runs.map { it.held }, pick)
    }

    /** Arms [schedule]'s alarm, and says whether that worked; one that fails is logged, not allowed to stop the rest. */
    private fun arm(schedule: Schedule): Boolean =
        runCatching { alarms.sync(schedule) }.onFailure { log.error("Arming schedule ${schedule.id} failed", it) }.isSuccess

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
            // A run by hand that uses up the occurrence (Send now) is marked in the log as recordManual's are.
            val logged = if (request.kind.manual) PlainResult.MANUAL + detail.orEmpty() else detail
            store.recordRun(schedule.id, occurrence, status, logged, schedule.targetName, now)
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
        /**
         * During a call, or while the phone is in use with no countdown to show, a scheduled send looks
         * again this often, and this many times: half an hour.
         */
        private const val CALL_LOOK_MS = 15_000L
        private const val CALL_LOOKS = 120
        /** A countdown a call cuts short runs again once the call is over, this many times at most. */
        private const val MAX_COUNTDOWNS = 3
        private val SNOOZE: Duration = Duration.ofMinutes(5)
        private const val HELD_POSTPONED = "held for a new time: Postpone… on the countdown"
        private const val HELD_UNSEEN = "held for a new time: no countdown could be shown, and the phone was in use for half an hour"
        private const val HELD_UNARMED = "held for a new time: the alarm for its snoozed time couldn't be set"
        /** A check backs out of WhatsApp in at most six tries of a two-second look and a Back press. */
        private const val CHECK_EXIT_ALLOWANCE_MS = 20_000L
        private const val MARGIN_MS = 60_000L
    }
}
