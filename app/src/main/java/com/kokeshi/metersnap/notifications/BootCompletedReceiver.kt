package com.kokeshi.metersnap.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver that triggers on device boot to reschedule reminder alarms.
 * 
 * Alarms are lost when the device reboots, so we need to reschedule them.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed, rescheduling reminders")
            EndOfMonthReminderScheduler.scheduleReminders(context)
            PeriodicScanReminderScheduler.schedule(context)
        }
    }
}
