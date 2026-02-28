package com.kokeshi.metersnap.utils

import android.content.Context

/**
 * Resets operational user session data when user switches to a new meter baseline.
 */
object SessionResetManager {

    private const val METER_PREFS = "MeterSnapPrefs"
    private const val KEY_PREVIOUS_READING = "previous_reading"
    private const val KEY_PREVIOUS_READING_SET = "previous_reading_set"
    private const val KEY_CHARGE_DATE = "charge_date"
    private const val KEY_CHARGE_DATE_SET = "charge_date_set"
    private const val KEY_TUTORIAL_COMPLETED = "tutorial_completed"
    private const val KEY_PREVIOUS_READING_PERIOD_KEY = "previous_reading_period_key"

    fun resetForNewMeterSession(context: Context, newPreviousReading: Int) {
        val meterPrefs = context.getSharedPreferences(METER_PREFS, Context.MODE_PRIVATE)
        SessionLogManager(context).archiveCurrentSession("new_meter_reset")

        val preserveChargeDate = meterPrefs.getBoolean(KEY_CHARGE_DATE_SET, false)
        val preservedChargeDateValue = meterPrefs.getInt(KEY_CHARGE_DATE, 28)
        val preserveTutorialState = meterPrefs.getBoolean(KEY_TUTORIAL_COMPLETED, true)

        meterPrefs.edit().clear().apply()

        meterPrefs.edit()
            .putInt(KEY_PREVIOUS_READING, newPreviousReading)
            .putBoolean(KEY_PREVIOUS_READING_SET, true)
            .putBoolean(KEY_TUTORIAL_COMPLETED, preserveTutorialState)
            .apply()

        val chargeDate = if (preserveChargeDate) preservedChargeDateValue else 28
        val currentPeriodKey = ChargeDateHelper.getCurrentBillingPeriodKey(chargeDate)
        meterPrefs.edit().putString(KEY_PREVIOUS_READING_PERIOD_KEY, currentPeriodKey).apply()

        if (preserveChargeDate) {
            meterPrefs.edit()
                .putInt(KEY_CHARGE_DATE, preservedChargeDateValue)
                .putBoolean(KEY_CHARGE_DATE_SET, true)
                .apply()
        }

        HistoryManager(context).clearAllHistory()
        ProjectedReadingManager(context).clearAllProjections()
    }
}
