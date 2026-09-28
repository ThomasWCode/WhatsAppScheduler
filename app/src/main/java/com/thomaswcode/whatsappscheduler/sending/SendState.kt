package com.thomaswcode.whatsappscheduler.sending

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SendPhase { SENDING, WAITING_TO_RETRY }

/** What the send service is doing. Process-wide, so alarm re-arming and the UI can see in-flight work (T4). */
data class SendState(
    val queuedIds: List<Long> = emptyList(),
    val currentId: Long? = null,
    val phase: SendPhase? = null,
    val attempt: Int = 0,
    /** Schedules whose pending occurrence a queued or running request will use up and re-arm afterwards. */
    val occurrenceHolderIds: Set<Long> = emptySet(),
) {
    /** True while [scheduleId] is waiting in the queue or being sent, whatever the kind of request. */
    fun isBusy(scheduleId: Long): Boolean = scheduleId == currentId || scheduleId in queuedIds

    /**
     * True while a request that uses up [scheduleId]'s occurrence is queued or running: its alarm must
     * then be left alone, since that request re-arms it when done. A test run or an extra copy doesn't
     * count, as neither touches the occurrence (B01).
     */
    fun holdsOccurrence(scheduleId: Long): Boolean = scheduleId in occurrenceHolderIds
}

class SendStateHolder {
    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state.asStateFlow()

    fun update(transform: (SendState) -> SendState) = _state.update(transform)

    fun reset() {
        _state.value = SendState()
    }
}
