package com.thomaswcode.whatsappscheduler.sending.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/** Thrown when a step cannot make progress; carries the window dump for diagnosis. */
class StepFailure(val step: String, message: String, val dump: String? = null) : Exception(message)

/**
 * Thin, polling wrapper over the accessibility APIs. Every wait has a timeout and every failure
 * captures a tree dump, because a UI script that fails silently is unmaintainable.
 */
class UiDriver(
    val service: WaAccessibilityService,
    private val log: AutomationLog,
    private val defaultTimeoutMs: Long,
) {
    private val pollMs = 200L

    fun find(selector: Selector): AccessibilityNodeInfo? = findAll(selector).firstOrNull()

    fun findAll(selector: Selector): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        service.allRoots().forEach { root -> collect(root, selector, out, 0) }
        return out
    }

    private fun collect(node: AccessibilityNodeInfo?, selector: Selector, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 60) return
        if (selector.matches(node)) out.add(node)
        for (i in 0 until node.childCount) collect(node.getChild(i), selector, out, depth + 1)
    }

    /** Waits until any of the selectors matches; returns the first match. */
    suspend fun waitForAny(step: String, vararg selectors: Selector, timeoutMs: Long = defaultTimeoutMs): Pair<Selector, AccessibilityNodeInfo> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (s in selectors) find(s)?.let { return s to it }
            delay(pollMs)
        }
        fail(step, "Timed out after ${timeoutMs}ms waiting for ${selectors.joinToString { it.name }}")
    }

    suspend fun waitFor(step: String, selector: Selector, timeoutMs: Long = defaultTimeoutMs): AccessibilityNodeInfo =
        waitForAny(step, selector, timeoutMs = timeoutMs).second

    /** Like [waitFor] but returns null instead of failing. */
    suspend fun tryWaitFor(selector: Selector, timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            find(selector)?.let { return it }
            delay(pollMs)
        }
        return null
    }

    suspend fun waitUntilGone(selector: Selector, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (find(selector) == null) return true
            delay(pollMs)
        }
        return false
    }

    /** Waits until [condition] holds, polling; returns false on timeout. */
    suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(pollMs)
        }
        return condition()
    }

    /** Clicks the node or its nearest clickable ancestor, falling back to a tap gesture. */
    suspend fun click(step: String, node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log.info("$step: clicked ${describe(current)}")
                delay(150)
                return
            }
            current = current.parent
            hops++
        }
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) fail(step, "Node not clickable and has no bounds: ${describe(node)}")
        log.info("$step: tapping at ${bounds.centerX()},${bounds.centerY()} for ${describe(node)}")
        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    suspend fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build())
        delay(150)
    }

    suspend fun swipeUp() {
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.8f)
            lineTo(x, metrics.heightPixels * 0.3f)
        }
        dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 250)).build())
        delay(400)
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val ok = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            },
            null,
        )
        if (!ok && cont.isActive) cont.resume(false)
    }

    /** Replaces the text of an editable node and verifies it took. */
    suspend fun setText(step: String, node: AccessibilityNodeInfo, text: String, secret: Boolean = false) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) fail(step, "ACTION_SET_TEXT rejected by ${describe(node)}")
        delay(200)
        node.refresh()
        val now = node.text?.toString() ?: ""
        if (!secret && now != text && !node.isPassword) {
            fail(step, "Text did not stick on ${describe(node)}: got '${now.take(40)}'")
        }
        log.info("$step: set text on ${describe(node)}" + if (secret) " (secret)" else " (${text.length} chars)")
    }

    /** Presses the IME action on an editable node (API 30+), returning whether it was accepted. */
    fun imeEnter(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)

    suspend fun back() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(350)
    }

    suspend fun home() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(350)
    }

    fun lockScreen(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

    fun foregroundPackage(): String? = service.rootInActiveWindow?.packageName?.toString()

    /** Full tree of every window, one node per line, for logs and the inspector. */
    fun dump(): String {
        val sb = StringBuilder()
        service.allRoots().distinctBy { it.hashCode() }.forEachIndexed { i, root ->
            sb.append("== window $i pkg=${root.packageName} ==\n")
            dumpNode(root, 0, sb)
        }
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (node == null || depth > 60) return
        val b = Rect().also { node.getBoundsInScreen(it) }
        sb.append("  ".repeat(depth))
            .append(node.className?.toString()?.substringAfterLast('.') ?: "?")
            .append(" id=").append(node.viewIdResourceName ?: "-")
            .append(" text=").append(node.text?.toString()?.replace('\n', ' ')?.take(60)?.let { "'$it'" } ?: "-")
            .append(" desc=").append(node.contentDescription?.toString()?.take(40)?.let { "'$it'" } ?: "-")
            .append(if (node.isClickable) " clickable" else "")
            .append(if (node.isEditable) " editable" else "")
            .append(if (node.isPassword) " password" else "")
            .append(" ").append(b.toShortString())
            .append('\n')
        for (i in 0 until node.childCount) dumpNode(node.getChild(i), depth + 1, sb)
    }

    fun describe(node: AccessibilityNodeInfo): String =
        (node.viewIdResourceName ?: node.className?.toString()?.substringAfterLast('.') ?: "?") +
            (node.text?.toString()?.take(20)?.let { " '$it'" } ?: "") +
            (node.contentDescription?.toString()?.take(20)?.let { " desc='$it'" } ?: "")

    fun fail(step: String, reason: String): Nothing {
        val dump = runCatching { dump() }.getOrDefault("(dump failed)")
        log.error("$step: $reason")
        log.saveDump("$step: $reason", dump)
        throw StepFailure(step, reason, dump)
    }
}
