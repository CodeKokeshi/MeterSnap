package com.kokeshi.metersnap.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.kokeshi.metersnap.utils.ChargeDateHelper
import java.util.*

/**
 * Schedules and manages end-of-billing-period scan reminder notifications.
 * 
 * Uses the user's configured CWD charge date instead of calendar month end.
 * 
 * ## Reminder Schedule:
 * - 3 days before charge date: "Billing period ending soon" (gentle reminder)
 * - 1 day before charge date: "Tomorrow is charge date" (medium priority)
 * - Charge date (morning 9 AM): "Charge date! Scan now" (high priority)
 * - Charge date (evening 6 PM): Final reminder if not scanned
 * 
 * ## User Preferences:
 * - Enable/disable reminders
 * - Customize reminder times
 * - Dismiss until next period
 */
object EndOfMonthReminderScheduler {
    
    private const val TAG = "EOMReminderScheduler"
    private const val PREFS_NAME = "eom_reminder_prefs"
    private const val KEY_REMINDERS_ENABLED = "reminders_enabled"
    private const val KEY_LAST_REMINDER_MONTH = "last_reminder_month"
    private const val KEY_SCANNED_THIS_MONTH = "scanned_this_month"
    private const val KEY_REMINDER_HOUR = "reminder_hour"
    private const val KEY_REMINDER_MINUTE = "reminder_minute"
    
    // Request codes for different reminders
    private const val REQUEST_CODE_3_DAYS = 3001
    private const val REQUEST_CODE_1_DAY = 3002
    private const val REQUEST_CODE_LAST_DAY_MORNING = 3003
    private const val REQUEST_CODE_LAST_DAY_EVENING = 3004
    
    /**
     * Schedule all reminders based on the user's charge date.
     * Uses charge date as the "end of billing period" instead of calendar month end.
     */
    fun scheduleReminders(context: Context) {
        val prefs = getPrefs(context)
        
        if (!areRemindersEnabled(prefs)) {
            Log.d(TAG, "Reminders are disabled, skipping scheduling")
            return
        }
        
        // Cancel any existing alarms first
        cancelAllReminders(context)
        
        val chargeDate = ChargeDateHelper.getChargeDate(context)
        
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)
        val currentDay = calendar.get(Calendar.DAY_OF_MONTH)
        
        // The "end of billing period" day is the charge date
        val billingEndDay = chargeDate
        
        Log.d(TAG, "Scheduling reminders for $currentYear-${currentMonth + 1}, today is day $currentDay, charge date is $billingEndDay")
        
        // Get preferred reminder time (default 9 AM)
        val reminderHour = prefs.getInt(KEY_REMINDER_HOUR, 9)
        val reminderMinute = prefs.getInt(KEY_REMINDER_MINUTE, 0)
        
        // Determine the target charge date
        // If we've already passed this month's charge date, schedule for next month
        var targetMonth = currentMonth
        var targetYear = currentYear
        if (currentDay > billingEndDay) {
            targetMonth += 1
            if (targetMonth > 11) {
                targetMonth = 0
                targetYear += 1
            }
        }
        
        // Schedule 3-day reminder.
        // For early charge dates (1-3) billingEndDay - 3 ≤ 0, so clamp to day 1 of the month.
        // Skip entirely only if it would land on or after the charge date itself (no room).
        val threeDaysBefore = (billingEndDay - 3).coerceAtLeast(1)
        if (threeDaysBefore < billingEndDay) {
            scheduleReminderForDate(
                context = context,
                year = targetYear,
                month = targetMonth,
                day = threeDaysBefore,
                hour = reminderHour,
                minute = reminderMinute,
                requestCode = REQUEST_CODE_3_DAYS,
                action = EndOfMonthReminderReceiver.ACTION_REMIND_SCAN
            )
        }

        // Schedule 1-day reminder.
        // For charge date 1, billingEndDay - 1 = 0 — clamp to day 1 (same day as charge date).
        // Skip if it would collide with the charge date morning reminder (handled below).
        val oneDayBefore = (billingEndDay - 1).coerceAtLeast(1)
        if (oneDayBefore < billingEndDay) {
            scheduleReminderForDate(
                context = context,
                year = targetYear,
                month = targetMonth,
                day = oneDayBefore,
                hour = reminderHour,
                minute = reminderMinute,
                requestCode = REQUEST_CODE_1_DAY,
                action = EndOfMonthReminderReceiver.ACTION_REMIND_SCAN
            )
        }
        
