package com.thomaswcode.whatsappscheduler.ui.schedules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.settings.Settings
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import com.thomaswcode.whatsappscheduler.sending.SendState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The schedule list's state and actions (T2). */
class ScheduleListViewModel(private val container: AppContainer) : ViewModel() {

    private val whileShown = SharingStarted.WhileSubscribed(5_000)

    val schedules: StateFlow<List<Schedule>> = container.repository.observeSchedules().stateIn(viewModelScope, whileShown, emptyList())
    val settings: StateFlow<Settings?> = container.settings.settings.stateIn(viewModelScope, whileShown, null)
    val hasPin: StateFlow<Boolean> = container.secrets.hasPin().stateIn(viewModelScope, whileShown, true)

    /** What the send service is doing, for rows being sent right now. */
    val sendState: StateFlow<SendState> = container.sendState.state

    fun setEnabled(schedule: Schedule, enabled: Boolean) {
        viewModelScope.launch {
            container.repository.setEnabled(schedule.id, enabled)?.let(container.alarmScheduler::sync)
        }
    }

    fun delete(schedule: Schedule) {
        viewModelScope.launch {
            container.alarmScheduler.cancel(schedule.id)
            container.repository.delete(schedule)
            // Its Tap to send prompt would otherwise outlive it (B10).
            container.fallbackNotifier.cancel(schedule.id)
        }
    }

    fun send(request: SendRequest) = container.send(request)

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { ScheduleListViewModel(container) } }
    }
}
