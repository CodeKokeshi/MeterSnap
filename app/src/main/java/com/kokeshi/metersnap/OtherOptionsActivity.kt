package com.kokeshi.metersnap

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import com.kokeshi.metersnap.data.ScanHistoryEntry
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.data.WaterRates
import com.kokeshi.metersnap.notifications.EndOfMonthReminderScheduler
import com.kokeshi.metersnap.utils.BillCalculator
import com.kokeshi.metersnap.utils.DeveloperOptions
import com.kokeshi.metersnap.utils.HistoryManager
import com.kokeshi.metersnap.utils.ProjectedReadingManager
import com.kokeshi.metersnap.utils.RatePreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Activity for miscellaneous options:
 * - End-of-month reminders
 * - Prediction statistics
 * - Developer settings (debug strip, fake prev, generate reading)
 */
class OtherOptionsActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var switchReminders: SwitchMaterial
    private lateinit var containerReminderTime: LinearLayout
    private lateinit var textReminderTime: TextView
    private lateinit var cardPredictionStats: CardView
    private lateinit var textPredictionStats: TextView

    // Developer settings
    private lateinit var switchDebugStrip: SwitchMaterial
    private lateinit var switchFakePrev: SwitchMaterial
    private lateinit var switchForceDualBilling: SwitchMaterial
    private lateinit var btnGenerateReading: Button

    private lateinit var reminderPrefs: SharedPreferences
    private lateinit var devPrefs: SharedPreferences
    private lateinit var projectedReadingManager: ProjectedReadingManager
    private lateinit var historyManager: HistoryManager

    companion object {
        private const val PREFS_NAME = "eom_reminder_prefs"
        private const val KEY_REMINDERS_ENABLED = "reminders_enabled"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_REMINDER_MINUTE = "reminder_minute"
        private const val DEV_PREFS_NAME = "developer_settings"
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
        devPrefs = getSharedPreferences(DEV_PREFS_NAME, Context.MODE_PRIVATE)
        projectedReadingManager = ProjectedReadingManager(this)
        historyManager = HistoryManager(this)

        initializeViews()
        setupToolbar()
        setupWindowInsets()
        loadNotificationSettings()
        loadPredictionStats()
        loadDeveloperSettings()
        setupListeners()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        switchReminders = findViewById(R.id.switch_reminders)
        containerReminderTime = findViewById(R.id.container_reminder_time)
        textReminderTime = findViewById(R.id.text_reminder_time)
        cardPredictionStats = findViewById(R.id.card_prediction_stats)
        textPredictionStats = findViewById(R.id.text_prediction_stats)

        // Developer settings
        switchDebugStrip = findViewById(R.id.switch_debug_strip)
        switchFakePrev = findViewById(R.id.switch_fake_prev)
        switchForceDualBilling = findViewById(R.id.switch_force_dual_billing)
        btnGenerateReading = findViewById(R.id.btn_generate_reading)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    private fun setupWindowInsets() {
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

    private fun loadDeveloperSettings() {
        switchDebugStrip.isChecked = devPrefs.getBoolean("debug_strip_enabled", false)
        switchFakePrev.isChecked = devPrefs.getBoolean("fake_prev_enabled", false)
        switchForceDualBilling.isChecked = DeveloperOptions.isForceDualBillingEnabled(this)
    }

    private fun setupListeners() {
        switchReminders.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
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

        // Developer settings listeners
        switchDebugStrip.setOnCheckedChangeListener { _, isChecked ->
            devPrefs.edit().putBoolean("debug_strip_enabled", isChecked).apply()
            val status = if (isChecked) "enabled" else "disabled"
            Toast.makeText(this, "Debug Strip $status", Toast.LENGTH_SHORT).show()
        }

        switchFakePrev.setOnCheckedChangeListener { _, isChecked ->
            devPrefs.edit().putBoolean("fake_prev_enabled", isChecked).apply()
            val status = if (isChecked) "enabled" else "disabled"
            Toast.makeText(this, "Fake Previous Month $status", Toast.LENGTH_SHORT).show()
        }

        switchForceDualBilling.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                AlertDialog.Builder(this)
                    .setTitle("Enable Forced Dual Billing?")
                    .setMessage(
                        "Developer mode only.\n\n" +
                            "When enabled, camera scans and Manual Input can open Dual Billing even without a real discrepancy. " +
                            "The app will inject a synthetic comparison value for screenshots, and that forced discrepancy will not be saved to history."
                    )
                    .setPositiveButton("Enable") { _, _ ->
                        DeveloperOptions.setForceDualBillingEnabled(this, true)
                        Toast.makeText(this, "Forced Dual Billing enabled", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        switchForceDualBilling.isChecked = false
                    }
                    .setOnCancelListener {
                        switchForceDualBilling.isChecked = false
                    }
                    .show()
            } else {
                DeveloperOptions.setForceDualBillingEnabled(this, false)
                Toast.makeText(this, "Forced Dual Billing disabled", Toast.LENGTH_SHORT).show()
            }
        }

        btnGenerateReading.setOnClickListener {
            showGenerateReadingDialog()
        }
    }

    private fun enableReminders() {
        reminderPrefs.edit().putBoolean(KEY_REMINDERS_ENABLED, true).apply()
        containerReminderTime.visibility = View.VISIBLE
        EndOfMonthReminderScheduler.scheduleReminders(this)
        Toast.makeText(this, "Billing period reminders enabled", Toast.LENGTH_SHORT).show()
    }

    private fun disableReminders() {
        reminderPrefs.edit().putBoolean(KEY_REMINDERS_ENABLED, false).apply()
        containerReminderTime.visibility = View.GONE
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

                if (switchReminders.isChecked) {
                    EndOfMonthReminderScheduler.scheduleReminders(this)
                }

                Toast.makeText(this, "Reminder time updated", Toast.LENGTH_SHORT).show()
            },
            currentHour,
            currentMinute,
            false
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

    /**
     * Show dialog to generate a fake reading entry with custom date, previous, and current reading.
     */
    private fun showGenerateReadingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_generate_reading, null)

        val textSelectedDate = dialogView.findViewById<TextView>(R.id.text_selected_date)
        val btnPickDate = dialogView.findViewById<Button>(R.id.btn_pick_date)
        val editPreviousReading = dialogView.findViewById<EditText>(R.id.edit_previous_reading)
        val editCurrentReading = dialogView.findViewById<EditText>(R.id.edit_current_reading)

        // Default to today
        val selectedCalendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        textSelectedDate.text = dateFormat.format(selectedCalendar.time)

        btnPickDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    selectedCalendar.set(year, month, dayOfMonth)
                    textSelectedDate.text = dateFormat.format(selectedCalendar.time)
                },
                selectedCalendar.get(Calendar.YEAR),
                selectedCalendar.get(Calendar.MONTH),
                selectedCalendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        AlertDialog.Builder(this)
            .setTitle("Generate Fake Reading")
            .setView(dialogView)
            .setPositiveButton("Generate") { _, _ ->
                val prevReading = editPreviousReading.text.toString().toIntOrNull()
                val currReading = editCurrentReading.text.toString().toIntOrNull()

                if (prevReading == null || currReading == null) {
                    Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (currReading < prevReading) {
                    Toast.makeText(this, "Current reading must be >= previous reading", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                generateFakeReading(selectedCalendar, prevReading, currReading)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Generate a fake history entry and save it.
     */
    private fun generateFakeReading(calendar: Calendar, previousReading: Int, currentReading: Int) {
        val usage = currentReading - previousReading

        // Calculate bill using current rate settings
        val ratePreferences = RatePreferences(this)
        val rateType = ratePreferences.getRateType()
        val customRates = ratePreferences.getCustomRates()
        val esfRate = if (rateType == WaterRateType.CUSTOM) {
            ratePreferences.getESFRate()
        } else {
            WaterRates.getESFForType(rateType)
        }

        val billResult = BillCalculator.calculateBill(
            usage = usage, rateType = rateType, customRates = customRates, esfRate = esfRate
        )

        // Set time to noon for the selected date
        calendar.set(Calendar.HOUR_OF_DAY, 12)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)

        val fakeEntry = ScanHistoryEntry(
            id = UUID.randomUUID().toString(),
            timestamp = calendar.timeInMillis,
            currentReading = currentReading,
            previousReading = previousReading,
            usage = usage,
            rateType = rateType.name,
            waterBill = billResult.waterBill,
            esfFee = billResult.esfFee,
            totalBill = billResult.totalBill,
            tier1Usage = billResult.tier1Usage,
            tier1Amount = billResult.tier1Amount,
            tier2Usage = billResult.tier2Usage,
            tier2Amount = billResult.tier2Amount,
            tier3Usage = billResult.tier3Usage,
            tier3Amount = billResult.tier3Amount,
            tier4Usage = billResult.tier4Usage,
            tier4Amount = billResult.tier4Amount,
            tier5Usage = billResult.tier5Usage,
            tier5Amount = billResult.tier5Amount
        )

        historyManager.addScanEntry(fakeEntry)

        val dateFormatDisplay = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        Toast.makeText(
            this,
            "Fake reading generated!\nDate: ${dateFormatDisplay.format(calendar.time)}\nUsage: $usage m³\nBill: ${fakeEntry.getFormattedTotalBill()}",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
