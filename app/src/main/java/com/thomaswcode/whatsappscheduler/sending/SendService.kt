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
import com.thomaswcode.whatsappscheduler.sending.accessibility.AccessibilitySender
import java.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive and the CPU awake while [SendQueueProcessor]
 * works through due schedules. Started by alarms, by boot recovery, and by the list's send actions.
 */
class SendService : LifecycleService() {

    private var worker: Job? = null
    private var lastStartId = 0

    /** Covers the gap between being started and the first job taking its own wake lock. */
    private var startupLock: PowerManager.WakeLock? = null

    private val container get() = SchedulerApp.container(this)

    private val processor: SendQueueProcessor by lazy {
        val c = container
        SendQueueProcessor(
            store = c.repository,
            // Under the same lock as "Check this chat now", so the two never drive WhatsApp at once.
            sender = LockedSender(AccessibilitySender(this, c.settings, c.secrets, c.pinGuard, c.log, c.notifications), c.automationLock),
            alarms = c.alarmScheduler,
            notifier = ServiceNotifier(c.notifications, c.fallbackNotifier),
            pinGuard = c.pinGuard,
            state = c.sendState,
            log = c.log,
            settings = { c.settings.current() },
            clock = Clock.systemDefaultZone(),
            jobGuard = WakeLockGuard(getSystemService(PowerManager::class.java)),
        )
    }

    override fun onCreate() {
        super.onCreate()
        val notification = container.notifications.sending("Preparing…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.ID_SENDING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_SENDING, notification)
        }
        startupLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WaScheduler:start")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        startupLock?.acquire(STARTUP_LOCK_MS)
        intent?.let(::requestFrom)?.let(processor::enqueue)
        if (worker?.isActive != true) worker = lifecycleScope.launch { drainAndStop() }
        return START_NOT_STICKY
    }

    private suspend fun drainAndStop() {
        while (true) {
            processor.drain()
            // The newest start so far. One arriving during housekeeping queues a request this loop must
            // still drain: stopping with this id then fails, as it is no longer the newest.
            val startId = lastStartId
            // With the queue empty: clear old sent messages and history (B28).
            runCatching { container.housekeeping.run() }.onFailure { container.log.error("Housekeeping failed", it) }
            if (stopSelfResult(startId)) return
            // A start is on its way (the system has issued it, onStartCommand hasn't run yet). Yield the
            // main thread so it can be delivered, instead of spinning here and blocking it.
            delay(START_POLL_MS)
        }
    }

    override fun onDestroy() {
        startupLock?.let { if (it.isHeld) it.release() }
        container.sendState.reset()
        super.onDestroy()
    }

    private fun requestFrom(intent: Intent): SendRequest? {
        val id = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L).takeIf { it >= 0 } ?: return null
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { name -> SendKind.entries.firstOrNull { it.name == name } } ?: SendKind.SCHEDULED
        val occurrence = intent.getLongExtra(EXTRA_OCCURRENCE_AT, -1L).takeIf { it >= 0 }
        return SendRequest(id, kind, occurrence)
    }

    /** A partial wake lock per job, sized to that job's worst case, released when it ends (B12). */
    private class WakeLockGuard(private val power: PowerManager) : JobGuard {
        override suspend fun around(budgetMs: Long, block: suspend () -> Unit) {
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WaScheduler:send").apply {
                setReferenceCounted(false)
                acquire(budgetMs)
            }
            try {
                block()
            } finally {
                if (lock.isHeld) lock.release()
            }
        }
    }

    private class ServiceNotifier(private val notifications: Notifications, private val fallback: FallbackNotifier) : SendNotifier {
        override fun progress(text: String) = notifications.show(Notifications.ID_SENDING, notifications.sending(text))
        // Only a real send makes an earlier Tap to send prompt stale; a dry run sent nothing (B10).
        override fun sent(schedule: Schedule, dryRun: Boolean) {
            if (!dryRun) fallback.cancel(schedule.id)
        }
        override fun failed(schedule: Schedule, occurrenceAtMillis: Long?, reason: String) = fallback.notifyFailed(schedule, occurrenceAtMillis, reason)
        override fun uncertain(schedule: Schedule, occurrenceAtMillis: Long?, reason: String) = fallback.notifyUncertain(schedule, occurrenceAtMillis, reason)
        override fun pinPaused(reason: String) = fallback.notifyPinPaused(reason)
        override fun missed(schedule: Schedule, occurrenceAtMillis: Long) = fallback.notifyMissed(schedule, occurrenceAtMillis)
    }

    companion object {
        private const val EXTRA_SCHEDULE_ID = "schedule_id"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_OCCURRENCE_AT = "occurrence_at"
        private const val STARTUP_LOCK_MS = 60_000L
        private const val START_POLL_MS = 100L

        /** Queues a send; the service is started in the foreground if needed. */
        fun enqueue(context: Context, request: SendRequest) {
            val intent = Intent(context, SendService::class.java)
                .putExtra(EXTRA_SCHEDULE_ID, request.scheduleId)
                .putExtra(EXTRA_KIND, request.kind.name)
            request.occurrenceAtMillis?.let { intent.putExtra(EXTRA_OCCURRENCE_AT, it) }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
