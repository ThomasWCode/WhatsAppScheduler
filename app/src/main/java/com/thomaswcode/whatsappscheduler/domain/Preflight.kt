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
     */
    fun checkAt(occurrenceAtMillis: Long, nowMillis: Long): Long? =
        (occurrenceAtMillis - LEAD.toMillis()).takeIf { it > nowMillis }
}
