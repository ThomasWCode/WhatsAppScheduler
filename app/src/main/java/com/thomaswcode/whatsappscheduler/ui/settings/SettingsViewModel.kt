package com.thomaswcode.whatsappscheduler.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.data.settings.Settings
import com.thomaswcode.whatsappscheduler.domain.PinFailures
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings' state and actions (T2). */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val whileShown = SharingStarted.WhileSubscribed(5_000)

    val settings: StateFlow<Settings> = container.settings.settings.stateIn(viewModelScope, whileShown, Settings())
    val hasPin: StateFlow<Boolean> = container.secrets.hasPin().stateIn(viewModelScope, whileShown, false)
    val hasSecretCode: StateFlow<Boolean> = container.secrets.hasSecretCode().stateIn(viewModelScope, whileShown, false)
    val pinFailures: StateFlow<PinFailures> = container.secrets.observePinFailures().stateIn(viewModelScope, whileShown, PinFailures())

    /** One line of feedback under the tools, e.g. "PIN stored". */
    var status by mutableStateOf<String?>(null)
        private set

    fun showStatus(text: String) {
        status = text
    }

    fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    /** Applies at once: sent messages older than the new period go now, not at the next send or visit. */
    fun setKeepSentDays(days: Int) {
        viewModelScope.launch {
            container.settings.update { it.copy(keepSentDays = days) }
            runCatching { container.housekeeping.run() }.onFailure { container.log.error("Housekeeping failed", it) }
        }
    }

    /** [is24Hour]: the phone's time format, for when unlocking is still paused until a set time. */
    fun savePin(value: String, is24Hour: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            // Clears a rejection (B04). Unlocking may still be paused by the daily limit, in which
            // case the paused notice stays, with the new reason.
            container.secrets.setPin(value)
            val stillBlocked = container.pinGuard.block()
            status = if (stillBlocked == null) {
                container.fallbackNotifier.cancelPinPaused()
                container.text(R.string.settings_pin_saved)
            } else {
                val reason = stillBlocked.describe(is24Hour = is24Hour)
                container.fallbackNotifier.notifyPinPaused(reason)
                container.text(R.string.settings_pin_saved_paused, reason)
            }
            onDone()
        }
    }

    fun removePin(onDone: () -> Unit) {
        viewModelScope.launch {
            container.secrets.setPin(null)
            container.fallbackNotifier.cancelPinPaused()
            status = container.text(R.string.settings_pin_removed)
            onDone()
        }
    }

    fun saveSecretCode(value: String, onDone: () -> Unit) {
        viewModelScope.launch {
            container.secrets.setSecretCode(value)
            onDone()
        }
    }

    fun rearmAlarms() {
        viewModelScope.launch {
            container.alarmScheduler.rescheduleAll()
            status = container.text(R.string.settings_alarms_rearmed)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { SettingsViewModel(container) } }
    }
}
