package org.sonora.android

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * In-app language selection.
 *
 * The chosen language is persisted in the shared `sonora_settings` preferences and applied by
 * wrapping the base context of the Application and Activity, so it works on every API level
 * (no AppCompat dependency) and is authoritative over the system locale.
 *
 * Supported values: [SYSTEM] (follow the device), [ITALIAN], [ENGLISH], [ROMANIAN].
 */
object AppLanguage {

    const val SYSTEM = "system"
    const val ITALIAN = "it"
    const val ENGLISH = "en"
    const val ROMANIAN = "ro"

    val SUPPORTED = listOf(SYSTEM, ITALIAN, ENGLISH, ROMANIAN)

    private const val PREFS = "sonora_settings"
    private const val KEY = "app_language"

    /** Persisted language tag, defaulting to [SYSTEM]. */
    fun preferredTag(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM)
        return saved?.takeIf { it in SUPPORTED } ?: SYSTEM
    }

    fun save(context: Context, tag: String) {
        val value = if (tag in SUPPORTED) tag else SYSTEM
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, value)
            .apply()
    }

    /**
     * Returns a context whose resources use the selected locale, or [base] unchanged when the
     * preference is [SYSTEM].
     */
    fun wrap(base: Context): Context {
        val tag = preferredTag(base)
        if (tag == SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
