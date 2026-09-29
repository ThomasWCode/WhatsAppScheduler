package com.thomaswcode.whatsappscheduler.sending

/** Why a schedule is being sent. Decides whether the send uses up the pending occurrence. */
enum class SendKind {
    /** The alarm for an occurrence fired, or a missed one is being caught up inside the grace window. */
    SCHEDULED,

    /** "Send now" on the pending occurrence: sends it early, and the schedule moves on as if it had fired (B07). */
    SEND_NOW,

    /** "Send an extra copy now": the pending occurrence is left as it is. */
    EXTRA_COPY,

    /** "Test (dry run)": every step runs except tapping Send. */
    TEST;

    val consumesOccurrence: Boolean get() = this == SCHEDULED || this == SEND_NOW

    /** Started from the app while the user is looking at it. */
    val manual: Boolean get() = this != SCHEDULED
}

/**
 * One request to send a schedule. [occurrenceAtMillis] is the idempotency key for kinds that use
 * up an occurrence (B01): the request is only carried out while the schedule's next occurrence is
 * still exactly that time. It is null for the other kinds, and for alarms armed before the key existed.
 */
data class SendRequest(
    val scheduleId: Long,
    val kind: SendKind,
    val occurrenceAtMillis: Long? = null,
    /**
     * When the send queue took it, which it notes. How late a scheduled send is is measured to then, so
     * time spent behind another send (its countdown, a call, retries) doesn't count against its alarm.
     */
    val receivedAtMillis: Long? = null,
) {

    /** Requests with equal keys are the same send, so at most one of them may be queued or running. */
    val dedupKey: Any get() = if (kind.consumesOccurrence) OccurrenceKey(scheduleId, occurrenceAtMillis) else ManualKey(scheduleId, kind)

    private data class OccurrenceKey(val scheduleId: Long, val occurrenceAtMillis: Long?)
    private data class ManualKey(val scheduleId: Long, val kind: SendKind)
}
