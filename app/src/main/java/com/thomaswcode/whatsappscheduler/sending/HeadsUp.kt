package com.thomaswcode.whatsappscheduler.sending

import com.thomaswcode.whatsappscheduler.data.db.Schedule
import java.util.concurrent.atomic.AtomicLong
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

    /** The countdown would be seen: notifications are on, for the app and for its Before sending channel. */
    fun canShow(): Boolean

    /**
     * Counts down to [endsAtMillis] for [schedules] (a batch's members due together get one countdown)
     * and returns what the user chose, or null when the countdown ran out.
     */
    suspend fun ask(schedules: List<Schedule>, endsAtMillis: Long): HeadsUpChoice?

    /** No countdown at all: nothing is ever in use. */
    object None : HeadsUp {
        override fun inUse() = false
        override fun inCall() = false
        override fun canShow() = true
        override suspend fun ask(schedules: List<Schedule>, endsAtMillis: Long): HeadsUpChoice? = null
    }
}

/**
 * Where the countdown's buttons ([HeadsUpActionReceiver]) hand their answer to the countdown waiting
 * for it. There is one countdown at a time, as the queue sends one message at a time.
 */
class HeadsUpAnswers {

    /** A countdown waiting for its answer; [token] is carried by its buttons. */
    class Wait(val token: Long) {
        val answer = CompletableDeferred<HeadsUpChoice>()
    }

    private val tokens = AtomicLong()
    private val waiting = AtomicReference<Wait?>(null)

    /** Starts waiting for an answer to a new countdown, whose buttons carry the wait's token. */
    fun expect(): Wait = Wait(tokens.incrementAndGet()).also { waiting.set(it) }

    /**
     * A button was pressed. Only the countdown it belongs to takes the answer: one tapped after its
     * countdown ran out, or delivered late, can't answer the next one, e.g. snoozing another message.
     */
    fun answer(token: Long, choice: HeadsUpChoice) {
        waiting.get()?.takeIf { it.token == token }?.answer?.complete(choice)
    }

    /** The countdown is over: later taps on its notification do nothing. */
    fun done(wait: Wait) {
        waiting.compareAndSet(wait, null)
    }
}
