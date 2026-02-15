package com.kokeshi.metersnap

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.kokeshi.metersnap.notifications.EndOfMonthReminderScheduler
import com.kokeshi.metersnap.utils.ProjectedReadingManager
import java.util.Locale

/**
 * Activity for miscellaneous options:
 * - End-of-month reminders
 * - Prediction statistics
 */
class OtherOptionsActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var switchReminders: SwitchMaterial
    private lateinit var containerReminderTime: LinearLayout
    private lateinit var textReminderTime: TextView
    private lateinit var cardPredictionStats: CardView
    private lateinit var textPredictionStats: TextView

    private lateinit var reminderPrefs: SharedPreferences
    private lateinit var projectedReadingManager: ProjectedReadingManager

    companion object {
        private const val PREFS_NAME = "eom_reminder_prefs"
        private const val KEY_REMINDERS_ENABLED = "reminders_enabled"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_REMINDER_MINUTE = "reminder_minute"
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            enableReminders()
        } else {
            switchReminders.isChecked = false
            Toast.makeText(this, "Notification permission required for reminders", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_other_options)

        reminderPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        projectedReadingManager = ProjectedReadingManager(this)

        initializeViews()
        setupToolbar()
        setupWindowInsets()
        loadNotificationSettings()
        loadPredictionStats()
        setupListeners()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        switchReminders = findViewById(R.id.switch_reminders)
        containerReminderTime = findViewById(R.id.container_reminder_time)
        textReminderTime = findViewById(R.id.text_reminder_time)
        cardPredictionStats = findViewById(R.id.card_prediction_stats)
        textPredictionStats = findViewById(R.id.text_prediction_stats)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    private fun setupWindowInsets() {
        // Apply status bar inset to toolbar
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, windowInsets ->
            val statusBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                statusBarInsets.top,
                view.paddingRight,
                view.paddingBottom
            )
            windowInsets
        }

        // Apply navigation bar inset to root
        val rootView = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, systemBars.bottom)
            windowInsets
        }
    }

    private fun loadNotificationSettings() {
        val isEnabled = reminderPrefs.getBoolean(KEY_REMINDERS_ENABLED, false)
        val hour = reminderPrefs.getInt(KEY_REMINDER_HOUR, 9)
        val minute = reminderPrefs.getInt(KEY_REMINDER_MINUTE, 0)

        switchReminders.isChecked = isEnabled
        containerReminderTime.visibility = if (isEnabled) View.VISIBLE else View.GONE
        updateReminderTimeDisplay(hour, minute)
    }

    private fun loadPredictionStats() {
        val stats = projectedReadingManager.getProjectionAccuracyStats()

        if (stats.hasEnoughData()) {
            cardPredictionStats.visibility = View.VISIBLE
            val avgError = String.format(Locale.US, "%.1f", stats.meanAbsoluteError)
            textPredictionStats.text = buildString {
                append("Samples: ${stats.sampleCount}\n")
                append("Average error: $avgError m³\n")
                append("Grade: ${stats.getAccuracyGrade()}")
            }
        } else {
            cardPredictionStats.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        switchReminders.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Check notification permission for Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                        == PackageManager.PERMISSION_GRANTED) {
                        enableReminders()
                    } else {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    enableReminders()
                }
            } else {
                disableReminders()
            }
        }

        textReminderTime.setOnClickListener {
            showTimePickerDialog()
        }
    }

    private fun enableReminders() {
        reminderPrefs.edit().putBoolean(KEY_REMINDERS_ENABLED, true).apply()
        containerReminderTime.visibility = View.VISIBLE

        // Schedule reminders
        EndOfMonthReminderScheduler.scheduleReminders(this)

        Toast.makeText(this, "End-of-month reminders enabled", Toast.LENGTH_SHORT).show()
    }

    private fun disableReminders() {
        reminderPrefs.edit().putBoolean(KEY_REMINDERS_ENABLED, false).apply()
        containerReminderTime.visibility = View.GONE

        // Cancel all reminders
        EndOfMonthReminderScheduler.cancelAllReminders(this)

        Toast.makeText(this, "Reminders disabled", Toast.LENGTH_SHORT).show()
    }

    private fun showTimePickerDialog() {
        val currentHour = reminderPrefs.getInt(KEY_REMINDER_HOUR, 9)
        val currentMinute = reminderPrefs.getInt(KEY_REMINDER_MINUTE, 0)

        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                reminderPrefs.edit()
                    .putInt(KEY_REMINDER_HOUR, hourOfDay)
                    .putInt(KEY_REMINDER_MINUTE, minute)
                    .apply()

                updateReminderTimeDisplay(hourOfDay, minute)

                // Reschedule with new time
                if (switchReminders.isChecked) {
                    EndOfMonthReminderScheduler.scheduleReminders(this)
                }

                Toast.makeText(this, "Reminder time updated", Toast.LENGTH_SHORT).show()
            },
            currentHour,
            currentMinute,
            false // 12-hour format
        ).show()
    }

    private fun updateReminderTimeDisplay(hour: Int, minute: Int) {
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        textReminderTime.text = String.format(Locale.US, "%d:%02d %s", displayHour, minute, amPm)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
