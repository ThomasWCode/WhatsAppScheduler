package com.thomaswcode.whatsappscheduler.ui.schedules

import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.sending.SendKind
import com.thomaswcode.whatsappscheduler.sending.SendPhase
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import com.thomaswcode.whatsappscheduler.sending.SendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListRowsTest {

    private fun schedule(id: Long, name: String = "Mum", next: Long? = 1_000L, recurrence: String = "ONCE") = Schedule(
        id = id, message = "Lunch?", targetKind = TargetKind.CONTACT, targetName = name, phoneE164 = null, isLocked = false,
        firstAtMillis = 1_000L, zoneId = "Europe/London", recurrence = recurrence, nextAtMillis = next,
    )

    @Test
    fun sendNowUsesUpEachPendingMembersOwnOccurrence() {
        // Part-way through a batch: Mum has had it, Dad is still due.
        val mum = schedule(1, next = null)
        val dad = schedule(2, "Dad", next = 5_000L)
        val plan = sendNowPlan(listOf(mum, dad))
        assertEquals(listOf(SendRequest(2, SendKind.SEND_NOW, 5_000L)), plan.early)

        // Nothing pending: sending again means a copy for everyone.
        assertTrue(sendNowPlan(listOf(mum, mum.copy(id = 3))).early.isEmpty())
        assertEquals(listOf(1L, 3L), sendNowPlan(listOf(mum, mum.copy(id = 3))).copies.map { it.scheduleId })

        // A switched-off one-off still uses its time up; a paused series only gets copies.
        assertEquals(1, sendNowPlan(listOf(dad.copy(enabled = false))).early.size)
        assertTrue(sendNowPlan(listOf(dad.copy(recurrence = "DAILY", enabled = false))).early.isEmpty())
    }

    @Test
    fun rowsBeingSentSaySo() {
        val mum = schedule(1)
        val dad = schedule(2, "Dad")
        assertEquals("Sending…", busyText(listOf(mum, dad), SendState(currentId = 2, phase = SendPhase.SENDING)))
        assertEquals("Waiting to try again…", busyText(listOf(mum), SendState(currentId = 1, phase = SendPhase.WAITING_TO_RETRY)))
        assertEquals("Queued to send", busyText(listOf(mum), SendState(queuedIds = listOf(1))))
        assertNull(busyText(listOf(mum), SendState(currentId = 5, queuedIds = listOf(6))))
    }

    @Test
    fun sendingAgainGoesOnlyToWhoItDidntReach() {
        val mum = schedule(1, next = null).copy(lastStatus = SendStatus.SENT)
        val dad = schedule(2, "Dad", next = null).copy(lastStatus = SendStatus.FAILED)
        val sam = schedule(3, "Sam", next = null).copy(lastStatus = SendStatus.UNCERTAIN)
        // Retrying Dad's failure doesn't send Mum, or Sam who may have had it, a second one.
        assertEquals(listOf(2L), sendNowPlan(listOf(mum, dad, sam)).copies.map { it.scheduleId })
        // Only a maybe left: that one, once the chat is checked.
        assertEquals(listOf(3L), sendNowPlan(listOf(mum, sam)).copies.map { it.scheduleId })
        // It reached everyone, a row from before statuses were stored too: sending again is for everyone.
        val oldMum = schedule(4, next = null).copy(lastResult = "manual SENT")
        assertEquals(listOf(1L, 4L), sendNowPlan(listOf(mum, oldMum)).copies.map { it.scheduleId })
        // A series' extra copy is for everyone.
        assertEquals(listOf(1L, 2L), sendNowPlan(listOf(mum, dad).map { it.copy(recurrence = "DAILY") }).copies.map { it.scheduleId })
    }
}
