package com.thomaswcode.whatsappscheduler.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val dryRun: Boolean = false,
    val relockAfterSend: Boolean = true,
    val retryCount: Int = 2,
    val retryDelaySeconds: Int = 30,
    val stepTimeoutMs: Long = 8_000,
    val jobTimeoutMs: Long = 90_000,
    val missedGraceMinutes: Int = 120,
    val deepLinkForLockedChats: Boolean = false,
    val defaultCountryIso: String = "GB",
    val onboardingDone: Boolean = false,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val dryRun = booleanPreferencesKey("dry_run")
        val relock = booleanPreferencesKey("relock_after_send")
        val retryCount = intPreferencesKey("retry_count")
        val retryDelay = intPreferencesKey("retry_delay_seconds")
        val stepTimeout = longPreferencesKey("step_timeout_ms")
        val jobTimeout = longPreferencesKey("job_timeout_ms")
        val grace = intPreferencesKey("missed_grace_minutes")
        val deepLinkLocked = booleanPreferencesKey("deep_link_locked")
        val countryIso = stringPreferencesKey("default_country_iso")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
    }

    val settings: Flow<Settings> = context.settingsDataStore.data.map { p ->
        Settings(
            dryRun = p[Keys.dryRun] ?: false,
            relockAfterSend = p[Keys.relock] ?: true,
            retryCount = p[Keys.retryCount] ?: 2,
            retryDelaySeconds = p[Keys.retryDelay] ?: 30,
            stepTimeoutMs = p[Keys.stepTimeout] ?: 8_000,
            jobTimeoutMs = p[Keys.jobTimeout] ?: 90_000,
            missedGraceMinutes = p[Keys.grace] ?: 120,
            deepLinkForLockedChats = p[Keys.deepLinkLocked] ?: false,
            defaultCountryIso = p[Keys.countryIso] ?: "GB",
            onboardingDone = p[Keys.onboardingDone] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        val next = transform(current())
        context.settingsDataStore.edit { p ->
            p[Keys.dryRun] = next.dryRun
            p[Keys.relock] = next.relockAfterSend
            p[Keys.retryCount] = next.retryCount
            p[Keys.retryDelay] = next.retryDelaySeconds
            p[Keys.stepTimeout] = next.stepTimeoutMs
            p[Keys.jobTimeout] = next.jobTimeoutMs
            p[Keys.grace] = next.missedGraceMinutes
            p[Keys.deepLinkLocked] = next.deepLinkForLockedChats
            p[Keys.countryIso] = next.defaultCountryIso
            p[Keys.onboardingDone] = next.onboardingDone
        }
    }
}
