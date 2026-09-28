package com.thomaswcode.whatsappscheduler.ui.schedules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.DeletedSchedules
import com.thomaswcode.whatsappscheduler.data.db.Attention
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.settings.Settings
import com.thomaswcode.whatsappscheduler.domain.PinFailures
import com.thomaswcode.whatsappscheduler.domain.Recurrence
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import com.thomaswcode.whatsappscheduler.sending.SendState
import com.thomaswcode.whatsappscheduler.sending.accessibility.WaAccessibilityService
import com.thomaswcode.whatsappscheduler.ui.minuteClock
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something the list should say once, usually with Undo. */
sealed interface ListEvent {
    /** [prompted] had a notification showing, which Undo puts back. */
    data class Deleted(val summary: String, val removed: DeletedSchedules, val prompted: Set<Long>) : ListEvent

    /** Moved to a new time; [before] are the rows as they were, for Undo. */
    data class Postponed(val summary: String, val before: List<Schedule>) : ListEvent

    /** A series stopped asking about an occurrence; [previous] puts the attention back. */
    data class Dismissed(val summary: String, val previous: Map<Long, Attention>, val prompted: Set<Long>) : ListEvent

    data class Failed(val message: String) : ListEvent
}

/** The schedule list's state and actions (T2, quick-schedule.md §7). */
class ScheduleListViewModel(private val container: AppContainer) : ViewModel() {

    private val whileShown = SharingStarted.WhileSubscribed(5_000)

    val schedules: StateFlow<List<Schedule>> = container.repository.observeSchedules().stateIn(viewModelScope, whileShown, emptyList())
    val settings: StateFlow<Settings?> = container.settings.settings.stateIn(viewModelScope, whileShown, null)
    val hasPin: StateFlow<Boolean> = container.secrets.hasPin().stateIn(viewModelScope, whileShown, true)
    val pinFailures: StateFlow<PinFailures> = container.secrets.observePinFailures().stateIn(viewModelScope, whileShown, PinFailures())
    val serviceConnected: StateFlow<Boolean> =
        WaAccessibilityService.instance.map { it != null }.stateIn(viewModelScope, whileShown, WaAccessibilityService.instance.value != null)

    /** When the message that needs attention was due: the occurrence of each schedule's latest run. */
    val dueAt: StateFlow<Map<Long, Long>> = container.repository.observeLatestOccurrences().stateIn(viewModelScope, whileShown, emptyMap())

    val now: StateFlow<ZonedDateTime> = minuteClock().stateIn(viewModelScope, whileShown, ZonedDateTime.now())

    /** What the send service is doing, for rows being sent right now. */
    val sendState: StateFlow<SendState> = container.sendState.state

    private val events = Channel<ListEvent>(Channel.BUFFERED)
    val eventFlow: Flow<ListEvent> = events.receiveAsFlow()

    /** Pauses or resumes a series (only repeating rows have a switch: R4, B29), or resumes an old paused one-off. */
    fun setEnabled(group: List<Schedule>, enabled: Boolean) {
        viewModelScope.launch {
            group.forEach { container.repository.setEnabled(it.id, enabled)?.let(container.alarmScheduler::sync) }
        }
    }

    /** Swipe or Delete: the schedules go, with their history, and Undo can bring them back. */
    fun delete(group: List<Schedule>, verb: String = "Deleted") {
        viewModelScope.launch {
            val ids = group.map { it.id }
            val prompted = ids.filter(container.fallbackNotifier::isShowing).toSet()
            runCatching {
                withContext(NonCancellable) {
                    // The rows first: if that fails, their alarms must still be there. A stale alarm for a
                    // deleted row is harmless, as the send queue drops it.
                    container.repository.deleteWithHistory(ids).also { removed ->
                        removed.schedules.forEach {
                            container.alarmScheduler.cancel(it.id)
                            // Its Tap to send prompt would otherwise outlive it (B10).
                            container.fallbackNotifier.cancel(it.id)
                        }
                    }
                }
            }
                .onSuccess { removed ->
                    if (removed.schedules.isNotEmpty()) events.trySend(ListEvent.Deleted("$verb: " + groupTitle(removed.schedules), removed, prompted))
                }
                .onFailure { fail("Couldn't delete", it) }
        }
    }

