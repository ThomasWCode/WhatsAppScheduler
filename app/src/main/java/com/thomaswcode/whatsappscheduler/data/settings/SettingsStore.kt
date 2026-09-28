package com.thomaswcode.whatsappscheduler.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalTime
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
    /** The quick sheet's "morning" and "evening" chips, as minutes after midnight. */
    val morningMinutes: Int = 9 * 60,
    val eveningMinutes: Int = 20 * 60,
    /** The user's own number in E.164, for the "Me" chip; null hides it. */
    val ownNumberE164: String? = null,
) {
    val morning: LocalTime get() = LocalTime.of(morningMinutes / 60, morningMinutes % 60)
    val evening: LocalTime get() = LocalTime.of(eveningMinutes / 60, eveningMinutes % 60)
}

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
        val morning = intPreferencesKey("morning_minutes")
        val evening = intPreferencesKey("evening_minutes")
        val ownNumber = stringPreferencesKey("own_number_e164")
    }

    val settings: Flow<Settings> = context.settingsDataStore.data.map(::read)

    suspend fun current(): Settings = settings.first()

    /**
     * Applies [transform] to the settings as stored at the moment of writing, inside DataStore's
     * transaction, so two quick changes can't read the same old values and undo each other (B20).
     */
    suspend fun update(transform: (Settings) -> Settings) {
        context.settingsDataStore.edit { p -> write(p, transform(read(p))) }
    }

    private fun read(p: Preferences) = Settings(
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
        morningMinutes = p[Keys.morning] ?: 9 * 60,
        eveningMinutes = p[Keys.evening] ?: 20 * 60,
        ownNumberE164 = p[Keys.ownNumber],
    )

    private fun write(p: MutablePreferences, s: Settings) {
        p[Keys.dryRun] = s.dryRun
        p[Keys.relock] = s.relockAfterSend
        p[Keys.retryCount] = s.retryCount
        p[Keys.retryDelay] = s.retryDelaySeconds
        p[Keys.stepTimeout] = s.stepTimeoutMs
        p[Keys.jobTimeout] = s.jobTimeoutMs
        p[Keys.grace] = s.missedGraceMinutes
        p[Keys.deepLinkLocked] = s.deepLinkForLockedChats
        p[Keys.countryIso] = s.defaultCountryIso
        p[Keys.onboardingDone] = s.onboardingDone
        p[Keys.morning] = s.morningMinutes
        p[Keys.evening] = s.eveningMinutes
        s.ownNumberE164?.let { p[Keys.ownNumber] = it } ?: p.remove(Keys.ownNumber)
    }
}