        // Schedule charge date morning reminder (9 AM)
        scheduleReminderForDate(
            context = context,
            year = targetYear,
            month = targetMonth,
            day = billingEndDay,
            hour = 9,
            minute = 0,
            requestCode = REQUEST_CODE_LAST_DAY_MORNING,
            action = EndOfMonthReminderReceiver.ACTION_LAST_DAY_REMINDER
        )
        
        // Schedule charge date evening reminder (6 PM)
        scheduleReminderForDate(
            context = context,
            year = targetYear,
            month = targetMonth,
            day = billingEndDay,
            hour = 18,
            minute = 0,
            requestCode = REQUEST_CODE_LAST_DAY_EVENING,
            action = EndOfMonthReminderReceiver.ACTION_LAST_DAY_REMINDER
        )
        
        // Update last reminder month
        prefs.edit().putString(KEY_LAST_REMINDER_MONTH, "$targetYear-${targetMonth + 1}").apply()
    }
    
    /**
     * Schedule a single reminder at the specified date and time.
     */
    private fun scheduleReminderForDate(
        context: Context,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        requestCode: Int,
        action: String
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        // Don't schedule if time has already passed
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            Log.d(TAG, "Skipping reminder for $year-${month+1}-$day - time has passed")
            return
        }
        
        val intent = Intent(context, EndOfMonthReminderReceiver::class.java).apply {
            this.action = action
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "Scheduled reminder: $year-${month+1}-$day, hour=$hour, action=$action")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to schedule exact alarm", e)
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        }
    }
    
    /**
     * Cancel all scheduled reminders.
     */
    fun cancelAllReminders(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val requestCodes = listOf(
            REQUEST_CODE_3_DAYS,
            REQUEST_CODE_1_DAY,
            REQUEST_CODE_LAST_DAY_MORNING,
            REQUEST_CODE_LAST_DAY_EVENING
        )
        
        for (requestCode in requestCodes) {
            val intent = Intent(context, EndOfMonthReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.cancel(pendingIntent)
        }
        
        Log.d(TAG, "All reminders cancelled")
    }
    
    /**
     * Enable or disable reminders.
     */
    fun setRemindersEnabled(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(KEY_REMINDERS_ENABLED, enabled).apply()
        
        if (enabled) {
            scheduleReminders(context)
        } else {
            cancelAllReminders(context)
        }
    }
    
    /**
     * Check if reminders are enabled.
     */
    fun areRemindersEnabled(context: Context): Boolean {
        return areRemindersEnabled(getPrefs(context))
    }
    
    private fun areRemindersEnabled(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(KEY_REMINDERS_ENABLED, true) // Default enabled
    }
    
    /**
     * Mark that the user has scanned this month (suppress further reminders).
     */
    fun markScannedThisMonth(context: Context) {
        val prefs = getPrefs(context)
        val calendar = Calendar.getInstance()
        val monthKey = "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH) + 1}"
        prefs.edit().putString(KEY_SCANNED_THIS_MONTH, monthKey).apply()
    }
    
    /**
     * Check if user has already scanned this month.
     */
    fun hasScannedThisMonth(context: Context): Boolean {
        val prefs = getPrefs(context)
        val calendar = Calendar.getInstance()
        val currentMonthKey = "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH) + 1}"
        val scannedMonthKey = prefs.getString(KEY_SCANNED_THIS_MONTH, "")
        return scannedMonthKey == currentMonthKey
    }
    
    /**
     * Set preferred reminder time.
     */
    fun setReminderTime(context: Context, hour: Int, minute: Int) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putInt(KEY_REMINDER_HOUR, hour)
            .putInt(KEY_REMINDER_MINUTE, minute)
            .apply()
        
        // Reschedule with new time
        scheduleReminders(context)
    }
    
    /**
     * Get the preferred reminder time.
     */
    fun getReminderTime(context: Context): Pair<Int, Int> {
        val prefs = getPrefs(context)
        val hour = prefs.getInt(KEY_REMINDER_HOUR, 9)
        val minute = prefs.getInt(KEY_REMINDER_MINUTE, 0)
        return Pair(hour, minute)
    }
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
