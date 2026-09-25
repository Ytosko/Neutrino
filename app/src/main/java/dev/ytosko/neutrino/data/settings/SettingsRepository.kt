package dev.ytosko.neutrino.data.settings

import java.time.LocalTime
import dev.ytosko.neutrino.domain.MealWindows
import dev.ytosko.neutrino.data.ai.PromptHints
import androidx.datastore.preferences.core.intPreferencesKey
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.PhotoDetail
import dev.ytosko.neutrino.data.backup.SettingsSnapshot
import dev.ytosko.neutrino.data.security.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val activeProvider: AiProvider? = null,
    /** Chosen model per provider. */
    val models: Map<AiProvider, String> = emptyMap(),
    /** Providers that have an (encrypted) API key stored. */
    val providersWithKey: Set<AiProvider> = emptySet(),
    val photoDetail: PhotoDetail = PhotoDetail.Standard,
    /** Breakfast, lunch and dinner reminders (10 AM, 2 PM, 6 PM). */
    val remindersEnabled: Boolean = true,
    /** Neutrino already asked for notification permission once (Android 13+). */
    val notificationsAsked: Boolean = false,
    /** e.g. "Bangladeshi"; a hint for the AI. Null means no preference. */
    val cuisine: String? = null,
    /** Short free-text notes added to AI prompts, e.g. "I use little oil". */
    val aiNotes: String = "",
    /** When each meal starts, for picking the meal type automatically. */
    val mealWindows: MealWindows = MealWindows(),
    val breakfastReminder: LocalTime = LocalTime.of(10, 0),
    val lunchReminder: LocalTime = LocalTime.of(14, 0),
    val dinnerReminder: LocalTime = LocalTime.of(18, 0),
) {
    val promptHints: PromptHints get() = PromptHints(cuisine, aiNotes.takeIf { it.isNotBlank() })
    val aiReady: Boolean
        get() = activeProvider != null && activeProvider in providersWithKey && models[activeProvider] != null
}

/**
 * App settings in DataStore. API keys are stored only as Keystore-encrypted ciphertext and
 * are never exposed through [settings]; read one explicitly with [apiKey] when making a call.
 */
class SettingsRepository(context: Context, private val cipher: SecretCipher) {

    private val store = context.applicationContext.settingsStore

    private object Keys {
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val activeProvider = stringPreferencesKey("ai_provider")
        val photoDetail = stringPreferencesKey("photo_detail")
        val reminders = booleanPreferencesKey("meal_reminders")
        val notificationsAsked = booleanPreferencesKey("notifications_asked")
        val cuisine = stringPreferencesKey("cuisine")
        val aiNotes = stringPreferencesKey("ai_notes")
        val breakfastStart = intPreferencesKey("meal_breakfast_min")
        val lunchStart = intPreferencesKey("meal_lunch_min")
        val snackStart = intPreferencesKey("meal_snack_min")
        val dinnerStart = intPreferencesKey("meal_dinner_min")
        val breakfastReminder = intPreferencesKey("reminder_breakfast_min")
        val lunchReminder = intPreferencesKey("reminder_lunch_min")
        val dinnerReminder = intPreferencesKey("reminder_dinner_min")
        fun model(provider: AiProvider) = stringPreferencesKey("ai_model_${provider.id}")
        fun apiKey(provider: AiProvider) = stringPreferencesKey("ai_key_${provider.id}")
    }

    private val preferences: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<AppSettings> = preferences.map { p ->
        AppSettings(
            onboardingComplete = p[Keys.onboardingComplete] ?: false,
            activeProvider = AiProvider.fromId(p[Keys.activeProvider]),
            models = AiProvider.entries.mapNotNull { provider -> p[Keys.model(provider)]?.let { provider to it } }.toMap(),
            providersWithKey = AiProvider.entries.filter { p[Keys.apiKey(it)] != null }.toSet(),
            photoDetail = PhotoDetail.fromId(p[Keys.photoDetail]),
            remindersEnabled = p[Keys.reminders] ?: true,
            notificationsAsked = p[Keys.notificationsAsked] ?: false,
            cuisine = p[Keys.cuisine],
            aiNotes = p[Keys.aiNotes].orEmpty(),
            mealWindows = runCatching {
                val defaults = MealWindows()
                MealWindows(
                    breakfast = p[Keys.breakfastStart]?.toTime() ?: defaults.breakfast,
                    lunch = p[Keys.lunchStart]?.toTime() ?: defaults.lunch,
                    snack = p[Keys.snackStart]?.toTime() ?: defaults.snack,
                    dinner = p[Keys.dinnerStart]?.toTime() ?: defaults.dinner,
                )
            }.getOrDefault(MealWindows()),
            breakfastReminder = p[Keys.breakfastReminder]?.toTime() ?: LocalTime.of(10, 0),
            lunchReminder = p[Keys.lunchReminder]?.toTime() ?: LocalTime.of(14, 0),
            dinnerReminder = p[Keys.dinnerReminder]?.toTime() ?: LocalTime.of(18, 0),
        )
    }

