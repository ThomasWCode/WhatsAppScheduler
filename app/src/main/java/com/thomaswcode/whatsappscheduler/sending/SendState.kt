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
) {
    /** True while [scheduleId] is waiting in the queue or being sent. */
    fun isBusy(scheduleId: Long): Boolean = scheduleId == currentId || scheduleId in queuedIds
}

class SendStateHolder {
    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state.asStateFlow()

    fun update(transform: (SendState) -> SendState) = _state.update(transform)

    fun reset() {
        _state.value = SendState()
    }
}
