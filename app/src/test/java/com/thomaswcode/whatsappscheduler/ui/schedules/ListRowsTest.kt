package com.thomaswcode.whatsappscheduler.ui.schedules

import com.thomaswcode.whatsappscheduler.data.db.Schedule
import com.thomaswcode.whatsappscheduler.data.db.SendStatus
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import com.thomaswcode.whatsappscheduler.sending.SendKind
import com.thomaswcode.whatsappscheduler.sending.SendRequest
import org.junit.Assert.assertEquals
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
    fun batchRowShowsEachOutcomeWhenTheyDiffer() {
        val mum = schedule(1).copy(lastStatus = SendStatus.SENT, lastResult = "SENT")
        val dad = schedule(2, "Dad").copy(lastStatus = SendStatus.FAILED, lastResult = "FAILED: search: Timed out")
        assertEquals(listOf("Mum: SENT", "Dad: FAILED: search: Timed out"), outcomeLines(listOf(mum, dad)))
        assertEquals(listOf("Mum: SENT", "Sam: not sent yet"), outcomeLines(listOf(mum, schedule(3, "Sam"))))
        assertEquals(listOf("Last: SENT"), outcomeLines(listOf(mum, mum.copy(id = 4, targetName = "Sam"))))
        // Both failed, for different reasons: both reasons show.
        val sam = dad.copy(id = 6, targetName = "Sam", lastResult = "FAILED: type: Timed out")
        assertEquals(listOf("Dad: FAILED: search: Timed out", "Sam: FAILED: type: Timed out"), outcomeLines(listOf(dad, sam)))
        assertEquals(emptyList<String>(), outcomeLines(listOf(schedule(5))))
    }
}