    fun undoDelete(removed: DeletedSchedules, prompted: Set<Long>) {
        viewModelScope.launch {
            runCatching {
                withContext(NonCancellable) {
                    container.repository.restore(removed).forEach {
                        container.alarmScheduler.sync(it)
                        if (it.id in prompted) container.fallbackNotifier.notifyAttention(it, dueAt.value[it.id])
                    }
                }
            }.onFailure { fail("Couldn't undo", it) }
        }
    }

    /**
     * Discard on a message that needs attention (features F3): a one-off is deleted, with Undo; a
     * series only stops asking about that occurrence and carries on.
     */
    fun discard(group: List<Schedule>) {
        if (group.first().rule == Recurrence.Once) {
            delete(group, verb = "Discarded")
            return
        }
        viewModelScope.launch {
            val previous = group.mapNotNull { s -> s.attention?.let { s.id to it } }.toMap()
            val prompted = previous.keys.filter(container.fallbackNotifier::isShowing).toSet()
            runCatching {
                previous.keys.forEach {
                    container.repository.setAttention(it, null)
                    container.fallbackNotifier.cancel(it)
                }
            }
                .onSuccess { events.trySend(ListEvent.Dismissed("Dismissed: " + groupTitle(group), previous, prompted)) }
                .onFailure { fail("Couldn't dismiss", it) }
        }
    }

    fun undoDismiss(previous: Map<Long, Attention>, prompted: Set<Long>) {
        viewModelScope.launch {
            runCatching {
                previous.forEach { (id, attention) ->
                    container.repository.setAttention(id, attention)
                    if (id in prompted) container.repository.get(id)?.let { container.fallbackNotifier.notifyAttention(it, dueAt.value[id]) }
                }
            }.onFailure { fail("Couldn't undo", it) }
        }
    }

    /**
     * Postpone… (quick-schedule.md §9): each member's occurrence moves to [at] and is armed for that
     * time; Undo puts the timing back. [summary] is what the snackbar says.
     */
    fun postpone(group: List<Schedule>, at: Instant, summary: String) {
        // Something being sent keeps its occurrence until it is recorded (B08).
        if (group.any { sendState.value.isBusy(it.id) }) {
            events.trySend(ListEvent.Failed("It's being sent now, so it wasn't moved."))
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(NonCancellable) {
                    // Each as the user saw it: one sent or changed since isn't moved.
                    group.mapNotNull { container.repository.postpone(it.id, at, seen = it) }.onEach { moved ->
                        container.alarmScheduler.sync(moved.after)
                        // A prompt it had was about the old time.
                        container.fallbackNotifier.cancel(moved.after.id)
                    }
                }
            }
                .onSuccess { moved ->
                    when {
                        moved.isEmpty() -> events.trySend(ListEvent.Failed("It had just been sent or changed, so it wasn't moved."))
                        else -> events.trySend(ListEvent.Postponed(summary, moved.map { it.before }))
                    }
                }
                .onFailure { fail("Couldn't postpone", it) }
        }
    }

    fun undoPostpone(before: List<Schedule>) {
        viewModelScope.launch {
            runCatching { withContext(NonCancellable) { before.mapNotNull { container.repository.restoreTiming(it) }.forEach(container.alarmScheduler::sync) } }
                .onFailure { fail("Couldn't undo", it) }
        }
    }

    /** "Mark as sent" on a message that may have gone out (B02): the user checked the chat. */
    fun markSent(group: List<Schedule>) {
        viewModelScope.launch {
            runCatching {
                group.forEach {
                    container.repository.markSent(it.id)
                    container.fallbackNotifier.cancel(it.id)
                }
            }.onFailure { fail("Couldn't mark it as sent", it) }
        }
    }

    fun send(request: SendRequest) = container.send(request)

    private fun fail(what: String, error: Throwable) {
        container.log.error(what, error)
        events.trySend(ListEvent.Failed("$what (${error.message ?: error.javaClass.simpleName})."))
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { ScheduleListViewModel(container) } }
    }
}
