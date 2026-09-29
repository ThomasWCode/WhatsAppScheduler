package com.thomaswcode.whatsappscheduler.util

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Step-by-step trace of automation runs, kept in a bounded file and mirrored to an in-memory
 * flow for the log screen. Never receives the PIN or secret code.
 */
class AutomationLog(context: Context) : TraceLog {

    private val file = File(context.filesDir, "automation.log")
    private val dumpFile = File(context.filesDir, "last_window_dump.txt")
    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    private val _lines = MutableStateFlow(readAll())
    val lines: StateFlow<List<String>> = _lines

    /** Appends one line with a timestamp. */
    override fun info(message: String) = append("I", message)

    override fun warn(message: String) = append("W", message)

    override fun error(message: String, throwable: Throwable?) =
        append("E", if (throwable != null) "$message: ${throwable.javaClass.simpleName}: ${throwable.message}" else message)

    private fun append(level: String, message: String) {
        val line = "${LocalDateTime.now().format(formatter)} $level $message"
        Log.println(if (level == "E") Log.ERROR else if (level == "W") Log.WARN else Log.INFO, TAG, message)
        synchronized(lock) {
            runCatching {
                file.appendText(line + "\n")
                if (file.length() > MAX_BYTES) trim()
            }
            _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        }
    }

    /** Stores the latest accessibility window tree so it can be inspected after a failure. */
    fun saveDump(title: String, dump: String) {
        synchronized(lock) {
            runCatching { dumpFile.writeText("# $title\n# ${LocalDateTime.now()}\n\n$dump") }
        }
    }

    fun lastDump(): String = runCatching { dumpFile.readText() }.getOrDefault("")

    /** Clears the trace and the saved window dump, which holds chat names and message previews (B27). */
    fun clear() {
        synchronized(lock) {
            runCatching { file.delete() }
            runCatching { dumpFile.delete() }
            _lines.value = emptyList()
        }
    }

    private fun trim() {
        val kept = file.readLines().takeLast(MAX_LINES)
        file.writeText(kept.joinToString("\n") + "\n")
    }

    private fun readAll(): List<String> = runCatching { file.readLines().takeLast(MAX_LINES) }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "WaScheduler"
        private const val MAX_BYTES = 512 * 1024L
        private const val MAX_LINES = 2000
    }
}
