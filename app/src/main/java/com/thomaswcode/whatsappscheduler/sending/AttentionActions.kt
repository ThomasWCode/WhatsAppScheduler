package com.thomaswcode.whatsappscheduler.sending

import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.scheduling.ScheduleAlarms
import com.thomaswcode.whatsappscheduler.util.Texts
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the "Needs your attention" notifications' buttons do (features F3), on the schedule as it is
 * now; the list does the same from its rows. None of them acts on a schedule that is being sent,
 * which keeps its occurrence until the send is recorded (B08). Each returns what to tell the user,
 * or null when the notification going away says it all.
 */
class AttentionActions(
    private val repository: ScheduleRepository,
    private val alarms: ScheduleAlarms,
    private val sendState: () -> SendState,
    private val send: (SendRequest) -> Unit,
    /** Removes the schedule's notification (FallbackNotifier.cancel). */
    private val cancelPrompt: (Long) -> Unit,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Held by a send while it takes its row (SendQueueProcessor), so a discard can't slip in between. */
    private val admission: Mutex = Mutex(),
    /** What each action tells the user, from strings.xml. */
    private val texts: Texts,
) {

    /**
     * Send now (missed, or waiting for a time) and Send again (may have been sent): a copy, now. The
     * occurrence it was about has already been used up, so nothing else changes until it has gone.
     */
    suspend fun sendNow(id: Long): String? {
        repository.get(id) ?: return gone(id)
        if (sendState().isBusy(id)) return busy
        // Started first: the notification stays if the send couldn't be, so the message isn't forgotten.
        runCatching { send(SendRequest(id, SendKind.EXTRA_COPY)) }.onFailure { return texts.get(R.string.attention_start_failed, it.message ?: it.javaClass.simpleName) }
        cancelPrompt(id)
        return null
    }

    /**
     * Discard: a one-off goes, its history staying in Logs; a series goes on, and only stops waiting on
     * this occurrence.
     */
    suspend fun discard(id: Long): String? = admission.withLock {
        // Checked and done while no send can be taking the row: one queued before is refused here, and
        // one after finds the row gone rather than sending what the user has just discarded.
        val schedule = repository.get(id) ?: return gone(id)
        if (sendState().isBusy(id)) return busy
        if (schedule.rule == Recurrence.Once) repository.delete(schedule) else repository.setAttention(id, null)
        cancelPrompt(id)
        null
    }

    /** Mark as sent: the user checked the chat and it went (B02). */
    suspend fun markSent(id: Long): String? {
        repository.get(id) ?: return gone(id)
        if (sendState().isBusy(id)) return busy
        repository.markSent(id)
        cancelPrompt(id)
        return null
    }

    /**
     * Postpone… from its picker: the one-off as it was when the picker opened moves to [at] and is armed
     * then. Refused if it has been sent or changed since, or for a time less than a minute ahead.
     */
    suspend fun postpone(seen: Schedule, at: Instant): String? = postpone(listOf(seen), at)

    /** [postpone] for several held together, e.g. a batch its countdown held: all of them, or none. */
    suspend fun postpone(seen: List<Schedule>, at: Instant): String? {
        if (seen.isEmpty()) return texts.get(R.string.already_deleted)
        if (!at.isAfter(clock.instant().plusSeconds(59))) return texts.get(R.string.pick_a_later_minute)
        // Checked and moved while no send can be taking the row, as for Discard: a send queued just before
        // is refused here, and one after reads the moved row, no longer the occurrence it was for.
        val moved = admission.withLock {
            if (seen.any { sendState().isBusy(it.id) }) return texts.get(R.string.being_sent_not_moved)
            repository.postponeAll(seen, at).orEmpty()
        }
        if (moved.isEmpty()) return texts.get(R.string.changed_not_moved)
        var armed = true
        moved.forEach { m ->
            // A one-off's notification was about the time it now leaves.
            if (m.after.rule == Recurrence.Once) cancelPrompt(m.after.id)
            runCatching { alarms.sync(m.after) }.onFailure { armed = false }
        }
        return if (armed) null else texts.get(R.string.attention_moved_unarmed)
    }

    /** Deleted since the notification was posted: nothing to do, and the notification goes. */
    private fun gone(id: Long): String {
        cancelPrompt(id)
        return texts.get(R.string.already_deleted)
    }

    private val busy: String get() = texts.get(R.string.being_sent_now)
}
