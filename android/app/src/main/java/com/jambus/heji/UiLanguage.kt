package com.jambus.heji

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * App-only presentation preference.  It intentionally never touches the user-owned Vault.
 * "system" is the default; unsupported system locales fall back to English.
 */
object UiLanguage {
    private const val PREFERENCES = "heji_notes_ui"
    private const val KEY = "language"
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val CHINESE = "zh"

    fun selected(context: Context): String = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY, SYSTEM).takeIf { it in setOf(SYSTEM, ENGLISH, CHINESE) } ?: SYSTEM

    fun set(context: Context, language: String) {
        require(language in setOf(SYSTEM, ENGLISH, CHINESE))
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
    }

    fun locale(context: Context): Locale = when (selected(context)) {
        CHINESE -> Locale.SIMPLIFIED_CHINESE
        ENGLISH -> Locale.ENGLISH
        else -> context.resources.configuration.locales[0].takeIf { it.language == CHINESE } ?: Locale.ENGLISH
    }

    fun localizedContext(context: Context): Context {
        val locale = locale(context)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
            setLocale(locale)
        }
        return context.createConfigurationContext(configuration)
    }
}

