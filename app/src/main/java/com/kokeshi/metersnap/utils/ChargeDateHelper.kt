package com.kokeshi.metersnap.utils

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Utility class for CWD charge date billing period calculations.
 *
 * CWD bills consumers on a specific day each month (1-28).
 * The billing period for a given month runs from:
 *   (previous month's charge date + 1) to (this month's charge date)
 *
 * Example with charge date = 28:
 *   - "January 2026" covers Dec 29, 2025 – Jan 28, 2026
 *   - "February 2026" covers Jan 29, 2026 – Feb 28, 2026
 *
 * Example with charge date = 21:
 *   - "January 2026" covers Dec 22, 2025 – Jan 21, 2026
 *   - "February 2026" covers Jan 22, 2026 – Feb 21, 2026
 */
object ChargeDateHelper {

    private const val PREFS_NAME = "MeterSnapPrefs"
    private const val KEY_CHARGE_DATE = "charge_date"
    private const val DEFAULT_CHARGE_DATE = 28

    /**
     * Get the user's configured charge date (1-28).
     */
    fun getChargeDate(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CHARGE_DATE, DEFAULT_CHARGE_DATE)
    }

    /**
     * Check if charge date has been set by the user.
     */
    fun isChargeDateSet(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("charge_date_set", false)
    }

    /**
     * Get the billing period key for a given timestamp.
     * Returns a "yyyy-MM" string representing which billing month the timestamp belongs to.
     *
     * If timestamp falls AFTER the charge date of a month, it belongs to the NEXT month's billing period.
     * If timestamp falls ON or BEFORE the charge date, it belongs to that month's billing period.
     */
    fun getBillingPeriodKey(timestamp: Long, chargeDate: Int): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)

        if (dayOfMonth > chargeDate) {
            // After charge date -> belongs to next month's billing period
            cal.add(Calendar.MONTH, 1)
        }

        val format = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        return format.format(cal.time)
    }

    /**
     * Get the display name for a billing period, including coverage dates.
     * e.g., "January 2026 (Dec 29 – Jan 28)"
     */
    fun getBillingPeriodDisplay(periodKey: String, chargeDate: Int): String {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val displayFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val shortFormat = SimpleDateFormat("MMM d", Locale.getDefault())

        return try {
            val date = monthFormat.parse(periodKey) ?: return periodKey
            val cal = Calendar.getInstance()
            cal.time = date

            val monthDisplay = displayFormat.format(cal.time)

            // End date: this month's charge date
            val endCal = Calendar.getInstance()
            endCal.time = date
            endCal.set(Calendar.DAY_OF_MONTH, chargeDate)

            // Start date: previous month's charge date - 1
            val startCal = Calendar.getInstance()
            startCal.time = date
            startCal.add(Calendar.MONTH, -1)
            startCal.set(Calendar.DAY_OF_MONTH, chargeDate)
            startCal.add(Calendar.DAY_OF_MONTH, -1)

            val startStr = shortFormat.format(startCal.time)
            val endStr = shortFormat.format(endCal.time)

            "$monthDisplay ($startStr \u2013 $endStr)"
        } catch (e: Exception) {
            periodKey
        }
    }

    /**
     * Get just the month display name (e.g., "January 2026") for a period key.
     */
    fun getMonthDisplayName(periodKey: String): String {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val displayFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        return try {
            val date = monthFormat.parse(periodKey) ?: return periodKey
            displayFormat.format(date)
        } catch (e: Exception) {
            periodKey
        }
    }

    /**
     * Get the billing period end timestamp (charge date at 11:59:59 PM) for a given period key.
     */
    fun getBillingPeriodEndTimestamp(periodKey: String, chargeDate: Int): Long {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        return try {
            val date = monthFormat.parse(periodKey) ?: return 0
            val cal = Calendar.getInstance()
            cal.time = date
            cal.set(Calendar.DAY_OF_MONTH, chargeDate)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.timeInMillis
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get the billing period start timestamp (previous charge date - 1 at 00:00:00).
     */
    fun getBillingPeriodStartTimestamp(periodKey: String, chargeDate: Int): Long {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        return try {
            val date = monthFormat.parse(periodKey) ?: return 0
            val cal = Calendar.getInstance()
            cal.time = date
            cal.add(Calendar.MONTH, -1)
            cal.set(Calendar.DAY_OF_MONTH, chargeDate)
            cal.add(Calendar.DAY_OF_MONTH, -1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.timeInMillis
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get the current billing period key (based on today's date and charge date).
     */
    fun getCurrentBillingPeriodKey(chargeDate: Int): String {
        return getBillingPeriodKey(System.currentTimeMillis(), chargeDate)
    }

    /**
     * Get the number of days until the next charge date.
     */
    fun getDaysUntilChargeDate(chargeDate: Int): Int {
        val today = Calendar.getInstance()
        val currentDay = today.get(Calendar.DAY_OF_MONTH)

        return if (currentDay <= chargeDate) {
            chargeDate - currentDay
        } else {
            // Next charge date is in the next calendar month
            val nextChargeDate = Calendar.getInstance()
            nextChargeDate.add(Calendar.MONTH, 1)
            nextChargeDate.set(Calendar.DAY_OF_MONTH, chargeDate)

            val diffMs = nextChargeDate.timeInMillis - today.timeInMillis
            (diffMs / (24 * 60 * 60 * 1000)).toInt()
        }
    }
}
