package com.thomaswcode.whatsappscheduler.ui.quick

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.thomaswcode.whatsappscheduler.AppContainer
import com.thomaswcode.whatsappscheduler.data.ScheduleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The quick sheet's error line: a change to the To field clears a problem it has answered, as choosing
 * a time does. Robolectric, for the app's own strings and the platform's number parsing; nothing here
 * touches the database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QuickScheduleViewModelTest {

    private val vm = QuickScheduleViewModel(AppContainer(ApplicationProvider.getApplicationContext()), SavedStateHandle())

    private val refused = "07700 900123 isn't a number WhatsApp can open. Type it with its country code."

    @Test
    fun addingAChatClearsARefusedNumber() {
        vm.addTyped("07700 900123")
        assertEquals(refused, vm.error)

        vm.addTyped("Book club")

        assertEquals(listOf("Book club"), vm.draft.recipients.map { it.chatName })
        assertNull(vm.error)
    }

    @Test
    fun addingAChatClearsChooseWhoItIsFor() {
        vm.schedule { it.toString() }
        assertEquals("Choose who it's for.", vm.error)

        vm.addTyped("Book club")

        assertNull(vm.error)
    }

    @Test
    fun takingOneOutClearsTheLimit() {
        repeat(ScheduleRepository.MAX_BATCH) { vm.addTyped("Club $it") }
        vm.addTyped("One too many")
        assertEquals("One message can go to at most 10 chats.", vm.error)

        vm.remove(vm.draft.recipients.first())

        assertEquals(ScheduleRepository.MAX_BATCH - 1, vm.draft.recipients.size)
        assertNull(vm.error)
    }

    @Test
    fun aChatAlreadyThereChangesNothing() {
        vm.addTyped("Book club")
        vm.addTyped("07700 900123")

        vm.addTyped("Book club")

        // Nothing was added, so the refused number is still the last thing that happened.
        assertEquals(listOf("Book club"), vm.draft.recipients.map { it.chatName })
        assertEquals(refused, vm.error)
    }
}
