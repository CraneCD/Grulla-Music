package com.grulla.music.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/** The app's own language (Android 13+ keeps it per app; older versions follow the phone). */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    SPANISH("es"),
    ;

    companion object {
        /** The chosen language, or null where Android has no per-app language. */
        fun current(context: Context): AppLanguage? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return SYSTEM
            if (locales.isEmpty) return SYSTEM
            return entries.firstOrNull { it.tag.isNotEmpty() && it.tag == locales[0].language } ?: SYSTEM
        }

        /** Android recreates the activity in the new language. */
        fun set(context: Context, language: AppLanguage) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (language == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
        }
    }
}
