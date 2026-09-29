package com.thomaswcode.whatsappscheduler.sending.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.thomaswcode.whatsappscheduler.util.AutomationLog
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thrown when a step cannot make progress; carries the window dump for diagnosis. [retryable] is
 * false when another attempt cannot help, e.g. the phone is locked and no PIN is stored.
 */
class StepFailure(val step: String, message: String, val dump: String? = null, val retryable: Boolean = true) : Exception(message)

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

    /** First match in tree order. Stops walking at the match, since polls call this constantly. */
    fun find(selector: Selector): AccessibilityNodeInfo? = roots().firstNotNullOfOrNull { first(it, selector, 0) }

    private fun first(node: AccessibilityNodeInfo?, selector: Selector, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        if (selector.matches(node)) return node
        for (i in 0 until node.childCount) first(node.getChild(i), selector, depth + 1)?.let { return it }
        return null
    }

    fun findAll(selector: Selector): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        roots().forEach { root -> collect(root, selector, out, 0) }
        return out
    }

    /** The active window is also in the window list, so without this every tree would be walked twice. */
    private fun roots(): List<AccessibilityNodeInfo> = service.allRoots().distinct()

    private fun collect(node: AccessibilityNodeInfo?, selector: Selector, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 60) return
        if (selector.matches(node)) out.add(node)
        for (i in 0 until node.childCount) collect(node.getChild(i), selector, out, depth + 1)
    }

    /** Waits until any of the selectors matches; returns the first match. */
    suspend fun waitForAny(step: String, vararg selectors: Selector, timeoutMs: Long = defaultTimeoutMs): Pair<Selector, AccessibilityNodeInfo> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val hits = scan(selectors)
            hits.indexOfFirst { it != null }.takeIf { it >= 0 }?.let { return selectors[it] to hits[it]!! }
            delay(pollMs)
        }
        fail(step, "Timed out after ${timeoutMs}ms waiting for ${selectors.joinToString { it.name }}")
    }

    suspend fun waitFor(step: String, selector: Selector, timeoutMs: Long = defaultTimeoutMs): AccessibilityNodeInfo =
        waitForAny(step, selector, timeoutMs = timeoutMs).second

    /** Like [waitFor] but returns null instead of failing. */
    suspend fun tryWaitFor(selector: Selector, timeoutMs: Long): AccessibilityNodeInfo? = tryWaitForAny(selector, timeoutMs = timeoutMs)

    /** Like [waitForAny] but returns null instead of failing. */
    suspend fun tryWaitForAny(vararg selectors: Selector, timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findAny(*selectors)?.let { return it }
            delay(pollMs)
        }
        return null
    }

    /** Match of the earliest-listed selector that has one, as if each were tried in order. */
    fun findAny(vararg selectors: Selector): AccessibilityNodeInfo? = scan(selectors).firstOrNull { it != null }

    /** One walk for all the selectors: the first match of each, by position. Ends early once the top-priority one hits. */
    private fun scan(selectors: Array<out Selector>): Array<AccessibilityNodeInfo?> {
        val hits = arrayOfNulls<AccessibilityNodeInfo>(selectors.size)
        if (selectors.isEmpty()) return hits
        for (root in roots()) if (scanNode(root, selectors, hits, 0)) break
        return hits
    }

    private fun scanNode(node: AccessibilityNodeInfo?, selectors: Array<out Selector>, hits: Array<AccessibilityNodeInfo?>, depth: Int): Boolean {
        if (node == null || depth > 60) return false
        for (i in selectors.indices) if (hits[i] == null && selectors[i].matches(node)) hits[i] = node
        if (hits[0] != null) return true
        for (i in 0 until node.childCount) if (scanNode(node.getChild(i), selectors, hits, depth + 1)) return true
        return false
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

    /**
     * Clicks the node or its nearest clickable ancestor, falling back to a tap gesture.
     * [quiet] keeps the node out of the log, for keys that would spell out the PIN.
     */
    suspend fun click(step: String, node: AccessibilityNodeInfo, quiet: Boolean = false) {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                if (!quiet) log.info("$step: clicked ${describe(current)}")
                delay(150)
                return
            }
            current = current.parent
            hops++
        }
        tapNode(step, node, quiet)
    }

    /** Taps the centre of the node with a real touch gesture, for views that ignore ACTION_CLICK. */
    suspend fun tapNode(step: String, node: AccessibilityNodeInfo, quiet: Boolean = false) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) fail(step, if (quiet) "Node has no bounds to tap" else "Node not clickable and has no bounds: ${describe(node)}")
        if (!quiet) log.info("$step: tapping at ${bounds.centerX()},${bounds.centerY()} for ${describe(node)}")
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
        // An empty field reports its hint ("Message") as its text, and WhatsApp's composer does not
        // flag that reliably, so clearing a field is not verified. Invisible characters are ignored:
        // WhatsApp's search box puts a zero-width space in front of whatever is typed into it.
        if (!secret && text.isNotEmpty() && !sameVisibleText(now, text) && !node.isPassword) {
            fail(step, "Text did not stick on ${describe(node)}: got '${showInvisible(now.take(40))}'")
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

    /**
     * The app in front, by its application window: the active window can be a keyboard or the system
     * UI over it, which isn't the app the user is in.
     */
    fun foregroundAppPackage(): String? = runCatching {
        val apps = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        (apps.firstOrNull { it.isFocused || it.isActive } ?: apps.firstOrNull())?.root?.packageName?.toString()
    }.getOrNull()

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
        // Typed input is never dumped: the search box may hold the locked-chats secret code.
        val text = node.text?.toString()?.let { if (node.isEditable) "<${it.length} chars>" else "'${it.replace('\n', ' ').take(60)}'" }
        sb.append("  ".repeat(depth))
            .append(node.className?.toString()?.substringAfterLast('.') ?: "?")
            .append(" id=").append(node.viewIdResourceName ?: "-")
            .append(" text=").append(text ?: "-")
            .append(" desc=").append(node.contentDescription?.toString()?.take(40)?.let { "'$it'" } ?: "-")
            .append(if (node.isClickable) " clickable" else "")
            .append(if (node.isEditable) " editable" else "")
            .append(if (node.isPassword) " password" else "")
            .append(" ").append(b.toShortString())
            .append('\n')
        for (i in 0 until node.childCount) dumpNode(node.getChild(i), depth + 1, sb)
    }

    /** Short label for log lines; typed input is left out, as in [dump]. */
    fun describe(node: AccessibilityNodeInfo): String =
        (node.viewIdResourceName ?: node.className?.toString()?.substringAfterLast('.') ?: "?") +
            (node.text?.toString()?.takeUnless { node.isEditable }?.take(20)?.let { " '$it'" } ?: "") +
            (node.contentDescription?.toString()?.take(20)?.let { " desc='$it'" } ?: "")

    fun fail(step: String, reason: String, retryable: Boolean = true): Nothing {
        val dump = runCatching { dump() }.getOrDefault("(dump failed)")
        log.error("$step: $reason")
        log.saveDump("$step: $reason", dump)
        throw StepFailure(step, reason, dump, retryable)
    }
}
