package com.thomaswcode.whatsappscheduler.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.SchedulerApp
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.sending.accessibility.WaAccessibilityService
import com.thomaswcode.whatsappscheduler.domain.Preflight
import com.thomaswcode.whatsappscheduler.ui.health.HealthCheck
import com.thomaswcode.whatsappscheduler.ui.health.HealthItem
import com.thomaswcode.whatsappscheduler.ui.health.HealthProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The pre-flight check (features F5), a while before a scheduled send: if anything would make it fail
 * (auto-send off or not running, WhatsApp missing, no PIN or PIN entry paused), the user is told while
 * there is still time to fix it. Nothing is said when all is well. One check also covers the sends due
 * in the few minutes after its own ([Preflight.COVERS]).
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
                // Only for the occurrence it was set for: one moved, edited or switched off since has its own.
                if (!stillDue(container.repository.get(id), occurrence)) return@launch
                val problem = HealthCheck.evaluate(
                    context,
                    serviceConnected = WaAccessibilityService.instance.value != null,
                    hasPin = container.secrets.hasPin().first(),
                    pinBlock = container.pinGuard.block(),
                    dryRunUntilMillis = null,
                ).firstOrNull { it.problem.blocksSending && it.problem != HealthProblem.DRY_RUN }
                // This send and any due soon after it, whose own checks Doze could hold back too long.
                container.repository.all()
                    .filter { s -> s.enabled && s.nextAtMillis?.let { Preflight.covers(occurrence, it) } == true }
                    .forEach { publish(container, it, problem) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.log.error("Pre-flight check failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Posts or clears [seen]'s warning, for its send as it is now. The checks take a moment, and a
     * schedule moved, edited or switched off meanwhile has had its warnings cleared by its sync; so it
     * is read again just before, and once more after, taking back a warning that no longer matches the
     * send it names (its time and its chat).
     */
    private suspend fun publish(container: AppContainer, seen: Schedule, problem: HealthItem?) {
        val occurrence = seen.nextAtMillis ?: return
        val current = container.repository.get(seen.id)?.takeIf { stillDue(it, occurrence) } ?: return
        if (problem == null) {
            container.fallbackNotifier.cancelWillFail(current.id)
            return
        }
        container.log.warn("Pre-flight for schedule ${current.id}: ${problem.message}")
        container.fallbackNotifier.notifyWillFail(current, occurrence, problem)
        val after = container.repository.get(current.id)
        if (after == null || !stillDue(after, occurrence) || after.targetName != current.targetName) {
            container.fallbackNotifier.cancelWillFail(current.id)
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
