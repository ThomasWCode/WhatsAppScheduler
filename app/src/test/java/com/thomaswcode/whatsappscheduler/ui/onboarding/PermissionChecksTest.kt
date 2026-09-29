package com.thomaswcode.whatsappscheduler.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for the Intent each item carries.
@RunWith(RobolectricTestRunner::class)
class PermissionChecksTest {

    private fun item(key: String, state: CheckState, group: SetupGroup) = SetupItem(key, key, "", state, null, group = group)

    @Test
    fun onlyRequiredItemsBlockSending() {
        val done = listOf(item("a", CheckState.OK, SetupGroup.REQUIRED), item("b", CheckState.MANUAL, SetupGroup.MANUAL))
        assertTrue(PermissionChecks.allRequiredOk(done))
        assertTrue(PermissionChecks.allRequiredOk(done + item("c", CheckState.MISSING, SetupGroup.RECOMMENDED)))
        assertFalse(PermissionChecks.allRequiredOk(done + item("d", CheckState.MISSING, SetupGroup.REQUIRED)))
    }

    @Test
    fun theServiceMustBeRunningNotOnlySwitchedOn() {
        assertEquals(CheckState.MISSING, PermissionChecks.accessibility(enabled = false, running = false).state)
        val notRunning = PermissionChecks.accessibility(enabled = true, running = false)
        assertEquals(CheckState.MISSING, notRunning.state)
        assertEquals("Accessibility service running", notRunning.title)
        assertEquals(CheckState.OK, PermissionChecks.accessibility(enabled = true, running = true).state)
    }
}
