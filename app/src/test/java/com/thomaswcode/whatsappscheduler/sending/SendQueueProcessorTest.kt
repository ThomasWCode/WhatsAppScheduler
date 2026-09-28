package com.thomaswcode.whatsappscheduler.sending

import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.SendStore
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.data.settings.Settings
import com.thomaswcode.whatsappscheduler.domain.PinBlock
import com.thomaswcode.whatsappscheduler.domain.PinFailures
import com.thomaswcode.whatsappscheduler.domain.SendJob
import com.thomaswcode.whatsappscheduler.domain.SendResult
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.scheduling.ScheduleAlarms
import com.thomaswcode.whatsappscheduler.util.TraceLog
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendQueueProcessorTest {

    private val due: Instant = Instant.parse("2026-09-29T08:00:00Z")
    private val clock = TestClock(due.plusSeconds(5))
    private val store = FakeStore()
    private val alarms = FakeAlarms()
    private val notifier = FakeNotifier()
    private val pinStore = FakePinStore()
    private val state = SendStateHolder()
    private val log = FakeLog()

    private fun processor(sender: MessageSender, settings: Settings = Settings(retryCount = 2, retryDelaySeconds = 30)) =
        SendQueueProcessor(store, sender, alarms, notifier, PinGuard(pinStore, clock), state, log, { settings }, clock)

    private fun oneOff(id: Long = 1, at: Instant = due) = schedule(id, at, "ONCE")

    private fun daily(id: Long = 1, at: Instant = due) = schedule(id, at, "DAILY")

    private fun schedule(id: Long, at: Instant, recurrence: String) = Schedule(
        id = id, message = "Happy birthday", targetKind = TargetKind.CONTACT, targetName = "Mum",
        phoneE164 = "+447700900123", isLocked = false, firstAtMillis = at.toEpochMilli(), zoneId = "Europe/London",
        recurrence = recurrence, nextAtMillis = at.toEpochMilli(),
    )

    private fun scheduled(id: Long = 1, at: Instant = due) = SendRequest(id, SendKind.SCHEDULED, at.toEpochMilli())

    // B01

    @Test
    fun sameOccurrenceEnqueuedTwiceIsSentOnce() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val queue = processor(sender)
        assertTrue(queue.enqueue(scheduled()))
        assertFalse(queue.enqueue(scheduled()))
        queue.drain()
        assertEquals(1, sender.jobs.size)
    }

    @Test
    fun duplicateArrivingWhileTheSendRunsIsDropped() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val queue = processor(sender)
        var accepted: Boolean? = null
        sender.onSend = { accepted = queue.enqueue(scheduled()) }
        queue.enqueue(scheduled())
        queue.drain()
        assertEquals(false, accepted)
        assertEquals(1, sender.jobs.size)
    }

    @Test
    fun requestForAnOccurrenceAlreadySentIsSkipped() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val queue = processor(sender)
        queue.enqueue(scheduled())
        queue.drain()
        assertNull(store.row(1).nextAtMillis)
        assertTrue(queue.enqueue(scheduled()))
        queue.drain()
        assertEquals(1, sender.jobs.size)
        assertEquals(listOf(SendStatus.SENT), store.logged)
    }

    @Test
    fun manualRequestIsNotBlockedByAScheduledOne() = runTest {
        store.put(daily())
        val sender = FakeSender()
        val queue = processor(sender)
        assertTrue(queue.enqueue(scheduled()))
        assertTrue(queue.enqueue(SendRequest(1, SendKind.EXTRA_COPY)))
        queue.drain()
        assertEquals(2, sender.jobs.size)
        // Only the scheduled send moves the series on, by exactly one day.
        assertEquals(due.plus(Duration.ofDays(1)).toEpochMilli(), store.row(1).nextAtMillis)
    }

    @Test
    fun alarmWithoutAnOccurrenceKeySendsTheDueOccurrenceOnce() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val queue = processor(sender)
        queue.enqueue(SendRequest(1, SendKind.SCHEDULED))
        queue.drain()
        queue.enqueue(SendRequest(1, SendKind.SCHEDULED))
        queue.drain()
        assertEquals(1, sender.jobs.size)
    }

    @Test
    fun alarmWithoutAnOccurrenceKeyIgnoresAFutureOccurrence() = runTest {
        store.put(oneOff(at = due.plus(Duration.ofHours(3))))
        val sender = FakeSender()
        processor(sender).run { enqueue(SendRequest(1, SendKind.SCHEDULED)); drain() }
        assertTrue(sender.jobs.isEmpty())
    }

    @Test
    fun switchedOffScheduleIsSkippedButCanStillBeSentByHand() = runTest {
        store.put(daily().copy(enabled = false))
        val sender = FakeSender()
        val queue = processor(sender)
        queue.enqueue(scheduled())
        queue.drain()
        assertTrue(sender.jobs.isEmpty())
        queue.enqueue(SendRequest(1, SendKind.EXTRA_COPY))
        queue.drain()
        assertEquals(1, sender.jobs.size)
    }

    @Test
    fun stateShowsTheScheduleAsBusyWhileItIsSent() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val queue = processor(sender)
        var busyDuringSend = false
        sender.onSend = { busyDuringSend = state.state.value.isBusy(1) }
        queue.enqueue(scheduled())
        assertTrue(state.state.value.isBusy(1))
        queue.drain()
        assertTrue(busyDuringSend)
        assertFalse(state.state.value.isBusy(1))
    }

    @Test
    fun onlyRequestsThatUseUpTheOccurrenceHoldItFromReArming() = runTest {
        store.put(daily())
        val sender = FakeSender()
        val queue = processor(sender)
        val seen = mutableListOf<Pair<SendKind, Boolean>>()
        sender.onSend = { job -> seen += (if (job.dryRun) SendKind.TEST else SendKind.SCHEDULED) to state.state.value.holdsOccurrence(1) }
        queue.enqueue(SendRequest(1, SendKind.TEST))
        queue.drain()
        queue.enqueue(scheduled())
        queue.drain()
        assertEquals(listOf(SendKind.TEST to false, SendKind.SCHEDULED to true), seen)
    }

    @Test
    fun everyFinishedRunReArmsTheAlarm() = runTest {
        // Re-arming skips a schedule while it is busy, so a test run or a copy must restore the alarm itself.
        store.put(daily())
        processor(FakeSender()).run {
            enqueue(SendRequest(1, SendKind.TEST)); drain()
            enqueue(SendRequest(1, SendKind.EXTRA_COPY)); drain()
        }
        assertEquals(2, alarms.synced.size)
        assertTrue(alarms.synced.all { it.nextAtMillis == due.toEpochMilli() })
    }

    // Missed occurrences (B09 interim): decided in the queue, so a re-arm and a late alarm agree

    @Test
    fun occurrenceLateBeyondTheGraceWindowIsReportedMissedNotSent() = runTest {
        store.put(daily())
        clock.now = due.plus(Duration.ofHours(3))
        val sender = FakeSender()
        processor(sender, Settings(missedGraceMinutes = 120)).run { enqueue(scheduled()); drain() }
        assertTrue(sender.jobs.isEmpty())
        assertEquals(listOf(SendStatus.SKIPPED), store.logged)
        assertEquals(listOf("missed Mum"), notifier.events)
        assertEquals(due.plus(Duration.ofDays(1)).toEpochMilli(), store.row(1).nextAtMillis)
    }

    @Test
    fun occurrenceLateWithinTheGraceWindowIsSent() = runTest {
        store.put(oneOff())
        clock.now = due.plus(Duration.ofMinutes(90))
        val sender = FakeSender()
        processor(sender, Settings(missedGraceMinutes = 120)).run { enqueue(scheduled()); drain() }
        assertEquals(1, sender.jobs.size)
        assertEquals(listOf(SendStatus.SENT), store.logged)
    }

    @Test
    fun sendNowIsNeverTooLate() = runTest {
        store.put(oneOff())
        clock.now = due.plus(Duration.ofHours(3))
        val sender = FakeSender()
        processor(sender, Settings(missedGraceMinutes = 120)).run { enqueue(SendRequest(1, SendKind.SEND_NOW, due.toEpochMilli())); drain() }
        assertEquals(1, sender.jobs.size)
    }

    // B02 and retries

    @Test
    fun failuresBeforeTheTapAreRetried() = runTest {
        store.put(oneOff())
        val sender = FakeSender(failed("search"), failed("search"), SendResult.Sent)
        processor(sender).run { enqueue(scheduled()); drain() }
        assertEquals(listOf(1, 2, 3), sender.jobs.map { it.attempt })
        assertEquals(listOf(SendStatus.SENT), store.logged)
        assertEquals(listOf("sent Mum"), notifier.events)
    }

    @Test
    fun lastFailureOffersTheManualSend() = runTest {
        store.put(oneOff())
        val sender = FakeSender(failed("search"), failed("search"), failed("search"))
        processor(sender).run { enqueue(scheduled()); drain() }
        assertEquals(3, sender.jobs.size)
        assertEquals(listOf(SendStatus.FAILED), store.logged)
        assertEquals(listOf("failed Mum"), notifier.events)
    }

    @Test
    fun uncertainResultIsNeverRetried() = runTest {
        store.put(oneOff())
        val sender = FakeSender(SendResult.Uncertain("verify", "Composer still holds the text"))
        processor(sender).run { enqueue(scheduled()); drain() }
        assertEquals(1, sender.jobs.size)
        assertEquals(listOf(SendStatus.UNCERTAIN), store.logged)
        assertEquals(listOf("uncertain Mum"), notifier.events)
        assertNull("the occurrence is used up", store.row(1).nextAtMillis)
    }

    @Test
    fun failureThatCannotBeHelpedByWaitingIsNotRetried() = runTest {
        store.put(oneOff())
        val sender = FakeSender(SendResult.Failed("wake", "no PIN stored", retryable = false))
        processor(sender).run { enqueue(scheduled()); drain() }
        assertEquals(1, sender.jobs.size)
        assertEquals(listOf("failed Mum"), notifier.events)
    }

    @Test
    fun scheduleEditedWhileWaitingToRetryIsNotRetried() = runTest {
        store.put(oneOff())
        val sender = FakeSender(failed("search"))
        sender.onSend = { store.put(store.row(1).copy(nextAtMillis = due.plus(Duration.ofHours(2)).toEpochMilli())) }
        processor(sender).run { enqueue(scheduled()); drain() }
        assertEquals(1, sender.jobs.size)
        assertTrue("nothing recorded against the new occurrence", store.logged.isEmpty())
        assertTrue(notifier.events.isEmpty())
    }

    @Test
    fun scheduleDeletedWhileSendingIsNotRecorded() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        sender.onSend = { store.remove(1) }
        processor(sender).run { enqueue(scheduled()); drain() }
        assertTrue(store.logged.isEmpty())
        assertTrue(notifier.events.isEmpty())
    }

    // B04

    @Test
    fun rejectedPinIsNeverRetriedAndPausesPinEntry() = runTest {
        store.put(oneOff())
        val sender = FakeSender(SendResult.PinRejected("unlockKeyguard", "Keyguard still locked"))
        processor(sender).run { enqueue(scheduled()); drain() }
        assertEquals(1, sender.jobs.size)
        assertTrue(PinGuard(pinStore, clock).block() is PinBlock.Rejected)
        assertEquals(listOf("pinPaused", "failed Mum"), notifier.events)
    }

    @Test
    fun rejectedPinIsRecordedEvenWhenRecordingTheOutcomeFails() = runTest {
        store.put(oneOff())
        store.failWrites = true
        val sender = FakeSender(SendResult.PinRejected("unlockKeyguard", "Keyguard still locked"))
        processor(sender).run { enqueue(scheduled()); drain() }
        assertTrue(PinGuard(pinStore, clock).block() is PinBlock.Rejected)
        assertTrue(log.lines.any { it.startsWith("E Unhandled error in job 1") })
    }

    // B07

    @Test
    fun sendNowOnAOneOffUsesUpItsOccurrence() = runTest {
        val later = due.plus(Duration.ofHours(1))
        store.put(oneOff(at = later))
        val sender = FakeSender()
        val queue = processor(sender)
        queue.enqueue(SendRequest(1, SendKind.SEND_NOW, later.toEpochMilli()))
        queue.drain()
        assertNull(store.row(1).nextAtMillis)
        assertEquals(1, alarms.synced.size)
        // The alarm that was armed for it fires anyway: nothing is sent twice.
        queue.enqueue(scheduled(at = later))
        queue.drain()
        assertEquals(1, sender.jobs.size)
    }

    @Test
    fun sendNowOnASwitchedOffOneOffUsesUpItsOccurrence() = runTest {
        val later = due.plus(Duration.ofHours(1))
        store.put(oneOff(at = later).copy(enabled = false))
        val sender = FakeSender()
        processor(sender).run { enqueue(SendRequest(1, SendKind.SEND_NOW, later.toEpochMilli())); drain() }
        assertEquals(1, sender.jobs.size)
        assertNull(store.row(1).nextAtMillis)
    }

    @Test
    fun sendNowThatFailsItsPrecheckLeavesTheScheduleAlone() = runTest {
        val later = due.plus(Duration.ofHours(1))
        store.put(oneOff(at = later))
        val sender = FakeSender(SendResult.Failed(SendResult.Failed.PRECHECK, "Accessibility service is not running", retryable = false))
        processor(sender).run { enqueue(SendRequest(1, SendKind.SEND_NOW, later.toEpochMilli())); drain() }
        assertEquals(later.toEpochMilli(), store.row(1).nextAtMillis)
        assertEquals(listOf(SendStatus.FAILED), store.logged)
        assertTrue(notifier.events.isEmpty())
    }

    @Test
    fun testRunIsADryRunAndNeverOffersAManualSend() = runTest {
        store.put(oneOff())
        val sender = FakeSender(failed("search"), failed("search"), failed("search"))
        processor(sender).run { enqueue(SendRequest(1, SendKind.TEST)); drain() }
        assertTrue(sender.jobs.all { it.dryRun })
        assertTrue(notifier.events.isEmpty())
        assertEquals(due.toEpochMilli(), store.row(1).nextAtMillis)
    }

    @Test
    fun globalDryRunNeverOffersAManualSend() = runTest {
        // Found on the phone: a failed dry run posted "Tap to send", inviting a real message to a group.
        store.put(oneOff())
        val sender = FakeSender(failed("search"), failed("search"), failed("search"))
        processor(sender, Settings(dryRun = true, retryCount = 2)).run { enqueue(scheduled()); drain() }
        assertTrue(sender.jobs.all { it.dryRun })
        assertEquals(listOf(SendStatus.FAILED), store.logged)
        assertTrue(notifier.events.isEmpty())
    }

    private fun failed(step: String) = SendResult.Failed(step, "Timed out")

    private class FakeSender(vararg results: SendResult) : MessageSender {
        private val results = ArrayDeque(results.toList())
        val jobs = mutableListOf<SendJob>()
        var onSend: (SendJob) -> Unit = {}

        override suspend fun send(job: SendJob): SendResult {
            jobs += job
            onSend(job)
            return results.removeFirstOrNull() ?: SendResult.Sent
        }
    }

    private class FakeStore : SendStore {
        private val rows = mutableMapOf<Long, Schedule>()
        val logged = mutableListOf<SendStatus>()
        var failWrites = false

        fun put(schedule: Schedule) {
            rows[schedule.id] = schedule
        }

        fun remove(id: Long) {
            rows.remove(id)
        }

        fun row(id: Long) = rows.getValue(id)

        override suspend fun get(id: Long) = rows[id]

        override suspend fun recordRun(id: Long, occurrenceAtMillis: Long, status: SendStatus, detail: String?, sentTo: String, ranAt: Instant): Schedule? {
            check(!failWrites) { "disk full" }
            val current = rows[id] ?: return null
            logged += status
            val next = if (current.nextAtMillis == occurrenceAtMillis) ScheduleRepository.nextAfterRun(current, occurrenceAtMillis, ranAt) else current.nextAtMillis
            return current.copy(nextAtMillis = next, lastRunAtMillis = ranAt.toEpochMilli(), lastResult = status.name).also(::put)
        }

        override suspend fun recordManual(id: Long, status: SendStatus, detail: String?, sentTo: String, ranAt: Instant): Schedule? {
            val current = rows[id] ?: return null
            logged += status
            return current.copy(lastRunAtMillis = ranAt.toEpochMilli(), lastResult = status.name).also(::put)
        }
    }

    private class FakeAlarms : ScheduleAlarms {
        val synced = mutableListOf<Schedule>()
        override fun sync(schedule: Schedule) {
            synced += schedule
        }
    }

    private class FakeNotifier : SendNotifier {
        val events = mutableListOf<String>()
        override fun progress(text: String) = Unit
        override fun sent(schedule: Schedule, dryRun: Boolean) {
            events += "sent ${schedule.targetName}"
        }
        override fun failed(schedule: Schedule, occurrenceAtMillis: Long?, reason: String) {
            events += "failed ${schedule.targetName}"
        }
        override fun uncertain(schedule: Schedule, occurrenceAtMillis: Long?, reason: String) {
            events += "uncertain ${schedule.targetName}"
        }
        override fun pinPaused(reason: String) {
            events += "pinPaused"
        }
        override fun missed(schedule: Schedule, occurrenceAtMillis: Long) {
            events += "missed ${schedule.targetName}"
        }
    }

    private class FakePinStore : PinFailureStore {
        private var failures = PinFailures()
        override suspend fun pinFailures() = failures
        override suspend fun updatePinFailures(transform: (PinFailures) -> PinFailures) {
            failures = transform(failures)
        }
    }

    private class FakeLog : TraceLog {
        val lines = mutableListOf<String>()
        override fun info(message: String) {
            lines += "I $message"
        }
        override fun warn(message: String) {
            lines += "W $message"
        }
        override fun error(message: String, throwable: Throwable?) {
            lines += "E $message"
        }
    }

    private class TestClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
