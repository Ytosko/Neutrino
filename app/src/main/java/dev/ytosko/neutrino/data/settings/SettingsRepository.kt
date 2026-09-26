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
import dev.ytosko.neutrino.domain.GlucoseUnit

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
    /** Blood glucose target range in mmol/L (used for colours and "time in range"). */
    val glucoseLow: Double = 4.0,
    val glucoseHigh: Double = 10.0,
    /** How glucose is shown; readings are always stored in mmol/L. */
    val glucoseUnit: GlucoseUnit = GlucoseUnit.defaultFor(),
    /** Optional daily goals; null means no goal. */
    val carbGoalG: Int? = null,
    val proteinGoalG: Int? = null,
    val fatGoalG: Int? = null,
    val kcalGoal: Int? = null,
    val waterGoalMl: Int? = null,
    /** Remind to test glucose some time after a meal (only useful with a meter). */
    val afterMealReminder: Boolean = false,
    val afterMealMinutes: Int = 120,
    /** Ask for fingerprint / screen lock when opening Neutrino. */
    val appLock: Boolean = false,
    /** Blank Neutrino's preview in the recent-apps switcher and block screenshots. */
    val hideInRecents: Boolean = false,
    /** A Sunday evening summary notification of the week. */
    val weeklySummary: Boolean = false,
    /** Show the latest glucose reading on the home screen widget. */
    val widgetShowsGlucose: Boolean = true,
    /** The "press and hold a meal" tip was seen (dismissed or used). */
    val mealTipDone: Boolean = false,
    /** Turns on the medicine log (tablets, syrups…). Off: medicines are hidden everywhere. */
    val takesMedicine: Boolean = false,
    /** Turns on the insulin log. */
    val usesInsulin: Boolean = false,
    /** Import blood glucose other apps (e.g. CGM apps) save to Health Connect. Needs its read permission. */
    val glucoseImport: Boolean = false,
    /** Readings up to this time were already imported. */
    val glucoseImportedUntil: Long = 0,
) {
    val medicinesOn: Boolean get() = takesMedicine || usesInsulin

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
        val glucoseLow = androidx.datastore.preferences.core.doublePreferencesKey("glucose_low")
        val glucoseHigh = androidx.datastore.preferences.core.doublePreferencesKey("glucose_high")
        val glucoseUnit = stringPreferencesKey("glucose_unit")
        val carbGoal = intPreferencesKey("goal_carbs_g")
        val proteinGoal = intPreferencesKey("goal_protein_g")
        val fatGoal = intPreferencesKey("goal_fat_g")
        val kcalGoal = intPreferencesKey("goal_kcal")
        val waterGoal = intPreferencesKey("goal_water_ml")
        val afterMealReminder = booleanPreferencesKey("after_meal_reminder")
        val afterMealMinutes = intPreferencesKey("after_meal_minutes")
        val appLock = booleanPreferencesKey("app_lock")
        val hideInRecents = booleanPreferencesKey("hide_in_recents")
        val weeklySummary = booleanPreferencesKey("weekly_summary")
        val takesMedicine = booleanPreferencesKey("takes_medicine")
        val usesInsulin = booleanPreferencesKey("uses_insulin")
        val glucoseImport = booleanPreferencesKey("glucose_import")
        val glucoseImportedUntil = androidx.datastore.preferences.core.longPreferencesKey("glucose_imported_until")
        val widgetGlucose = booleanPreferencesKey("widget_glucose")
        val mealTipDone = booleanPreferencesKey("meal_tip_done")
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
            glucoseLow = p[Keys.glucoseLow] ?: 4.0,
            glucoseHigh = p[Keys.glucoseHigh] ?: 10.0,
            glucoseUnit = GlucoseUnit.fromId(p[Keys.glucoseUnit]) ?: GlucoseUnit.defaultFor(),
            carbGoalG = p[Keys.carbGoal],
            proteinGoalG = p[Keys.proteinGoal],
            fatGoalG = p[Keys.fatGoal],
            kcalGoal = p[Keys.kcalGoal],
            waterGoalMl = p[Keys.waterGoal],
            afterMealReminder = p[Keys.afterMealReminder] ?: false,
            afterMealMinutes = p[Keys.afterMealMinutes] ?: 120,
            appLock = p[Keys.appLock] ?: false,
            hideInRecents = p[Keys.hideInRecents] ?: false,
            weeklySummary = p[Keys.weeklySummary] ?: false,
            takesMedicine = p[Keys.takesMedicine] ?: false,
            usesInsulin = p[Keys.usesInsulin] ?: false,
            glucoseImport = p[Keys.glucoseImport] ?: false,
            glucoseImportedUntil = p[Keys.glucoseImportedUntil] ?: 0,
            widgetShowsGlucose = p[Keys.widgetGlucose] ?: true,
            mealTipDone = p[Keys.mealTipDone] ?: false,
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

    /** Saves the target range; ignored unless 2.0 <= low < high <= 20.0 mmol/L. */
    suspend fun setGlucoseRange(low: Double, high: Double): Boolean {
        if (low < 2.0 || high > 20.0 || low >= high) return false
        store.edit {
            it[Keys.glucoseLow] = low
            it[Keys.glucoseHigh] = high
        }
        return true
    }

    /** Stores [pick]'s unit if none was chosen yet. */
    suspend fun ensureGlucoseUnit(pick: suspend () -> GlucoseUnit) {
        if (preferences.first()[Keys.glucoseUnit] != null) return
        val unit = pick()
        store.edit { if (it[Keys.glucoseUnit] == null) it[Keys.glucoseUnit] = unit.name }
    }

    suspend fun setGlucoseUnit(unit: GlucoseUnit) {
        store.edit { it[Keys.glucoseUnit] = unit.name }
    }

    /** Daily goals; null clears a goal. Out-of-range values are ignored. */
    suspend fun setGoals(carbsG: Int?, proteinG: Int?, fatG: Int?, kcal: Int?, waterMl: Int?) {
        store.edit { p ->
            fun put(key: androidx.datastore.preferences.core.Preferences.Key<Int>, value: Int?, range: IntRange) {
                if (value == null) p.remove(key) else if (value in range) p[key] = value
            }
            put(Keys.carbGoal, carbsG, 10..1_000)
            put(Keys.proteinGoal, proteinG, 10..500)
            put(Keys.fatGoal, fatG, 10..500)
            put(Keys.kcalGoal, kcal, 500..10_000)
            put(Keys.waterGoal, waterMl, 250..10_000)
        }
    }

    suspend fun setAfterMealReminder(enabled: Boolean, minutes: Int = 120) {
        store.edit {
            it[Keys.afterMealReminder] = enabled
            it[Keys.afterMealMinutes] = minutes.coerceIn(30, 240)
        }
    }

    suspend fun setAppLock(enabled: Boolean) {
        store.edit { it[Keys.appLock] = enabled }
    }

    suspend fun setHideInRecents(enabled: Boolean) {
        store.edit { it[Keys.hideInRecents] = enabled }
    }

    suspend fun setWeeklySummary(enabled: Boolean) {
        store.edit { it[Keys.weeklySummary] = enabled }
    }

    suspend fun setTakesMedicine(enabled: Boolean) {
        store.edit { it[Keys.takesMedicine] = enabled }
    }

    suspend fun setUsesInsulin(enabled: Boolean) {
        store.edit { it[Keys.usesInsulin] = enabled }
    }

    suspend fun setGlucoseImport(enabled: Boolean) {
        store.edit { it[Keys.glucoseImport] = enabled }
    }

    suspend fun setGlucoseImportedUntil(epochMs: Long) {
        store.edit { it[Keys.glucoseImportedUntil] = epochMs }
    }

    suspend fun setMealTipDone() {
        store.edit { it[Keys.mealTipDone] = true }
    }

    suspend fun setWidgetShowsGlucose(enabled: Boolean) {
        store.edit { it[Keys.widgetGlucose] = enabled }
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
