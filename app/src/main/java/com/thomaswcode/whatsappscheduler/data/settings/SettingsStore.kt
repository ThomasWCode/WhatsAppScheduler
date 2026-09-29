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
    /**
     * Test mode (improvements I6): until when every send is a dry run, typed but never sent. Null is
     * off; [UNTIL_TURNED_OFF] stays on; any other time switches it off by itself then (B11).
     */
    val dryRunUntilMillis: Long? = null,
    val relockAfterSend: Boolean = true,
    val retryCount: Int = 2,
    val retryDelaySeconds: Int = 30,
    val stepTimeoutMs: Long = 8_000,
    val jobTimeoutMs: Long = 90_000,
    /** How late a send may still go by itself (B09); later than that it is held and the user asked. */
    val missedGraceMinutes: Int = DEFAULT_GRACE_MINUTES,
    /** How long the countdown before a scheduled send lasts while the phone is in use (features F1): 5–30 s. */
    val headsUpSeconds: Int = 10,
    val deepLinkForLockedChats: Boolean = false,
    val defaultCountryIso: String = "GB",
    val onboardingDone: Boolean = false,
    /** The quick sheet's "morning" and "evening" chips, as minutes after midnight. */
    val morningMinutes: Int = 9 * 60,
    val eveningMinutes: Int = 20 * 60,
    /** The user's own number in E.164, for the "Me" chip; null hides it. */
    val ownNumberE164: String? = null,
    /** Sent one-offs are cleared this many days after sending; 0 keeps them (quick-schedule.md §8). */
    val keepSentDays: Int = 30,
    /** A quiet "Sent to Mum" notification after each real send (features F2); dry runs always say so. */
    val notifyAfterSend: Boolean = true,
) {
    /** Whether test mode is on at [nowMillis]. */
    fun dryRunAt(nowMillis: Long): Boolean = dryRunUntilMillis?.let { it > nowMillis } ?: false

    /** Whether test mode is on now; what a send decides by. */
    val dryRun: Boolean get() = dryRunAt(System.currentTimeMillis())

    companion object {
        /** Test mode that stays on until switched off. */
        const val UNTIL_TURNED_OFF = Long.MAX_VALUE
    }

    val morning: LocalTime get() = LocalTime.of(morningMinutes / 60, morningMinutes % 60)
    val evening: LocalTime get() = LocalTime.of(eveningMinutes / 60, eveningMinutes % 60)
}

/** The grace window's default (B09): up to 20 minutes late a message still goes by itself. */
const val DEFAULT_GRACE_MINUTES = 20

/** The default before B09. Every setting was stored whenever any was changed, so a stored 120 was usually never chosen. */
private const val LEGACY_DEFAULT_GRACE_MINUTES = 120

/**
 * The grace window from what is stored: one set since the default became 20 minutes, else an older
 * setting unless it is the old default, which becomes the new one.
 */
internal fun graceMinutes(stored: Int?, legacy: Int?): Int =
    stored ?: legacy?.takeIf { it != LEGACY_DEFAULT_GRACE_MINUTES } ?: DEFAULT_GRACE_MINUTES

class SettingsStore(private val context: Context) {

    private object Keys {
        /** Before test mode had an end: on or off. Read once, as "until turned off", then removed. */
        val legacyDryRun = booleanPreferencesKey("dry_run")
        val dryRunUntil = longPreferencesKey("dry_run_until")
        val notifyAfterSend = booleanPreferencesKey("notify_after_send")
        val relock = booleanPreferencesKey("relock_after_send")
        val retryCount = intPreferencesKey("retry_count")
        val retryDelay = intPreferencesKey("retry_delay_seconds")
        val stepTimeout = longPreferencesKey("step_timeout_ms")
        val jobTimeout = longPreferencesKey("job_timeout_ms")
        /** From when the grace window's default was 2 hours; see [graceMinutes]. */
        val legacyGrace = intPreferencesKey("missed_grace_minutes")
        val grace = intPreferencesKey("missed_grace_minutes_v2")
        val headsUpSeconds = intPreferencesKey("heads_up_seconds")
        val deepLinkLocked = booleanPreferencesKey("deep_link_locked")
        val countryIso = stringPreferencesKey("default_country_iso")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val morning = intPreferencesKey("morning_minutes")
        val evening = intPreferencesKey("evening_minutes")
        val ownNumber = stringPreferencesKey("own_number_e164")

