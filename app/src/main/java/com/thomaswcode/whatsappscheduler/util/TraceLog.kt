package com.thomaswcode.whatsappscheduler.util

/** The automation trace as seen by code that must also run in JVM tests; [AutomationLog] in the app. */
interface TraceLog {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}
