package com.thomaswcode.whatsappscheduler.ui.logs

import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogsTest {

    @Test
    fun filtersAskForTheirStatuses() {
        assertNull(LogFilter.ALL.statuses)
        assertEquals(setOf(SendStatus.SENT), LogFilter.SENT.statuses)
        // Uncertain ones are with the failed: both wait on the user.
        assertEquals(setOf(SendStatus.FAILED, SendStatus.UNCERTAIN), LogFilter.FAILED.statuses)
        assertEquals(setOf(SendStatus.MISSED), LogFilter.MISSED.statuses)
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
