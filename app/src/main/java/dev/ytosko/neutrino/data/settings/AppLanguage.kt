package dev.ytosko.neutrino.data.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language: the phone's language, English or Bangla. On Android 13+ this is Android's
 * own per-app language (also shown in the system's app settings); on older phones Neutrino
 * remembers the choice and applies it to its screens itself.
 */
enum class AppLanguage(val tag: String) {
    System(""),
    English("en"),
    Bangla("bn"),
    ;

    companion object {
        private const val PREFS = "app_language"
        private const val KEY = "tag"

        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag.orEmpty() && it != System } ?: System

        fun current(context: Context): AppLanguage =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
                if (locales.isEmpty) System else fromTag(locales[0].language)
            } else {
                fromTag(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""))
            }

        /** Applies [language]. Before Android 13 the caller recreates the screen to show it. */
        fun set(context: Context, language: AppLanguage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    if (language == System) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
            }
        }

        /**
         * The context Neutrino draws with: before Android 13 it applies the language chosen here.
         * In Bangla it keeps regular digits (0-9) for numbers, so values like 6.4 mmol/L never mix
         * digit styles; Android would otherwise print some numbers in Bangla digits.
         */
        fun wrap(base: Context): Context {
            var locale = base.resources.configuration.locales[0]
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
                if (tag.isNotEmpty()) locale = Locale.forLanguageTag(tag)
            }
            if (locale.language == "bn" && locale.getUnicodeLocaleType("nu") == null) {
                locale = Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build()
            }
            if (locale == base.resources.configuration.locales[0]) return base
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
            return base.createConfigurationContext(config)
        }
    }
}
