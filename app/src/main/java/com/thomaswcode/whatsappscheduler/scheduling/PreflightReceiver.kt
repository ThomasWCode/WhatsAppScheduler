package com.thomaswcode.whatsappscheduler.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.SchedulerApp
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.sending.accessibility.WaAccessibilityService
import com.thomaswcode.whatsappscheduler.ui.health.HealthCheck
import com.thomaswcode.whatsappscheduler.ui.health.HealthProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The pre-flight check (features F5), a while before a scheduled send: if anything would make it fail
 * (auto-send off or not running, WhatsApp missing, no PIN or PIN entry paused), the user is told while
 * there is still time to fix it. Nothing is said when all is well.
 */
class PreflightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PREFLIGHT) return
        val id = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L).takeIf { it >= 0 } ?: return
        val occurrence = intent.getLongExtra(EXTRA_OCCURRENCE_AT, -1L).takeIf { it >= 0 } ?: return
        val container = SchedulerApp.container(context)
        val pending = goAsync()
        container.appScope.launch {
            try {
                val schedule = container.repository.get(id)
                // Only for the occurrence it was set for: one moved, edited or switched off since has its own.
                if (schedule == null || !stillDue(schedule, occurrence)) return@launch
                val problem = HealthCheck.evaluate(
                    context,
                    serviceConnected = WaAccessibilityService.instance.value != null,
                    hasPin = container.secrets.hasPin().first(),
                    pinBlock = container.pinGuard.block(),
                    dryRunUntilMillis = null,
                ).firstOrNull { it.problem.blocksSending && it.problem != HealthProblem.DRY_RUN }
                // The checks take a moment: a schedule moved, edited or switched off meanwhile has had its
                // warnings cleared already, and mustn't get one about a send that is no longer due.
                if (!stillDue(container.repository.get(id), occurrence)) return@launch
                if (problem == null) {
                    container.fallbackNotifier.cancelWillFail(id)
                } else {
                    container.log.warn("Pre-flight for schedule $id: ${problem.message}")
                    container.fallbackNotifier.notifyWillFail(schedule, occurrence, problem)
                    // And one changed just as it was posted: taken back.
                    if (!stillDue(container.repository.get(id), occurrence)) container.fallbackNotifier.cancelWillFail(id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.log.error("Pre-flight check failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun stillDue(schedule: Schedule?, occurrence: Long): Boolean =
        schedule != null && schedule.enabled && schedule.nextAtMillis == occurrence

    companion object {
        const val ACTION_PREFLIGHT = "com.thomaswcode.whatsappscheduler.PREFLIGHT"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_OCCURRENCE_AT = "occurrence_at"
    }
}
