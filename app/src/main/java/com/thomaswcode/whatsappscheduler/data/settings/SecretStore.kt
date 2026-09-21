package com.thomaswcode.whatsappscheduler.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

/** Encrypted storage for the lock-screen PIN and the WhatsApp Chat Lock secret code. */
class SecretStore(private val context: Context, private val crypto: CryptoBox) {

    private val pinKey = stringPreferencesKey("pin")
    private val secretCodeKey = stringPreferencesKey("secret_code")

    fun hasPin() = context.secretsDataStore.data.map { it[pinKey] != null }

    suspend fun pin(): String? = context.secretsDataStore.data.first()[pinKey]?.let(crypto::decrypt)

    suspend fun setPin(pin: String?) {
        context.secretsDataStore.edit { prefs ->
            if (pin.isNullOrEmpty()) prefs.remove(pinKey) else prefs[pinKey] = crypto.encrypt(pin)
        }
    }

    fun hasSecretCode() = context.secretsDataStore.data.map { it[secretCodeKey] != null }

    suspend fun secretCode(): String? = context.secretsDataStore.data.first()[secretCodeKey]?.let(crypto::decrypt)

    suspend fun setSecretCode(code: String?) {
        context.secretsDataStore.edit { prefs ->
            if (code.isNullOrEmpty()) prefs.remove(secretCodeKey) else prefs[secretCodeKey] = crypto.encrypt(code)
        }
    }
}
