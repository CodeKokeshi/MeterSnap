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
import com.kokeshi.metersnap.utils.ReadingPredictor
import java.util.*

/**
 * BroadcastReceiver that handles end-of-month scan reminder notifications.
 * 
 * This reminds users to scan their meter on the last day of the month
 * to get accurate readings for billing predictions.
 */
class EndOfMonthReminderReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "EndOfMonthReminder"
        const val CHANNEL_ID = "meter_snap_eom_reminders"
        const val NOTIFICATION_ID_REMINDER = 1001
        const val NOTIFICATION_ID_LAST_DAY = 1002
        
        const val ACTION_REMIND_SCAN = "com.kokeshi.metersnap.ACTION_REMIND_SCAN"
        const val ACTION_LAST_DAY_REMINDER = "com.kokeshi.metersnap.ACTION_LAST_DAY_REMINDER"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")
        
        when (intent.action) {
            ACTION_REMIND_SCAN -> showUpcomingMonthEndReminder(context)
            ACTION_LAST_DAY_REMINDER -> showLastDayReminder(context)
            Intent.ACTION_BOOT_COMPLETED -> {
                // Reschedule alarms after device reboot
                EndOfMonthReminderScheduler.scheduleReminders(context)
            }
        }
    }
    
    /**
     * Show a reminder that the month end is approaching (2-3 days before).
     */
    private fun showUpcomingMonthEndReminder(context: Context) {
        val predictor = ReadingPredictor(context)
        val daysRemaining = predictor.getDaysUntilMonthEnd()
        
        val title = "Month-End Scan Reminder"
        val message = if (daysRemaining == 1) {
            "Tomorrow is the last day of the month! Scan your meter for accurate billing."
        } else {
            "Only $daysRemaining days left this month. Consider scanning your water meter."
        }
        
        showNotification(
            context = context,
            notificationId = NOTIFICATION_ID_REMINDER,
            title = title,
            message = message,
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }
    
    /**
     * Show a high-priority reminder on the last day of the month.
     */
    private fun showLastDayReminder(context: Context) {
        val title = "📊 Last Day of the Month!"
        val message = "Scan your water meter now for the most accurate end-of-month reading. This helps predict your next bill!"
        
        showNotification(
            context = context,
            notificationId = NOTIFICATION_ID_LAST_DAY,
            title = title,
            message = message,
            priority = NotificationCompat.PRIORITY_HIGH
        )
    }
    
    /**
     * Display a notification with the given content.
     */
    private fun showNotification(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        priority: Int
    ) {
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Notification permission not granted")
                return
            }
        }
        
        // Create notification channel
        createNotificationChannel(context)
        
        // Intent to open the app
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("from_notification", true)
            putExtra("show_scanner", true)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build the notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use app icon
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        
        // Show the notification
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            Log.d(TAG, "Notification shown: $title")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification due to permission", e)
        }
    }
    
    /**
     * Create the notification channel (required for Android O+).
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Month-End Reminders"
            val descriptionText = "Reminders to scan your water meter at the end of each month"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