        val keepSentDays = intPreferencesKey("keep_sent_days")

        /** Set once schedules from before recipients existed have been linked to recipients. */
        val recipientsLinked = booleanPreferencesKey("recipients_linked")
    }

    val settings: Flow<Settings> = context.settingsDataStore.data.map(::read)

    suspend fun current(): Settings = settings.first()

    /**
     * Applies [transform] to the settings as stored at the moment of writing, inside DataStore's
     * transaction, so two quick changes can't read the same old values and undo each other (B20).
     */
    /** Whether schema-v1 schedules have been linked to recipients; see [markRecipientsLinked]. */
    suspend fun recipientsLinked(): Boolean = context.settingsDataStore.data.first()[Keys.recipientsLinked] ?: false

    /**
     * Linking happens once per install. After it, an unlinked schedule is one whose recipient was
     * removed from recents on purpose, and linking it again would bring the chip back.
     */
    suspend fun markRecipientsLinked() {
        context.settingsDataStore.edit { it[Keys.recipientsLinked] = true }
    }

    suspend fun update(transform: (Settings) -> Settings) {
        context.settingsDataStore.edit { p -> write(p, transform(read(p))) }
    }

    private fun read(p: Preferences) = Settings(
        dryRunUntilMillis = p[Keys.dryRunUntil] ?: Settings.UNTIL_TURNED_OFF.takeIf { p[Keys.legacyDryRun] == true },
        notifyAfterSend = p[Keys.notifyAfterSend] ?: true,
        relockAfterSend = p[Keys.relock] ?: true,
        retryCount = p[Keys.retryCount] ?: 2,
        retryDelaySeconds = p[Keys.retryDelay] ?: 30,
        stepTimeoutMs = p[Keys.stepTimeout] ?: 8_000,
        jobTimeoutMs = p[Keys.jobTimeout] ?: 90_000,
        missedGraceMinutes = graceMinutes(p[Keys.grace], p[Keys.legacyGrace]),
        headsUpSeconds = p[Keys.headsUpSeconds] ?: 10,
        deepLinkForLockedChats = p[Keys.deepLinkLocked] ?: false,
        defaultCountryIso = p[Keys.countryIso] ?: "GB",
        onboardingDone = p[Keys.onboardingDone] ?: false,
        morningMinutes = p[Keys.morning] ?: 9 * 60,
        eveningMinutes = p[Keys.evening] ?: 20 * 60,
        ownNumberE164 = p[Keys.ownNumber],
        keepSentDays = p[Keys.keepSentDays] ?: 30,
    )

    private fun write(p: MutablePreferences, s: Settings) {
        s.dryRunUntilMillis?.let { p[Keys.dryRunUntil] = it } ?: p.remove(Keys.dryRunUntil)
        p.remove(Keys.legacyDryRun)
        p[Keys.notifyAfterSend] = s.notifyAfterSend
        p[Keys.relock] = s.relockAfterSend
        p[Keys.retryCount] = s.retryCount
        p[Keys.retryDelay] = s.retryDelaySeconds
        p[Keys.stepTimeout] = s.stepTimeoutMs
        p[Keys.jobTimeout] = s.jobTimeoutMs
        p[Keys.grace] = s.missedGraceMinutes
        p[Keys.headsUpSeconds] = s.headsUpSeconds
        p.remove(Keys.legacyGrace)
        p[Keys.deepLinkLocked] = s.deepLinkForLockedChats
        p[Keys.countryIso] = s.defaultCountryIso
        p[Keys.onboardingDone] = s.onboardingDone
        p[Keys.morning] = s.morningMinutes
        p[Keys.evening] = s.eveningMinutes
        s.ownNumberE164?.let { p[Keys.ownNumber] = it } ?: p.remove(Keys.ownNumber)
        p[Keys.keepSentDays] = s.keepSentDays
    }
}
