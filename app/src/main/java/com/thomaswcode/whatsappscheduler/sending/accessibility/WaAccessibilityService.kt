package com.thomaswcode.whatsappscheduler.sending.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single accessibility service. It stays passive: events are ignored unless a send job has
 * marked it active, so it costs nothing while idle. All UI work goes through [UiDriver].
 */
class WaAccessibilityService : AccessibilityService() {

    val active = AtomicBoolean(false)

    @Volatile
    var lastEventPackage: String? = null
        private set

    @Volatile
    var lastEventAtMillis: Long = 0L
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!active.get() || event == null) return
        lastEventPackage = event.packageName?.toString()
        lastEventAtMillis = System.currentTimeMillis()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance.value = null
        super.onDestroy()
    }

    /** Roots of every visible window plus the active one; duplicates are harmless for searching. */
    fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        runCatching { windows.forEach { w -> w.root?.let(roots::add) } }
        runCatching { rootInActiveWindow?.let(roots::add) }
        return roots
    }

    companion object {
        val instance = MutableStateFlow<WaAccessibilityService?>(null)

        val connected: StateFlow<WaAccessibilityService?> get() = instance

        /** True when the user has enabled this service in system accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, WaAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) || it.equals(ComponentName(context, WaAccessibilityService::class.java).flattenToShortString(), ignoreCase = true) }
        }
    }
}
