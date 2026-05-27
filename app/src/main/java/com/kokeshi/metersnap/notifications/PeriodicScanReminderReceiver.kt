package com.kokeshi.metersnap.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kokeshi.metersnap.MainActivity
import com.kokeshi.metersnap.R
import com.kokeshi.metersnap.utils.ChargeDateHelper
import com.kokeshi.metersnap.utils.HistoryManager

/**
 * Receiver for billing-period scan check-in reminders.
 */
class PeriodicScanReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PeriodicScanReceiver"
        private const val CHANNEL_ID = "meter_snap_periodic_scan"
        private const val NOTIFICATION_ID = 1003
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PeriodicScanReminderScheduler.ACTION_PERIODIC_SCAN_REMINDER) return

        Log.d(TAG, "Periodic scan reminder received")

        val intervalDays = PeriodicScanReminderScheduler.getIntervalDays(context)
        val chargeDate = ChargeDateHelper.getChargeDate(context)
        val currentPeriodKey = ChargeDateHelper.getCurrentBillingPeriodKey(chargeDate)
        val periodStart = ChargeDateHelper.getBillingPeriodStartTimestamp(currentPeriodKey, chargeDate)
        val periodEnd = ChargeDateHelper.getBillingPeriodEndTimestamp(currentPeriodKey, chargeDate)
        val hasScanThisPeriod = HistoryManager(context)
            .getAllHistory()
            .any { entry -> entry.timestamp in periodStart..periodEnd }
        val daysElapsed = ((System.currentTimeMillis() - periodStart) / (24L * 60L * 60L * 1000L)).toInt()

        Log.d(
            TAG,
            "Billing period reminder state: hasScanThisPeriod=$hasScanThisPeriod, daysElapsed=$daysElapsed, interval=$intervalDays"
        )

        if (!hasScanThisPeriod && daysElapsed >= intervalDays) {
            showNotification(context, daysElapsed)
        } else {
            Log.d(TAG, "Skipping notification for current billing period")
        }

        PeriodicScanReminderScheduler.schedule(context)
    }

    private fun showNotification(context: Context, daysSince: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted - skipping notification")
            return
        }

        createChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("from_notification", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = "You haven't scanned in $daysSince day${if (daysSince != 1) "s" else ""}. " +
            "Scan now for a more accurate projection."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Scan Reminder")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Periodic scan notification sent")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to send notification", e)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scan Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminders to scan your meter during the billing period for accurate bill projections"
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
