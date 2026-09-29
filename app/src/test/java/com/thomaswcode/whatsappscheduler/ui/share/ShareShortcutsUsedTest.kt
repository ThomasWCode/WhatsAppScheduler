package com.thomaswcode.whatsappscheduler.ui.share

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.domain.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareShortcutsUsedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val choir = Recipient(id = 12, kind = TargetKind.GROUP, displayName = "Choir")

    @Test
    fun aChatScheduledToIsOfferedEvenBeforeTheRecentsArePublished() {
        ShareShortcuts(context).used(listOf(choir))

        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).single()
        assertEquals("recipient-12", shortcut.id)
        assertEquals("Choir", shortcut.shortLabel)
    }

    @Test
    fun usingAChatAgainKeepsOneShortcutForIt() {
        val shortcuts = ShareShortcuts(context)
        shortcuts.used(listOf(choir))
        shortcuts.used(listOf(choir.copy(displayName = "Choir (Tuesdays)")))

        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).single()
        assertEquals("Choir (Tuesdays)", shortcut.shortLabel)
    }
}
