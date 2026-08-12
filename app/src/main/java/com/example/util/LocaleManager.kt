package com.example.util

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

object LocaleManager {

    private const val PREFS_NAME = "zipmaster_prefs"
    private const val KEY_LANG = "selected_language"

    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANG, "fa") ?: "fa"
    }

    fun saveLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANG, langCode).apply()
    }

    fun applyLocale(context: Context, langCode: String): Context {
        val locale = Locale(langCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun getLayoutDirection(langCode: String): LayoutDirection {
        return if (langCode == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr
    }
}

@Composable
fun ZipMasterLocaleProvider(
    langCode: String,
    content: @Composable () -> Unit
) {
    val layoutDirection = LocaleManager.getLayoutDirection(langCode)
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        content()
    }
}
