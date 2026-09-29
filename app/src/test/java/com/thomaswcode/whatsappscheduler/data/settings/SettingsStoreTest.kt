package com.thomaswcode.whatsappscheduler.data.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsStoreTest {

    @Test
    fun recipientsAreLinkedOnceAndEditingSettingsKeepsThatFlag() = runTest {
        val store = SettingsStore(ApplicationProvider.getApplicationContext())
        assertFalse(store.recipientsLinked())
        store.markRecipientsLinked()
        // Settings are written as a whole; that must not wipe the flag, or linking would run again.
        store.update { it.copy(dryRunUntilMillis = Settings.UNTIL_TURNED_OFF) }
        assertTrue(store.recipientsLinked())
        assertTrue(store.current().dryRun)
        // Test mode with an end is off once that has passed.
        store.update { it.copy(dryRunUntilMillis = 1_000L) }
        assertFalse(store.current().dryRun)
        assertTrue(store.current().dryRunAt(999L))
    }
}
