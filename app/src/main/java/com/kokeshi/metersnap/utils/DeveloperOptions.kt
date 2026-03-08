package com.kokeshi.metersnap.utils

import android.content.Context

object DeveloperOptions {
    const val PREFS_NAME = "developer_settings"
    const val KEY_FORCE_DUAL_BILLING = "force_dual_billing_enabled"

    const val EXTRA_DEV_FORCED_COMPARISON = "DEV_FORCED_COMPARISON"
    const val EXTRA_FORCED_APP_PREVIOUS_READING = "FORCED_APP_PREVIOUS_READING"

    fun isForceDualBillingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_DUAL_BILLING, false)
    }

    fun setForceDualBillingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_DUAL_BILLING, enabled)
            .apply()
    }

    fun buildForcedAppPreviousReading(currentReading: Int, cwdPreviousReading: Int): Int? {
        val usage = currentReading - cwdPreviousReading
        if (usage <= 0) return null

        val loweredDelta = minOf(5, maxOf(1, usage / 3))
        val loweredPreviousReading = cwdPreviousReading - loweredDelta
        if (loweredPreviousReading >= 0 && loweredPreviousReading < currentReading) {
            return loweredPreviousReading
        }

        val upwardRoom = currentReading - cwdPreviousReading - 1
        if (upwardRoom <= 0) return null

        val raisedPreviousReading = cwdPreviousReading + minOf(5, upwardRoom)
        return raisedPreviousReading.takeIf { it < currentReading && it != cwdPreviousReading }
    }
}