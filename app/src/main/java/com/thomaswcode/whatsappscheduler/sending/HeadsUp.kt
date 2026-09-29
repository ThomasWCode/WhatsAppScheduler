package com.thomaswcode.whatsappscheduler.sending

import com.thomaswcode.whatsappscheduler.data.db.Schedule
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/** What the user chose on the countdown before a scheduled send (features F1). */
enum class HeadsUpChoice {
    /** Send now, or the countdown ran out. */
    SEND,

    /** Move this occurrence five minutes on. */
    SNOOZE,

    /** Hold it until the user picks a new time. */
    POSTPONE,
}

/**
 * The countdown shown before a scheduled send while the phone is in use (features F1), so the screen
 * isn't taken over without warning. Behind an interface so the send queue's tests can answer it.
 */
interface HeadsUp {

    /** The phone is in use: the screen is on, locked or not. */
    fun inUse(): Boolean

    /** A phone or VoIP call is going on, which a send mustn't interrupt. */
    fun inCall(): Boolean

    /**
     * Counts down to [endsAtMillis] for [schedules] (a batch's members due together get one countdown)
     * and returns what the user chose, or null when the countdown ran out.
     */
    suspend fun ask(schedules: List<Schedule>, endsAtMillis: Long): HeadsUpChoice?

    /** No countdown at all: nothing is ever in use. */
    object None : HeadsUp {
        override fun inUse() = false
        override fun inCall() = false
        override suspend fun ask(schedules: List<Schedule>, endsAtMillis: Long): HeadsUpChoice? = null
    }
}

/**
 * Where the countdown's buttons ([HeadsUpActionReceiver]) hand their answer to the countdown waiting
 * for it. There is one countdown at a time, as the queue sends one message at a time.
 */
class HeadsUpAnswers {
    private val waiting = AtomicReference<CompletableDeferred<HeadsUpChoice>?>(null)

    /** Starts waiting for an answer to a new countdown. */
    fun expect(): CompletableDeferred<HeadsUpChoice> = CompletableDeferred<HeadsUpChoice>().also { waiting.set(it) }

    /** A button was pressed; ignored when no countdown is waiting, e.g. one tapped after it ran out. */
    fun answer(choice: HeadsUpChoice) {
        waiting.get()?.complete(choice)
    }

    /** The countdown is over: later taps on its notification do nothing. */
    fun done(answer: CompletableDeferred<HeadsUpChoice>) {
        waiting.compareAndSet(answer, null)
    }
}
