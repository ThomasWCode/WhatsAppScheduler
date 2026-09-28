package com.thomaswcode.whatsappscheduler.sending

import com.thomaswcode.whatsappscheduler.domain.PinBlock
import com.thomaswcode.whatsappscheduler.domain.PinCircuitBreaker
import com.thomaswcode.whatsappscheduler.domain.PinFailures
import java.time.Clock

/** Where [PinFailures] are kept: next to the PIN in [com.thomaswcode.whatsappscheduler.data.settings.SecretStore]. */
interface PinFailureStore {
    suspend fun pinFailures(): PinFailures
    suspend fun updatePinFailures(transform: (PinFailures) -> PinFailures)
}

/** Applies [PinCircuitBreaker] to the stored failures. Consulted before every PIN entry. */
class PinGuard(private val store: PinFailureStore, private val clock: Clock) {

    suspend fun block(): PinBlock? = PinCircuitBreaker.block(store.pinFailures(), clock.millis())

    suspend fun recordRejection() = store.updatePinFailures { PinCircuitBreaker.afterRejection(it, clock.millis()) }

    suspend fun recordAccepted() = store.updatePinFailures(PinCircuitBreaker::afterAccepted)
}
