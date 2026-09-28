package com.thomaswcode.whatsappscheduler.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.thomaswcode.whatsappscheduler.domain.PinCircuitBreaker
import com.thomaswcode.whatsappscheduler.domain.PinFailures
import com.thomaswcode.whatsappscheduler.sending.PinFailureStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

/**
 * Encrypted storage for the lock-screen PIN and the WhatsApp Chat Lock secret code, plus the
 * record of PIN entries the phone rejected (B04), which lives next to the PIN so saving a new PIN
 * clears the rejection in the same write.
 */
class SecretStore(private val context: Context, private val crypto: CryptoBox) : PinFailureStore {

    private val pinKey = stringPreferencesKey("pin")
    private val secretCodeKey = stringPreferencesKey("secret_code")
    private val pinRejectedAtKey = longPreferencesKey("pin_rejected_at")
    private val pinFailuresKey = stringPreferencesKey("pin_failures")

    fun hasPin() = context.secretsDataStore.data.map { it[pinKey] != null }

    suspend fun pin(): String? = context.secretsDataStore.data.first()[pinKey]?.let(crypto::decrypt)

    /** Stores [pin], or removes it when null or empty. Either way a previous rejection is cleared. */
    suspend fun setPin(pin: String?, nowMillis: Long = System.currentTimeMillis()) {
        context.secretsDataStore.edit { prefs ->
            if (pin.isNullOrEmpty()) prefs.remove(pinKey) else prefs[pinKey] = crypto.encrypt(pin)
            writeFailures(prefs, PinCircuitBreaker.afterPinSaved(readFailures(prefs), nowMillis))
        }
    }

    fun hasSecretCode() = context.secretsDataStore.data.map { it[secretCodeKey] != null }

    suspend fun secretCode(): String? = context.secretsDataStore.data.first()[secretCodeKey]?.let(crypto::decrypt)

    suspend fun setSecretCode(code: String?) {
        context.secretsDataStore.edit { prefs ->
            if (code.isNullOrEmpty()) prefs.remove(secretCodeKey) else prefs[secretCodeKey] = crypto.encrypt(code)
        }
    }

    fun observePinFailures(): Flow<PinFailures> = context.secretsDataStore.data.map(::readFailures)

    override suspend fun pinFailures(): PinFailures = readFailures(context.secretsDataStore.data.first())

    override suspend fun updatePinFailures(transform: (PinFailures) -> PinFailures) {
        context.secretsDataStore.edit { prefs -> writeFailures(prefs, transform(readFailures(prefs))) }
    }

    private fun readFailures(prefs: Preferences) = PinFailures(
        rejectedAtMillis = prefs[pinRejectedAtKey],
        recentMillis = prefs[pinFailuresKey]?.split(',')?.mapNotNull(String::toLongOrNull) ?: emptyList(),
    )

    private fun writeFailures(prefs: MutablePreferences, failures: PinFailures) {
        failures.rejectedAtMillis?.let { prefs[pinRejectedAtKey] = it } ?: prefs.remove(pinRejectedAtKey)
        if (failures.recentMillis.isEmpty()) prefs.remove(pinFailuresKey) else prefs[pinFailuresKey] = failures.recentMillis.joinToString(",")
    }
}
