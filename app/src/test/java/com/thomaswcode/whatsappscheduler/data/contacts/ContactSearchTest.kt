package com.thomaswcode.whatsappscheduler.data.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactSearchTest {

    @Test
    fun whatsAppChatIdsGiveTheNumberTheyAreFor() {
        assertEquals("+447400123456", ContactSearch.whatsAppChatNumber("447400123456@s.whatsapp.net"))
        // Groups, and anything that isn't a person's chat id, are no number.
        assertNull(ContactSearch.whatsAppChatNumber("120363041234567890@g.us"))
        assertNull(ContactSearch.whatsAppChatNumber("Message +44 7400 123456"))
        assertNull(ContactSearch.whatsAppChatNumber("@s.whatsapp.net"))
        assertNull(ContactSearch.whatsAppChatNumber(null))
    }
}
