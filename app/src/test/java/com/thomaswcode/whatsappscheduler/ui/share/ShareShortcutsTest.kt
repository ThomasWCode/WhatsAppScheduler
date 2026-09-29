package com.thomaswcode.whatsappscheduler.ui.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareShortcutsTest {

    @Test
    fun aShortcutStandsForTheRecipientItWasMadeFor() {
        assertEquals("recipient-12", ShareShortcuts.idFor(12))
        assertEquals(12L, ShareShortcuts.recipientId(ShareShortcuts.idFor(12)))
    }

    @Test
    fun anyOtherShareIsForNoOneInParticular() {
        assertNull(ShareShortcuts.recipientId(null))
        assertNull(ShareShortcuts.recipientId("some-other-app-shortcut"))
        assertNull(ShareShortcuts.recipientId("recipient-"))
        assertNull(ShareShortcuts.recipientId("recipient-0"))
        assertNull(ShareShortcuts.recipientId("recipient-12x"))
    }
}
