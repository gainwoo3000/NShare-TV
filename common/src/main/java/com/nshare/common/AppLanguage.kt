package com.nshare.common

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language choice. SYSTEM follows the device language (Korean devices get Korean,
 * everything else falls back to English, the default resources).
 *
 * Android 13+: uses the platform per-app language API, so the choice also shows up in
 * system Settings > Apps > Language. Older versions: the choice is stored and applied by
 * wrapping each Activity/Service base context with [wrap].
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    KOREAN("ko"),
    ENGLISH("en");

    fun next(): AppLanguage = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val PREFS = "app_language"
        private const val KEY = "tag"

        fun current(context: Context): AppLanguage {
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            }
            return entries.firstOrNull { it.tag != null && it.tag == tag } ?: SYSTEM
        }

        fun set(activity: Activity, language: AppLanguage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The platform recreates visible activities with the new configuration.
                activity.getSystemService(LocaleManager::class.java).applicationLocales =
                    language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
            } else {
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
                activity.recreate()
            }
        }

        /** Applies a stored choice on Android < 13. Call from attachBaseContext. */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
            val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
            val config = Configuration(base.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            return base.createConfigurationContext(config)
        }
    }
}
