package dev.ytosko.neutrino.data.settings

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
) {
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
