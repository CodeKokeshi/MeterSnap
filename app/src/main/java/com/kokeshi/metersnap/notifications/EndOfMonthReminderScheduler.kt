package com.kokeshi.metersnap.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.util.*

/**
 * Schedules and manages end-of-month scan reminder notifications.
 * 
 * ## Reminder Schedule:
 * - 3 days before month end: "Month end approaching" (gentle reminder)
 * - 1 day before month end: "Tomorrow is the last day" (medium priority)
 * - Last day of month (morning 9 AM): "Last day! Scan now" (high priority)
 * - Last day of month (evening 6 PM): Final reminder if not scanned
 * 
 * ## User Preferences:
 * - Enable/disable reminders
 * - Customize reminder times
 * - Dismiss until next month
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
     * Schedule all end-of-month reminders for the current month.
     */
    fun scheduleReminders(context: Context) {
        val prefs = getPrefs(context)
        
        if (!areRemindersEnabled(prefs)) {
            Log.d(TAG, "Reminders are disabled, skipping scheduling")
            return
        }
        
        // Cancel any existing alarms first
        cancelAllReminders(context)
        
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)
        val lastDayOfMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val currentDay = calendar.get(Calendar.DAY_OF_MONTH)
        
        Log.d(TAG, "Scheduling reminders for $currentYear-${currentMonth + 1}, today is day $currentDay, last day is $lastDayOfMonth")
        
        // Get preferred reminder time (default 9 AM)
        val reminderHour = prefs.getInt(KEY_REMINDER_HOUR, 9)
        val reminderMinute = prefs.getInt(KEY_REMINDER_MINUTE, 0)
        
        // Schedule 3-day reminder
        val threeDaysBefore = lastDayOfMonth - 3
        if (currentDay <= threeDaysBefore) {
            scheduleReminder(
                context = context,
                day = threeDaysBefore,
                hour = reminderHour,
                minute = reminderMinute,
                requestCode = REQUEST_CODE_3_DAYS,
                action = EndOfMonthReminderReceiver.ACTION_REMIND_SCAN
            )
        }
        
        // Schedule 1-day reminder
        val oneDayBefore = lastDayOfMonth - 1
        if (currentDay <= oneDayBefore) {
            scheduleReminder(
                context = context,
                day = oneDayBefore,
                hour = reminderHour,
                minute = reminderMinute,
                requestCode = REQUEST_CODE_1_DAY,
                action = EndOfMonthReminderReceiver.ACTION_REMIND_SCAN
            )
        }
        
        // Schedule last day morning reminder (9 AM)
        if (currentDay <= lastDayOfMonth) {
            scheduleReminder(
                context = context,
                day = lastDayOfMonth,
                hour = 9,
                minute = 0,
                requestCode = REQUEST_CODE_LAST_DAY_MORNING,
                action = EndOfMonthReminderReceiver.ACTION_LAST_DAY_REMINDER
            )
        }
        
        // Schedule last day evening reminder (6 PM)
        if (currentDay <= lastDayOfMonth) {
            scheduleReminder(
                context = context,
                day = lastDayOfMonth,
                hour = 18,
                minute = 0,
                requestCode = REQUEST_CODE_LAST_DAY_EVENING,
                action = EndOfMonthReminderReceiver.ACTION_LAST_DAY_REMINDER
            )
        }
        
        // Update last reminder month
        prefs.edit().putString(KEY_LAST_REMINDER_MONTH, "$currentYear-${currentMonth + 1}").apply()
    }
    
    /**
     * Schedule a single reminder at the specified time.
     */
    private fun scheduleReminder(
        context: Context,
        day: Int,
        hour: Int,
        minute: Int,
        requestCode: Int,
        action: String
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        // Don't schedule if time has already passed
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            Log.d(TAG, "Skipping reminder for day $day - time has passed")
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
        
        // Use setExactAndAllowWhileIdle for reliable delivery
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ requires checking for exact alarm permission
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                } else {
                    // Fall back to inexact alarm
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
            
            Log.d(TAG, "Scheduled reminder: day=$day, hour=$hour, action=$action")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to schedule exact alarm", e)
            // Fall back to regular alarm
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
