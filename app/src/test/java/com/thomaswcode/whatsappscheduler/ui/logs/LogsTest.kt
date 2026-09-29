package com.thomaswcode.whatsappscheduler.ui.logs

import com.thomaswcode.whatsappscheduler.data.db.SendLog
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogsTest {

    private fun log(status: SendStatus) = SendLog(scheduleId = 1, atMillis = 0, status = status, targetName = "Mum", detail = null)

    private val all = SendStatus.entries.map(::log)

    @Test
    fun filtersKeepTheirStatuses() {
        assertEquals(SendStatus.entries, all.filter(LogFilter.ALL::keeps).map { it.status })
        assertEquals(listOf(SendStatus.SENT), all.filter(LogFilter.SENT::keeps).map { it.status })
        // Uncertain ones are with the failed: both wait on the user.
        assertEquals(listOf(SendStatus.FAILED, SendStatus.UNCERTAIN), all.filter(LogFilter.FAILED::keeps).map { it.status })
        assertEquals(listOf(SendStatus.MISSED), all.filter(LogFilter.MISSED::keeps).map { it.status })
    }

    @Test
    fun aTraceLineSaysItsLevel() {
        assertEquals('W', traceLevel("09-29 08:00:05.123 W Attempt 1 failed at search: Timed out"))
        assertEquals('E', traceLevel("09-29 08:00:05.123 E Unhandled error in job 3: IllegalStateException: x"))
        assertEquals('I', traceLevel("09-29 08:00:05.123 I Alarm fired for schedule 3"))
        assertNull(traceLevel("not a trace line"))
        assertNull(traceLevel(""))
    }
}
