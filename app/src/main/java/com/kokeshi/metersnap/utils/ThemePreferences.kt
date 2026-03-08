package com.kokeshi.metersnap.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemePreferences {
    private const val PREFS_NAME = "app_theme_prefs"
    private const val KEY_APP_THEME = "app_theme"
    private const val THEME_DARK = "dark"
    private const val THEME_LIGHT = "light"

    fun applySavedTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(getNightMode(context))
    }

    fun isLightModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_THEME, THEME_DARK) == THEME_LIGHT
    }

    fun setLightModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_THEME, if (enabled) THEME_LIGHT else THEME_DARK)
            .apply()

        AppCompatDelegate.setDefaultNightMode(getNightMode(context))
    }

    private fun getNightMode(context: Context): Int {
        return if (isLightModeEnabled(context)) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
    }
}