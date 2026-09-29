package com.thomaswcode.whatsappscheduler.ui.schedules

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.thomaswcode.whatsappscheduler.ui.quick.QuickScheduleViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Job
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

    /** Moved to a new time; [moved] are the rows before and after, for Undo, and [prompted] had a prompt showing. */
    data class Postponed(val summary: String, val moved: List<com.thomaswcode.whatsappscheduler.data.Postponed>, val prompted: Set<Long>) : ListEvent

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

    /**
     * The list's snackbar. It lives here, not in the screen, so a message and its Undo survive a
     * rotation: the screen is recreated and shows the snackbar that is still running.
     */
    val snackbar = SnackbarHostState()

    private var announcing: Job? = null

    /** A second snackbar, shown after the first unless its action was taken, e.g. a one-time offer. */
    data class FollowUp(val message: String, val action: String, val run: () -> Unit)

    /**
     * Shows [message], replacing one that is showing so its Undo isn't queued behind it; [onAction] if
     * tapped, otherwise [followUp] next.
     */
    fun announce(message: String, action: String? = null, followUp: FollowUp? = null, onAction: () -> Unit = {}) {
        // A newer message replaces the one showing, and with it any follow-up still to come from it.
        announcing?.cancel()
        announcing = viewModelScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message,
                actionLabel = action,
                withDismissAction = action == null,
                duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            when {
                result == SnackbarResult.ActionPerformed -> onAction()
                followUp != null ->
                    if (snackbar.showSnackbar(followUp.message, actionLabel = followUp.action, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) followUp.run()
            }
        }
    }

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
    /**
     * The rows the Postpone dialog is for, as they were when it opened. Kept here, not looked up again
     * from the live list: if the message goes out while the dialog is open, the move is then refused,
     * rather than applied to the row as it is after the send.
     */
    var postponing by mutableStateOf<List<Schedule>?>(null)
        private set

    fun askToPostpone(group: List<Schedule>) {
        postponing = group
    }

    fun cancelPostpone() {
        postponing = null
    }

    /** Moves the rows [askToPostpone] was given; [summary] is what the snackbar says. */
    fun postpone(at: Instant, summary: String) {
        val group = postponing ?: return
        postponing = null
        // The dialog judged by the last minute tick; this is the moment it counts.
        if (!QuickScheduleViewModel.isSendable(at.atZone(ZoneId.systemDefault()), ZonedDateTime.now())) {
            events.trySend(ListEvent.Failed("Pick a time at least a minute from now."))
            return
        }
        // Something being sent keeps its occurrence until it is recorded (B08).
        if (group.any { sendState.value.isBusy(it.id) }) {
            events.trySend(ListEvent.Failed("It's being sent now, so it wasn't moved."))
            return
        }
        val prompted = group.filter { it.rule == Recurrence.Once }.map { it.id }.filter(container.fallbackNotifier::isShowing).toSet()
        viewModelScope.launch {
            runCatching {
                withContext(NonCancellable) {
                    // Each as the user saw it: one sent or changed since isn't moved.
                    // All of it as the user saw it, or nothing: a batch can't end up half moved.
                    val moved = container.repository.postponeAll(group, at).orEmpty()
                    // Committed: every member is armed on its own, so one failure can't leave the others at
                    // their old alarms, and the move is reported (with Undo) either way.
                    val unarmed = moved.count { m ->
                        // A one-off's prompt was about the time it now leaves. A series' is about an earlier
                        // occurrence that still needs the user, so it stays.
                        if (m.after.rule == Recurrence.Once) runCatching { container.fallbackNotifier.cancel(m.after.id) }
                        runCatching { container.alarmScheduler.sync(m.after) }.onFailure { container.log.error("Arming a moved send failed", it) }.isFailure
                    }
                    moved to unarmed
                }
            }
                .onSuccess { (moved, unarmed) ->
                    when {
                        moved.isEmpty() -> events.trySend(ListEvent.Failed("It had just been sent or changed, so it wasn't moved."))
                        unarmed > 0 -> events.trySend(ListEvent.Postponed("$summary, but its alarm couldn't be set: $REARM", moved, prompted))
                        else -> events.trySend(ListEvent.Postponed(summary, moved, prompted))
                    }
                }
                .onFailure { fail("Couldn't postpone", it) }
        }
    }

    /**
     * Undo for Postpone, refused while the moved occurrence is being sent and skipped for any row whose
     * move no longer stands; a prompt that was showing before the move comes back.
     */
    fun undoPostpone(moved: List<com.thomaswcode.whatsappscheduler.data.Postponed>, prompted: Set<Long>) {
        if (moved.any { sendState.value.isBusy(it.after.id) }) {
            events.trySend(ListEvent.Failed("It's being sent now, so it can't be moved back."))
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(NonCancellable) {
                    val restored = container.repository.restoreAll(moved).orEmpty()
                    // Committed: each is armed on its own, and one that couldn't be is reported.
                    val unarmed = restored.count { r ->
                        if (r.id in prompted && r.attention != null) {
                            runCatching { container.fallbackNotifier.notifyAttention(r, dueAt.value[r.id]) }
                        }
                        runCatching { container.alarmScheduler.sync(r) }.onFailure { container.log.error("Re-arming after Undo failed", it) }.isFailure
                    }
                    restored to unarmed
                }
            }
                .onSuccess { (restored, unarmed) ->
                    when {
                        restored.isEmpty() -> events.trySend(ListEvent.Failed("It has been sent, changed or is about to be sent, so it wasn't moved back."))
                        unarmed > 0 -> events.trySend(ListEvent.Failed("Moved back, but its alarm couldn't be set: $REARM."))
                    }
                }
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

    /** "Turn off" on the test-mode warning (B11). */
    fun turnOffDryRun() {
        viewModelScope.launch { container.settings.update { it.copy(dryRunUntilMillis = null) } }
    }

    companion object {
        /** What to do about an alarm that couldn't be set: the app re-arms every alarm only on a fresh start. */
        private const val REARM = "use Re-arm all alarms in Settings"

        fun factory(container: AppContainer) = viewModelFactory { initializer { ScheduleListViewModel(container) } }
    }
}