    suspend fun apiKey(provider: AiProvider): String? {
        val encrypted = preferences.first()[Keys.apiKey(provider)] ?: return null
        return withContext(Dispatchers.IO) { cipher.decrypt(encrypted) }
    }

    /** Makes [provider] active with [model]. A null [apiKey] keeps the key already stored. */
    suspend fun saveAiProvider(provider: AiProvider, apiKey: String?, model: String) {
        val encrypted = apiKey?.let { withContext(Dispatchers.IO) { cipher.encrypt(it) } }
        store.edit {
            it[Keys.activeProvider] = provider.id
            it[Keys.model(provider)] = model
            if (encrypted != null) it[Keys.apiKey(provider)] = encrypted
        }
    }

    suspend fun setPhotoDetail(detail: PhotoDetail) {
        store.edit { it[Keys.photoDetail] = detail.id }
    }

    /** Everything a backup needs, including decrypted API keys (the backup itself is encrypted). */
    suspend fun snapshot(): SettingsSnapshot {
        val p = preferences.first()
        return SettingsSnapshot(
            activeProvider = p[Keys.activeProvider],
            models = AiProvider.entries.mapNotNull { provider -> p[Keys.model(provider)]?.let { provider.id to it } }.toMap(),
            apiKeys = AiProvider.entries.mapNotNull { provider -> apiKey(provider)?.let { provider.id to it } }.toMap(),
            photoDetail = p[Keys.photoDetail],
        )
    }

    /** Replaces AI settings with a backup's, re-encrypting keys for this install, and finishes onboarding. */
    suspend fun restore(snapshot: SettingsSnapshot) {
        val encrypted = withContext(Dispatchers.IO) {
            snapshot.apiKeys.mapNotNull { (id, key) -> AiProvider.fromId(id)?.let { it to cipher.encrypt(key) } }.toMap()
        }
        store.edit { p ->
            AiProvider.entries.forEach { provider ->
                p.remove(Keys.model(provider))
                p.remove(Keys.apiKey(provider))
            }
            snapshot.models.forEach { (id, model) -> AiProvider.fromId(id)?.let { p[Keys.model(it)] = model } }
            encrypted.forEach { (provider, key) -> p[Keys.apiKey(provider)] = key }
            val active = AiProvider.fromId(snapshot.activeProvider)
            if (active != null) p[Keys.activeProvider] = active.id else p.remove(Keys.activeProvider)
            snapshot.photoDetail?.let { p[Keys.photoDetail] = it }
            p[Keys.onboardingComplete] = true
        }
    }

    suspend fun setCuisine(cuisine: String?) {
        store.edit { if (cuisine.isNullOrBlank()) it.remove(Keys.cuisine) else it[Keys.cuisine] = cuisine }
    }

    suspend fun setAiNotes(notes: String) {
        store.edit { it[Keys.aiNotes] = notes.take(PromptHints.MAX_NOTES) }
    }

    /** Saves meal start times; returns false (and saves nothing) if they're out of order. */
    suspend fun setMealWindows(windows: MealWindows): Boolean {
        val valid = runCatching { windows.copy() }.isSuccess
        if (!valid) return false
        store.edit {
            it[Keys.breakfastStart] = windows.breakfast.toMinutes()
            it[Keys.lunchStart] = windows.lunch.toMinutes()
            it[Keys.snackStart] = windows.snack.toMinutes()
            it[Keys.dinnerStart] = windows.dinner.toMinutes()
        }
        return true
    }

    suspend fun setReminderTimes(breakfast: LocalTime, lunch: LocalTime, dinner: LocalTime) {
        store.edit {
            it[Keys.breakfastReminder] = breakfast.toMinutes()
            it[Keys.lunchReminder] = lunch.toMinutes()
            it[Keys.dinnerReminder] = dinner.toMinutes()
        }
    }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        store.edit { it[Keys.reminders] = enabled }
    }

    suspend fun setNotificationsAsked() {
        store.edit { it[Keys.notificationsAsked] = true }
    }

    suspend fun setOnboardingComplete() {
        store.edit { it[Keys.onboardingComplete] = true }
    }
}

private fun Int.toTime(): LocalTime = LocalTime.of((this / 60).coerceIn(0, 23), (this % 60).coerceIn(0, 59))

private fun LocalTime.toMinutes(): Int = hour * 60 + minute
