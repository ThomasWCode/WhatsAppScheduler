package com.thomaswcode.whatsappscheduler.sending.accessibility.steps

import org.junit.Assert.assertEquals
import org.junit.Test

/** B26: a biometric prompt's negative button is only pressed for what its text says it does. */
class PromptOfferTest {

    @Test
    fun pinLabelsOfferThePin() {
        listOf("Use PIN", "USE PIN", "Use device PIN", "Use password", "Use screen lock").forEach {
            assertEquals(it, PromptOffer.PIN, promptOffer(it, isUseCredentialId = false))
        }
        assertEquals(PromptOffer.PIN, promptOffer(null, isUseCredentialId = true))
    }

    @Test
    fun oneUiChatLockPromptOffersTheSecretCode() {
        // The prompt a wa.me link to a locked chat raises on One UI 8 / WhatsApp 2.26.
        assertEquals(PromptOffer.SECRET_CODE, promptOffer("Use secret code", isUseCredentialId = false))
        assertEquals(PromptOffer.SECRET_CODE, promptOffer("​Use secret code", isUseCredentialId = false))
    }

    @Test
    fun cancelIsNeverPressed() {
        listOf("Cancel", "Use fingerprint", "", null).forEach {
            assertEquals(it.toString(), PromptOffer.NOTHING, promptOffer(it, isUseCredentialId = false))
        }
    }
}
