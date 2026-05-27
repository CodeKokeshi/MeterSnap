package com.kokeshi.metersnap.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.kokeshi.metersnap.utils.ChargeDateHelper
import com.kokeshi.metersnap.utils.HistoryManager
import java.util.Calendar

/**
 * Schedules a configurable "scan check-in" reminder within the active billing period.
 *
 * If the user has not scanned yet this period, they are reminded every X days.
 * Once a scan exists for the period, reminders are deferred until the next period.
 */
object PeriodicScanReminderScheduler {

    private const val TAG = "PeriodicScanReminder"
    private const val DAY_IN_MILLIS = 24L * 60L * 60L * 1000L

    const val PREFS_NAME = "periodic_scan_prefs"
    const val KEY_ENABLED = "periodic_enabled"
    const val KEY_INTERVAL_DAYS = "periodic_interval_days"
    const val KEY_HOUR = "periodic_hour"
    const val KEY_MINUTE = "periodic_minute"

    private const val REQUEST_CODE = 4001
    const val ACTION_PERIODIC_SCAN_REMINDER =
        "com.kokeshi.metersnap.ACTION_PERIODIC_SCAN_REMINDER"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    fun getIntervalDays(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_INTERVAL_DAYS, 7).coerceIn(1, 90)

    fun setIntervalDays(context: Context, days: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_INTERVAL_DAYS, days.coerceIn(1, 90)).apply()
        if (isEnabled(context)) schedule(context)
    }

    fun getReminderTime(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(prefs.getInt(KEY_HOUR, 9), prefs.getInt(KEY_MINUTE, 0))
    }

    fun setReminderTime(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply()
        if (isEnabled(context)) schedule(context)
    }

    fun schedule(context: Context) {
        if (!isEnabled(context)) {
            cancel(context)
            return
        }

        val interval = getIntervalDays(context)
        val (hour, minute) = getReminderTime(context)
        val triggerAtMillis = computeNextTriggerAtMillis(context, interval, hour, minute)

        scheduleAt(context, triggerAtMillis)
        Log.d(
            TAG,
            "Scheduled next periodic reminder at ${
                Calendar.getInstance().apply { timeInMillis = triggerAtMillis }.time
            }"
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context))
        Log.d(TAG, "Periodic reminder cancelled")
    }

    private fun computeNextTriggerAtMillis(
        context: Context,
        intervalDays: Int,
        hour: Int,
        minute: Int
    ): Long {
        val chargeDate = ChargeDateHelper.getChargeDate(context)
        val currentPeriodKey = ChargeDateHelper.getCurrentBillingPeriodKey(chargeDate)
        val currentPeriodStart = ChargeDateHelper.getBillingPeriodStartTimestamp(currentPeriodKey, chargeDate)
        val currentPeriodEnd = ChargeDateHelper.getBillingPeriodEndTimestamp(currentPeriodKey, chargeDate)
        val nextPeriodStart = getNextPeriodStartTimestamp(currentPeriodKey, chargeDate)

        return if (hasScanInPeriod(context, currentPeriodStart, currentPeriodEnd)) {
            Calendar.getInstance().apply {
                timeInMillis = nextPeriodStart
                add(Calendar.DAY_OF_MONTH, intervalDays)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } else {
            val now = System.currentTimeMillis()
            val daysElapsed = ((now - currentPeriodStart) / DAY_IN_MILLIS).toInt().coerceAtLeast(0)
            val nextIntervalNumber = (daysElapsed / intervalDays) + 1
            val candidate = Calendar.getInstance().apply {
                timeInMillis = currentPeriodStart
                add(Calendar.DAY_OF_MONTH, nextIntervalNumber * intervalDays)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (candidate.timeInMillis <= now) {
                candidate.add(Calendar.DAY_OF_MONTH, intervalDays)
            }

            if (candidate.timeInMillis <= currentPeriodEnd) {
                candidate.timeInMillis
            } else {
                Calendar.getInstance().apply {
                    timeInMillis = nextPeriodStart
                    add(Calendar.DAY_OF_MONTH, intervalDays)
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
        }
    }

    private fun hasScanInPeriod(context: Context, startMillis: Long, endMillis: Long): Boolean {
        return HistoryManager(context)
            .getAllHistory()
            .any { entry -> entry.timestamp in startMillis..endMillis }
    }

    private fun getNextPeriodStartTimestamp(periodKey: String, chargeDate: Int): Long {
        val parts = periodKey.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: return System.currentTimeMillis()
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return System.currentTimeMillis()
        val nextPeriod = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, 1)
        }
        val nextPeriodKey = String.format(
            "%04d-%02d",
            nextPeriod.get(Calendar.YEAR),
            nextPeriod.get(Calendar.MONTH) + 1
        )
        return ChargeDateHelper.getBillingPeriodStartTimestamp(nextPeriodKey, chargeDate)
    }

    private fun scheduleAt(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pi
                    )
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pi
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Exact alarm denied - falling back to inexact", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PeriodicScanReminderReceiver::class.java).apply {
            action = ACTION_PERIODIC_SCAN_REMINDER
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
