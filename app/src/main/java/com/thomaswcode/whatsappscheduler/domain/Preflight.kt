package com.thomaswcode.whatsappscheduler.domain

import java.time.Duration

/**
 * The pre-flight check (features F5): a look, a while before each scheduled send, at whatever would
 * make it fail, so there is time to fix it rather than finding out afterwards.
 */
object Preflight {

    /** How long before the send it looks. */
    val LEAD: Duration = Duration.ofMinutes(15)

    /**
     * When to look before the occurrence due at [occurrenceAtMillis]: null when that time has already
     * passed, e.g. for a message set less than [LEAD] ahead, which the user has only just checked on.
     * On recovering alarms ([catchUp]), e.g. a phone switched on ten minutes before a send, a look that
     * was missed is taken at once instead, while the send is still ahead.
     */
    fun checkAt(occurrenceAtMillis: Long, nowMillis: Long, catchUp: Boolean = false): Long? =
        (occurrenceAtMillis - LEAD.toMillis()).takeIf { it > nowMillis }
            ?: nowMillis.takeIf { catchUp && occurrenceAtMillis > nowMillis }

    /**
     * What a warning is about: the send due at [occurrenceAtMillis] to [chat], as it says. One about
     * anything else, e.g. after the schedule was moved to another chat at the same time, is stale.
     */
    fun warningKey(occurrenceAtMillis: Long, chat: String): String = "$occurrenceAtMillis|$chat"
}
