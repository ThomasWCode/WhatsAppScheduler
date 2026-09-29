package com.thomaswcode.whatsappscheduler.sending

import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.HeldRun
import com.thomaswcode.whatsappscheduler.data.SendStore
import com.thomaswcode.whatsappscheduler.data.db.Attention
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

    /** Shared by every processor in a test, as by every send service in the process. */
    private val stopped = StoppedOccurrences(clock)

    private fun processor(sender: MessageSender, settings: Settings = Settings(retryCount = 2, retryDelaySeconds = 30), headsUp: HeadsUp = HeadsUp.None) =
        SendQueueProcessor(store, sender, alarms, notifier, PinGuard(pinStore, clock), state, log, { settings }, clock, headsUp = headsUp, stopped = stopped)

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
    fun occurrenceLateBeyondTheGraceWindowIsHeldNotSent() = runTest {
        store.put(daily())
        clock.now = due.plus(Duration.ofHours(3))
        val sender = FakeSender()
        processor(sender, Settings(missedGraceMinutes = 120)).run { enqueue(scheduled()); drain() }
        assertTrue(sender.jobs.isEmpty())
        assertEquals(listOf(SendStatus.MISSED), store.logged)
        // The series goes on; the user is asked about the held copy of the missed message (B09).
        assertEquals(due.plus(Duration.ofDays(1)).toEpochMilli(), store.row(1).nextAtMillis)
        assertEquals(listOf("missed Mum"), notifier.events)
        assertEquals(store.held.single().id, notifier.missedIds.single())
        assertEquals(1L, store.held.single().sourceScheduleId)
    }

    @Test
    fun theGraceWindowIsTwentyMinutesByDefault() = runTest {
        store.put(oneOff())
        clock.now = due.plus(Duration.ofMinutes(21))
        val sender = FakeSender()
        processor(sender, Settings()).run { enqueue(scheduled()); drain() }
        assertTrue(sender.jobs.isEmpty())
        assertEquals(listOf(SendStatus.MISSED), store.logged)
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

    // F1: the countdown before a scheduled send while the phone is in use

    @Test
    fun aScheduledSendCountsDownFirstAndGoesWhenItRunsOut() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val countdown = FakeHeadsUp()
        processor(sender, headsUp = countdown).run { enqueue(scheduled()); drain() }
        assertEquals(listOf(listOf(1L)), countdown.asked)
        assertEquals(1, sender.jobs.size)
    }

    @Test
    fun noCountdownWhenThePhoneIsntInUseOrTheSendIsByHand() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val countdown = FakeHeadsUp(inUse = false)
        processor(sender, headsUp = countdown).run { enqueue(scheduled()); drain() }
        store.put(oneOff(2))
        countdown.inUse = true
        processor(sender, headsUp = countdown).run { enqueue(SendRequest(2, SendKind.SEND_NOW, due.toEpochMilli())); drain() }
        assertTrue(countdown.asked.isEmpty())
        assertEquals(2, sender.jobs.size)
    }

    @Test
    fun snoozeMovesTheOccurrenceFiveMinutesOnWithoutAFailure() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        processor(sender, headsUp = FakeHeadsUp(choice = HeadsUpChoice.SNOOZE)).run { enqueue(scheduled()); drain() }
        assertTrue(sender.jobs.isEmpty())
        assertEquals(clock.instant().plus(Duration.ofMinutes(5)).toEpochMilli(), store.row(1).nextAtMillis)
        assertEquals(store.row(1), alarms.synced.last())
        assertTrue(notifier.events.isEmpty())
        assertTrue(store.logged.isEmpty())
    }

    @Test
    fun postponeHoldsItAndAsksForATime() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        processor(sender, headsUp = FakeHeadsUp(choice = HeadsUpChoice.POSTPONE)).run { enqueue(scheduled()); drain() }
        assertTrue(sender.jobs.isEmpty())
        assertEquals(Attention.HELD, store.row(1).attention)
        assertNull(store.row(1).nextAtMillis)
        assertEquals(listOf("held Mum"), notifier.events)
    }

    @Test
    fun duringACallTheSendWaitsForItToEnd() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val countdown = FakeHeadsUp(calls = 3)
        processor(sender, headsUp = countdown).run { enqueue(scheduled()); drain() }
        // Three looks, 15 s apart, before the countdown.
        assertEquals(45_000L, testScheduler.currentTime)
        assertEquals(1, countdown.asked.size)
        assertEquals(1, sender.jobs.size)
    }

    @Test
    fun aCallDuringTheCountdownIsWaitedOutAndTheCountdownRunsAgain() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val countdown = FakeHeadsUp(callAfterFirstAsk = 2)
        processor(sender, headsUp = countdown).run { enqueue(scheduled()); drain() }
        assertEquals(2, countdown.asked.size)
        assertEquals(1, sender.jobs.size)
    }

    @Test
    fun whenACallCutsShortEveryCountdownItIsSnoozedNotSentAsTheLastEnds() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val countdown = FakeHeadsUp(callDuringEveryAsk = 2)
        processor(sender, headsUp = countdown).run { enqueue(scheduled()); drain() }
        assertEquals(3, countdown.asked.size)
        assertTrue(sender.jobs.isEmpty())
        assertEquals(clock.instant().plus(Duration.ofMinutes(5)).toEpochMilli(), store.row(1).nextAtMillis)
    }

    @Test
    fun withNoCountdownToShowItWaitsForThePhoneToBePutDown() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val countdown = FakeHeadsUp(canShow = false, inUseLooks = 3)
        processor(sender, headsUp = countdown).run { enqueue(scheduled()); drain() }
        assertTrue(countdown.asked.isEmpty())
        // In use at two looks, 15 s apart, then put down.
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(1, sender.jobs.size)
    }

    @Test
    fun withNoCountdownToShowAPhoneInUseForHalfAnHourHoldsItQuietly() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        processor(sender, headsUp = FakeHeadsUp(canShow = false)).run { enqueue(scheduled()); drain() }
        assertTrue(sender.jobs.isEmpty())
        assertEquals(30 * 60_000L, testScheduler.currentTime)
        assertEquals(Attention.HELD, store.row(1).attention)
        assertEquals(listOf("held Mum"), notifier.events)
        // Its notification only: no picker opens over what the user is doing.
        assertTrue(notifier.heldGroups.isEmpty())
    }

    @Test
    fun aCallThatOutlastsHalfAnHourIsNeverInterrupted() = runTest {
        store.put(oneOff())
        val sender = FakeSender()
        val countdown = FakeHeadsUp(calls = 1_000)
        processor(sender, headsUp = countdown).run { enqueue(scheduled()); drain() }
        assertEquals(30 * 60_000L, testScheduler.currentTime)
        assertTrue(countdown.asked.isEmpty())
        assertTrue(sender.jobs.isEmpty())
        assertEquals(clock.instant().plus(Duration.ofMinutes(5)).toEpochMilli(), store.row(1).nextAtMillis)
    }

    @Test
    fun aSnoozeWhoseAlarmCantBeSetIsHeldWhereItCanBeSeen() = runTest {
        store.put(oneOff())
        alarms.failing = true
        processor(FakeSender(), headsUp = FakeHeadsUp(choice = HeadsUpChoice.SNOOZE)).run { enqueue(scheduled()); drain() }
        assertEquals(Attention.HELD, store.row(1).attention)
        assertNull(store.row(1).nextAtMillis)
        assertEquals(listOf("held Mum"), notifier.events)
        assertTrue(notifier.heldGroups.isEmpty())
    }

    @Test
    fun aBatchLetThroughIsSentEvenWhenTheOthersAlarmsComeLate() = runTest {
        store.put(oneOff(1).copy(batchId = "b"))
        store.put(oneOff(2).copy(batchId = "b", targetName = "Dad"))
        val sender = FakeSender()
        val countdown = FakeHeadsUp()
        val processor = processor(sender, headsUp = countdown)
        // Only the first alarm has come when the countdown runs; the other's comes after the queue has emptied.
        processor.run { enqueue(scheduled(1)); drain() }
        processor.run { enqueue(scheduled(2)); drain() }
        assertEquals(listOf(listOf(1L, 2L)), countdown.asked)
        assertEquals(listOf(1L, 2L), sender.jobs.map { it.scheduleId })
    }

    @Test
    fun aBatchLetThroughIsntMissedForTheTimeItsCountdownTook() = runTest {
        store.put(oneOff(1).copy(batchId = "b"))
        store.put(oneOff(2).copy(batchId = "b", targetName = "Dad"))
        val sender = FakeSender()
        // The phone was held for 25 minutes, past the 20-minute grace window, before the batch went.
        val countdown = FakeHeadsUp(onAsk = { clock.now = clock.now.plus(Duration.ofMinutes(25)) })
        processor(sender, Settings(), headsUp = countdown).run { enqueue(scheduled(1)); drain() }
        assertEquals(listOf(1L, 2L), sender.jobs.map { it.scheduleId })
        assertTrue(notifier.missedIds.isEmpty())
    }

    @Test
    fun aSendQueuedBehindALongCountdownIsntMissed() = runTest {
        store.put(oneOff(1))
        store.put(oneOff(3).copy(targetName = "Sam"))
        val sender = FakeSender()
        var asks = 0
        val countdown = FakeHeadsUp(onAsk = { if (asks++ == 0) clock.now = clock.now.plus(Duration.ofMinutes(25)) })
        processor(sender, Settings(), headsUp = countdown).run { enqueue(scheduled(1)); enqueue(scheduled(3)); drain() }
        // Its alarm came on time; the 25 minutes were spent behind the first send's countdown.
        assertEquals(listOf(1L, 3L), sender.jobs.map { it.scheduleId })
        assertTrue(notifier.missedIds.isEmpty())
    }

    @Test
    fun aSeriesWhoseNextAlarmCantBeSetAfterPostponeSaysSo() = runTest {
        store.put(daily())
        alarms.failing = true
        processor(FakeSender(), headsUp = FakeHeadsUp(choice = HeadsUpChoice.POSTPONE)).run { enqueue(scheduled()); drain() }
        assertEquals(listOf("unarmed Mum", "held Mum"), notifier.events)
        assertEquals(listOf(listOf(1_001L)), notifier.heldGroups)
    }

    @Test
    fun postponeDuringACallThatBeganInTheCountdownOpensNoPicker() = runTest {
        store.put(oneOff())
        processor(FakeSender(), headsUp = FakeHeadsUp(choice = HeadsUpChoice.POSTPONE, callAfterFirstAsk = 1)).run { enqueue(scheduled()); drain() }
        assertEquals(Attention.HELD, store.row(1).attention)
        assertEquals(listOf("held Mum"), notifier.events)
        assertTrue(notifier.heldGroups.isEmpty())
    }

    @Test
    fun theListSaysItIsAboutToSendDuringTheCountdown() = runTest {
        store.put(oneOff())
        val phases = mutableListOf<SendPhase?>()
        val countdown = FakeHeadsUp(onAsk = { phases += state.state.value.phase })
        processor(FakeSender(), headsUp = countdown).run { enqueue(scheduled()); drain() }
        assertEquals(listOf(SendPhase.WAITING), phases)
    }

    @Test
    fun aSnoozeThatCantBeSavedStopsTheWholeBatch() = runTest {
        store.put(oneOff(1).copy(batchId = "b"))
        store.put(oneOff(2).copy(batchId = "b", targetName = "Dad"))
        store.failWrites = true
        val sender = FakeSender()
        val countdown = FakeHeadsUp(choice = HeadsUpChoice.SNOOZE)
        processor(sender, headsUp = countdown).run { enqueue(scheduled(1)); enqueue(scheduled(2)); drain() }
        // One countdown; nothing sent, nor counted down again; both reported as not sent.
        assertEquals(1, countdown.asked.size)
        assertTrue(sender.jobs.isEmpty())
        assertEquals(listOf("failed Mum", "failed Dad"), notifier.events)
    }

    @Test
    fun aBatchThatCouldntBeSnoozedStaysStoppedForAnAlarmThatComesLater() = runTest {
        store.put(oneOff(1).copy(batchId = "b"))
        store.put(oneOff(2).copy(batchId = "b", targetName = "Dad"))
        store.failWrites = true
        val sender = FakeSender()
        val countdown = FakeHeadsUp(choice = HeadsUpChoice.SNOOZE)
        processor(sender, headsUp = countdown).run { enqueue(scheduled(1)); drain() }
        // The other member's alarm comes after that queue has gone, to a new send service.
        processor(sender, headsUp = countdown).run { enqueue(scheduled(2)); drain() }
        assertEquals(1, countdown.asked.size)
        assertTrue(sender.jobs.isEmpty())
    }

    @Test
    fun aCallBeforeARetrySnoozesInsteadOfInterruptingIt() = runTest {
        store.put(oneOff())
        val sender = FakeSender(failed("search"), SendResult.Sent)
        val countdown = FakeHeadsUp()
        // A call starts after the first attempt, during the retry delay.
        sender.onSend = { countdown.startCall(1) }
        processor(sender, headsUp = countdown).run { enqueue(scheduled()); drain() }
        assertEquals(1, sender.jobs.size)
        assertEquals(clock.instant().plus(Duration.ofMinutes(5)).toEpochMilli(), store.row(1).nextAtMillis)
    }

    @Test
    fun postponingABatchOpensOnePicker() = runTest {
        store.put(oneOff(1).copy(batchId = "b"))
        store.put(oneOff(2).copy(batchId = "b", targetName = "Dad"))
        processor(FakeSender(), headsUp = FakeHeadsUp(choice = HeadsUpChoice.POSTPONE)).run { enqueue(scheduled(1)); enqueue(scheduled(2)); drain() }
        assertEquals(listOf(listOf(1L, 2L)), notifier.heldGroups)
    }

    @Test
    fun aBatchDueTogetherGetsOneCountdown() = runTest {
        store.put(oneOff(1).copy(batchId = "b"))
        store.put(oneOff(2).copy(batchId = "b", targetName = "Dad"))
        val sender = FakeSender()
        val countdown = FakeHeadsUp()
        processor(sender, headsUp = countdown).run { enqueue(scheduled(1)); enqueue(scheduled(2)); drain() }
        assertEquals(listOf(listOf(1L, 2L)), countdown.asked)
        assertEquals(2, sender.jobs.size)
    }

    @Test
    fun snoozingABatchMovesEveryMember() = runTest {
        store.put(oneOff(1).copy(batchId = "b"))
        store.put(oneOff(2).copy(batchId = "b", targetName = "Dad"))
        val sender = FakeSender()
        processor(sender, headsUp = FakeHeadsUp(choice = HeadsUpChoice.SNOOZE)).run { enqueue(scheduled(1)); enqueue(scheduled(2)); drain() }
        assertTrue(sender.jobs.isEmpty())
        val snoozedTo = clock.instant().plus(Duration.ofMinutes(5)).toEpochMilli()
        assertEquals(listOf(snoozedTo, snoozedTo), listOf(store.row(1).nextAtMillis, store.row(2).nextAtMillis))
    }

    @Test
    fun anAlarmThatFailsDoesntStopAPostponedBatch() = runTest {
        store.put(oneOff(1).copy(batchId = "b"))
        store.put(oneOff(2).copy(batchId = "b", targetName = "Dad"))
        alarms.failing = true
        processor(FakeSender(), headsUp = FakeHeadsUp(choice = HeadsUpChoice.POSTPONE)).run { enqueue(scheduled(1)); enqueue(scheduled(2)); drain() }
        assertEquals(listOf(listOf(1L, 2L)), notifier.heldGroups)
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
        processor(sender, Settings(dryRunUntilMillis = Settings.UNTIL_TURNED_OFF, retryCount = 2)).run { enqueue(scheduled()); drain() }
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

        val held = mutableListOf<Schedule>()

        override suspend fun duePeers(schedule: Schedule, occurrenceAtMillis: Long): List<Schedule> =
            schedule.batchId?.let { batch -> rows.values.filter { it.batchId == batch && it.nextAtMillis == occurrenceAtMillis }.sortedBy { it.id } }
                ?: listOf(schedule)

        override suspend fun snooze(ids: List<Long>, occurrenceAtMillis: Long, to: Instant, ranAt: Instant): List<Schedule> = ids.also { check(!failWrites) { "disk full" } }.mapNotNull { id ->
            rows[id]?.takeIf { it.nextAtMillis == occurrenceAtMillis }?.copy(nextAtMillis = to.toEpochMilli(), postponedFromMillis = occurrenceAtMillis)?.also(::put)
        }

        override suspend fun hold(ids: List<Long>, occurrenceAtMillis: Long, ranAt: Instant, detail: String): List<HeldRun> = ids.also { check(!failWrites) { "disk full" } }.mapNotNull { id ->
            val current = rows[id]?.takeIf { it.nextAtMillis == occurrenceAtMillis } ?: return@mapNotNull null
            if (current.recurrence == "ONCE") {
                val waiting = current.copy(nextAtMillis = null, attention = Attention.HELD).also(::put)
                return@mapNotNull HeldRun(waiting, waiting)
            }
            // A series goes on to its next day; the occurrence waits as a one-off copy of its own.
            val series = current.copy(nextAtMillis = occurrenceAtMillis + Duration.ofDays(1).toMillis()).also(::put)
            val copy = current.copy(id = 1_000 + id, recurrence = "ONCE", nextAtMillis = null, attention = Attention.HELD, sourceScheduleId = id).also(::put)
            HeldRun(series, copy)
        }

        override suspend fun recordMissed(id: Long, occurrenceAtMillis: Long, detail: String, sentTo: String, ranAt: Instant): HeldRun? {
            val stored = recordRun(id, occurrenceAtMillis, SendStatus.MISSED, detail, sentTo, ranAt) ?: return null
            if (stored.recurrence == "ONCE") return HeldRun(stored, stored)
            val copy = stored.copy(id = 1_000 + id, recurrence = "ONCE", nextAtMillis = null, firstAtMillis = occurrenceAtMillis, sourceScheduleId = id)
            held += copy
            return HeldRun(stored, copy)
        }
    }

    private class FakeAlarms : ScheduleAlarms {
        val synced = mutableListOf<Schedule>()
        var failing = false
        override fun sync(schedule: Schedule) {
            check(!failing) { "alarm rejected" }
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
        /** The schedules each Postpone picker opened for. */
        val heldGroups = mutableListOf<List<Long>>()
        override fun held(schedules: List<Schedule>, pick: Boolean) {
            schedules.forEach { events += "held ${it.targetName}" }
            if (pick) heldGroups += schedules.map { it.id }
        }
        override fun unarmed(schedule: Schedule) {
            events += "unarmed ${schedule.targetName}"
        }

        val missedIds = mutableListOf<Long>()
        override fun missed(schedule: Schedule, occurrenceAtMillis: Long) {
            events += "missed ${schedule.targetName}"
            missedIds += schedule.id
        }
    }

    /** In use unless told otherwise; in a call for the first [calls] looks; answers [choice] (null: runs out). */
    private class FakeHeadsUp(
        var inUse: Boolean = true,
        private var calls: Int = 0,
        private val choice: HeadsUpChoice? = null,
        /** A call starts during the first countdown and lasts this many looks. */
        private val callAfterFirstAsk: Int = 0,
        /** A call starts during every countdown and lasts this many looks. */
        private val callDuringEveryAsk: Int = 0,
        /** Notifications are on, so a countdown can be seen. */
        private val canShow: Boolean = true,
        /** In use for this many looks, then put down. */
        private var inUseLooks: Int = Int.MAX_VALUE,
        /** Runs as each countdown is shown, e.g. to let time pass while it is. */
        private val onAsk: () -> Unit = {},
    ) : HeadsUp {
        val asked = mutableListOf<List<Long>>()
        override fun inUse() = inUse && inUseLooks-- > 0
        override fun inCall() = calls-- > 0

        /** A call starts now and lasts this many looks. */
        fun startCall(looks: Int) {
            calls = looks
        }
        override fun canShow() = canShow
        override suspend fun ask(schedules: List<Schedule>, endsAtMillis: Long): HeadsUpChoice? {
            asked += schedules.map { it.id }
            onAsk()
            if (asked.size == 1) calls = callAfterFirstAsk
            if (callDuringEveryAsk > 0) calls = callDuringEveryAsk
            return choice
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
