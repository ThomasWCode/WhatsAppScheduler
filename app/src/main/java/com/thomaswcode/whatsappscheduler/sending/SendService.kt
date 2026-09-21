package com.thomaswcode.whatsappscheduler.sending

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.thomaswcode.whatsappscheduler.SchedulerApp
import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.SendJob
import com.thomaswcode.whatsappscheduler.domain.SendResult
import com.thomaswcode.whatsappscheduler.sending.accessibility.AccessibilitySender
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that works through due schedules one at a time, with retries, and records
 * the outcome. Started by alarms, by boot recovery, and by "send now" from the UI.
 */
class SendService : LifecycleService() {

    private data class Request(val scheduleId: Long, val advance: Boolean, val forceDryRun: Boolean)

    private val queue = ConcurrentLinkedQueue<Request>()
    private var worker: Job? = null
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private val container get() = SchedulerApp.container(this)
    private val sender: MessageSender by lazy {
        AccessibilitySender(this, container.settings, container.secrets, container.log, container.notifications)
    }

    override fun onCreate() {
        super.onCreate()
        val notification = container.notifications.sending("Preparing…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.ID_SENDING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_SENDING, notification)
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WaScheduler:send")
            .apply { acquire(10 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        val id = intent?.getLongExtra(EXTRA_SCHEDULE_ID, -1L) ?: -1L
        if (intent != null && id >= 0) {
            queue.add(Request(id, intent.getBooleanExtra(EXTRA_ADVANCE, true), intent.getBooleanExtra(EXTRA_FORCE_DRY_RUN, false)))
        }
        if (worker?.isActive != true) worker = lifecycleScope.launch { drain() }
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        while (true) {
            val request = queue.poll()
            if (request == null) {
                if (stopSelfResult(lastStartId)) return else continue
            }
            runCatching { process(request) }.onFailure { container.log.error("Unhandled error in job ${request.scheduleId}", it) }
        }
    }

    private suspend fun process(request: Request) {
        val log = container.log
        val schedule = container.repository.get(request.scheduleId)
        if (schedule == null) {
            log.warn("Schedule ${request.scheduleId} no longer exists")
            return
        }
        if (!schedule.enabled && request.advance) {
            log.info("Schedule ${schedule.id} is disabled; skipping")
            return
        }
        val settings = container.settings.current()
        val dryRun = settings.dryRun || request.forceDryRun
        updateNotification("Sending to ${schedule.targetName}…")

        var result: SendResult = SendResult.Failed("init", "not attempted")
        for (attempt in 1..(settings.retryCount + 1)) {
            result = sender.send(SendJob(schedule.id, schedule.target, schedule.message, dryRun, attempt))
            if (result !is SendResult.Failed) break
            log.warn("Attempt $attempt failed at ${result.step}: ${result.reason}")
            if (attempt <= settings.retryCount) {
                updateNotification("Retrying ${schedule.targetName} in ${settings.retryDelaySeconds}s…")
                delay(settings.retryDelaySeconds * 1000L)
            }
        }
        finish(schedule, result, request.advance)
    }

    private suspend fun finish(schedule: Schedule, result: SendResult, advance: Boolean) {
        val (status, detail) = when (result) {
            SendResult.Sent -> SendStatus.SENT to null
            SendResult.DryRun -> SendStatus.DRY_RUN to "composer filled, send not tapped"
            is SendResult.Failed -> SendStatus.FAILED to "${result.step}: ${result.reason}"
        }
        if (advance) {
            val updated = container.repository.recordRun(schedule, status, detail)
            container.alarmScheduler.sync(updated)
        } else {
            container.repository.recordManual(schedule, status, detail)
        }
        if (result is SendResult.Failed && !(result.step == "precheck" && !advance)) {
            container.fallbackNotifier.notify(schedule, result.reason)
        }
    }

    private fun updateNotification(text: String) =
        container.notifications.show(Notifications.ID_SENDING, container.notifications.sending(text))

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_ADVANCE = "advance"
        const val EXTRA_FORCE_DRY_RUN = "force_dry_run"

        /** Queues a schedule for sending; the service is started in the foreground if needed. */
        fun enqueue(context: Context, scheduleId: Long, advance: Boolean = true, forceDryRun: Boolean = false) {
            val intent = Intent(context, SendService::class.java)
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                .putExtra(EXTRA_ADVANCE, advance)
                .putExtra(EXTRA_FORCE_DRY_RUN, forceDryRun)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
