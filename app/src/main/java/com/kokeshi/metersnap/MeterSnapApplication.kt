package com.kokeshi.metersnap

import android.app.Application
import com.kokeshi.metersnap.utils.ThemePreferences

class MeterSnapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePreferences.applySavedTheme(this)
    }
}