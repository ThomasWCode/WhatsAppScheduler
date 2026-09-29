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
    fun theRecentsAreRankedMostRecentFirstAndTheRestGo() {
        val shortcuts = ShareShortcuts(context)
        val dad = Recipient(id = 3, kind = TargetKind.CONTACT, displayName = "Dad", phoneE164 = "+447700900123")
        shortcuts.publish(listOf(choir, dad))
        assertEquals(
            listOf("recipient-12" to 0, "recipient-3" to 1),
            ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id to it.rank }.sortedBy { it.second },
        )

        shortcuts.publish(listOf(dad))
        assertEquals(listOf("recipient-3" to 0), ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id to it.rank })
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
