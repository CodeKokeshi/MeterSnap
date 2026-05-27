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
import com.kokeshi.metersnap.notifications.PeriodicScanReminderScheduler
import com.kokeshi.metersnap.utils.BillCalculator
import com.kokeshi.metersnap.utils.ChargeDateHelper
import com.kokeshi.metersnap.utils.DeveloperOptions
import com.kokeshi.metersnap.utils.HistoryManager
import com.kokeshi.metersnap.utils.ProjectedReadingManager
import com.kokeshi.metersnap.utils.RatePreferences
import com.kokeshi.metersnap.utils.RateTableManager
import com.kokeshi.metersnap.utils.ThemePreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Activity for miscellaneous options:
 * - End-of-month reminders
 * - Periodic scan reminders
 * - Prediction statistics
 * - Developer settings (debug strip, fake prev, generate reading)
 */
class OtherOptionsActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var switchReminders: SwitchMaterial
    private lateinit var containerReminderTime: LinearLayout
    private lateinit var textReminderTime: TextView
    private lateinit var switchPeriodicScanReminders: SwitchMaterial
    private lateinit var containerPeriodicScanDetails: LinearLayout
    private lateinit var textPeriodicScanInterval: TextView
    private lateinit var textPeriodicScanTime: TextView
    private lateinit var cardPredictionStats: CardView
    private lateinit var textPredictionStats: TextView
    private lateinit var switchLightMode: SwitchMaterial

    private lateinit var switchDeveloperSettings: SwitchMaterial
    private lateinit var containerDeveloperControls: LinearLayout
    private lateinit var switchDebugStrip: SwitchMaterial
    private lateinit var switchFakePrev: SwitchMaterial
    private lateinit var switchForceDualBilling: SwitchMaterial
    private lateinit var btnScannerTuning: Button
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
        private const val KEY_DEVELOPER_SETTINGS_ENABLED = "developer_settings_master_enabled"
    }

    private var pendingNotificationPermissionAction: (() -> Unit)? = null
    private var pendingNotificationPermissionReset: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingNotificationPermissionAction?.invoke()
        } else {
            pendingNotificationPermissionReset?.invoke()
            Toast.makeText(this, "Notification permission required for reminders", Toast.LENGTH_LONG).show()
        }
        pendingNotificationPermissionAction = null
        pendingNotificationPermissionReset = null
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
        switchPeriodicScanReminders = findViewById(R.id.switch_periodic_scan_reminders)
        containerPeriodicScanDetails = findViewById(R.id.container_periodic_scan_details)
        textPeriodicScanInterval = findViewById(R.id.text_periodic_scan_interval)
        textPeriodicScanTime = findViewById(R.id.text_periodic_scan_time)
        cardPredictionStats = findViewById(R.id.card_prediction_stats)
        textPredictionStats = findViewById(R.id.text_prediction_stats)
        switchLightMode = findViewById(R.id.switch_light_mode)

        switchDeveloperSettings = findViewById(R.id.switch_developer_settings)
        containerDeveloperControls = findViewById(R.id.container_developer_controls)
        switchDebugStrip = findViewById(R.id.switch_debug_strip)
        switchFakePrev = findViewById(R.id.switch_fake_prev)
        switchForceDualBilling = findViewById(R.id.switch_force_dual_billing)
        btnScannerTuning = findViewById(R.id.btn_scanner_tuning)
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
        val isEnabled = reminderPrefs.getBoolean(KEY_REMINDERS_ENABLED, true)
        val hour = reminderPrefs.getInt(KEY_REMINDER_HOUR, 9)
        val minute = reminderPrefs.getInt(KEY_REMINDER_MINUTE, 0)
        val periodicEnabled = PeriodicScanReminderScheduler.isEnabled(this)
        val periodicInterval = PeriodicScanReminderScheduler.getIntervalDays(this)
        val (periodicHour, periodicMinute) = PeriodicScanReminderScheduler.getReminderTime(this)

        switchReminders.isChecked = isEnabled
        containerReminderTime.visibility = if (isEnabled) View.VISIBLE else View.GONE
        updateReminderTimeDisplay(hour, minute)

        switchPeriodicScanReminders.isChecked = periodicEnabled
        containerPeriodicScanDetails.visibility = if (periodicEnabled) View.VISIBLE else View.GONE
        updatePeriodicIntervalDisplay(periodicInterval)
        updatePeriodicReminderTimeDisplay(periodicHour, periodicMinute)
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
        switchLightMode.isChecked = ThemePreferences.isLightModeEnabled(this)
        val developerSettingsEnabled = devPrefs.getBoolean(KEY_DEVELOPER_SETTINGS_ENABLED, false)
        switchDeveloperSettings.isChecked = developerSettingsEnabled
        switchDebugStrip.isChecked = devPrefs.getBoolean("debug_strip_enabled", false)
        switchFakePrev.isChecked = devPrefs.getBoolean("fake_prev_enabled", false)
        switchForceDualBilling.isChecked = DeveloperOptions.isForceDualBillingEnabled(this)
        updateDeveloperSectionVisibility(developerSettingsEnabled)
    }

    private fun setupListeners() {
        switchLightMode.setOnCheckedChangeListener { _, isChecked ->
            ThemePreferences.setLightModeEnabled(this, isChecked)
            val mode = if (isChecked) "Light mode" else "Dark mode"
            Toast.makeText(this, "$mode enabled", Toast.LENGTH_SHORT).show()
        }

        switchDeveloperSettings.setOnCheckedChangeListener { _, isChecked ->
            devPrefs.edit().putBoolean(KEY_DEVELOPER_SETTINGS_ENABLED, isChecked).apply()
            updateDeveloperSectionVisibility(isChecked)
        }

        switchReminders.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                ensureNotificationPermissionOrRun(
                    onGranted = { enableReminders() },
                    onDeniedReset = { switchReminders.isChecked = false }
                )
            } else {
                disableReminders()
            }
        }

        switchPeriodicScanReminders.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                ensureNotificationPermissionOrRun(
                    onGranted = { enablePeriodicScanReminders() },
                    onDeniedReset = { switchPeriodicScanReminders.isChecked = false }
                )
            } else {
                disablePeriodicScanReminders()
            }
        }

        textReminderTime.setOnClickListener {
            showTimePickerDialog()
        }

        textPeriodicScanInterval.setOnClickListener {
            showPeriodicIntervalDialog()
        }

        textPeriodicScanTime.setOnClickListener {
            showPeriodicTimePickerDialog()
        }

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

        btnScannerTuning.setOnClickListener {
            startActivity(android.content.Intent(this, ScannerTuningActivity::class.java))
        }

        btnGenerateReading.setOnClickListener {
            showGenerateReadingDialog()
        }
    }

    private fun updateDeveloperSectionVisibility(isEnabled: Boolean) {
        containerDeveloperControls.visibility = if (isEnabled) View.VISIBLE else View.GONE
    }

    private fun ensureNotificationPermissionOrRun(
        onGranted: () -> Unit,
        onDeniedReset: () -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationPermissionAction = onGranted
            pendingNotificationPermissionReset = onDeniedReset
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onGranted()
        }
    }

    private fun enableReminders() {
        reminderPrefs.edit().putBoolean(KEY_REMINDERS_ENABLED, true).apply()
        containerReminderTime.visibility = View.VISIBLE
        EndOfMonthReminderScheduler.scheduleReminders(this)
        if (!ChargeDateHelper.isChargeDateSet(this)) {
            Toast.makeText(
                this,
                "Reminders enabled. Note: using default day 28 — set your charge date in History & Readings to customize.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "Billing period reminders enabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disableReminders() {
        reminderPrefs.edit().putBoolean(KEY_REMINDERS_ENABLED, false).apply()
        containerReminderTime.visibility = View.GONE
        EndOfMonthReminderScheduler.cancelAllReminders(this)
        Toast.makeText(this, "Reminders disabled", Toast.LENGTH_SHORT).show()
    }

    private fun enablePeriodicScanReminders() {
        PeriodicScanReminderScheduler.setEnabled(this, true)
        containerPeriodicScanDetails.visibility = View.VISIBLE
        Toast.makeText(this, "Scan check-in reminders enabled", Toast.LENGTH_SHORT).show()
    }

    private fun disablePeriodicScanReminders() {
        PeriodicScanReminderScheduler.setEnabled(this, false)
        containerPeriodicScanDetails.visibility = View.GONE
        Toast.makeText(this, "Scan check-in reminders disabled", Toast.LENGTH_SHORT).show()
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

    private fun showPeriodicTimePickerDialog() {
        val (currentHour, currentMinute) = PeriodicScanReminderScheduler.getReminderTime(this)

        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                PeriodicScanReminderScheduler.setReminderTime(this, hourOfDay, minute)
                updatePeriodicReminderTimeDisplay(hourOfDay, minute)
                Toast.makeText(this, "Scan reminder time updated", Toast.LENGTH_SHORT).show()
            },
            currentHour,
            currentMinute,
            false
        ).show()
    }

    private fun showPeriodicIntervalDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(PeriodicScanReminderScheduler.getIntervalDays(this@OtherOptionsActivity).toString())
            setSelection(text.length)
        }

        val container = FrameLayout(this).apply {
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            val margin = (16 * resources.displayMetrics.density).toInt()
            params.leftMargin = margin
            params.rightMargin = margin
            input.layoutParams = params
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Scan Reminder Interval")
            .setMessage("Choose how many days to wait before reminding you again if you still have not scanned this billing period.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val days = input.text.toString().toIntOrNull()
                if (days == null) {
                    Toast.makeText(this, "Enter a valid number of days", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                PeriodicScanReminderScheduler.setIntervalDays(this, days)
                updatePeriodicIntervalDisplay(PeriodicScanReminderScheduler.getIntervalDays(this))
                Toast.makeText(this, "Scan reminder interval updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateReminderTimeDisplay(hour: Int, minute: Int) {
        textReminderTime.text = formatTime(hour, minute)
    }

    private fun updatePeriodicReminderTimeDisplay(hour: Int, minute: Int) {
        textPeriodicScanTime.text = formatTime(hour, minute)
    }

    private fun updatePeriodicIntervalDisplay(days: Int) {
        textPeriodicScanInterval.text = if (days == 1) "Every 1 day" else "Every $days days"
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format(Locale.US, "%d:%02d %s", displayHour, minute, amPm)
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

        val ratePreferences = RatePreferences(this)
        val rateType = ratePreferences.getRateType()
        val customRates = ratePreferences.getCustomRates()

        val billResult: BillCalculator.BillResult = if (rateType == WaterRateType.CUSTOM) {
            val esfRate = ratePreferences.getESFRate()
            BillCalculator.calculateBill(
                usage = usage,
                rateType = rateType,
                customRates = customRates,
                esfRate = esfRate
            )
        } else {
            val rateTableManager = RateTableManager(this)
            val serviceType = rateTableManager.getServiceType(rateType.name)
            if (serviceType != null) {
                BillCalculator.calculateBillDynamic(
                    usage = usage,
                    serviceType = serviceType,
                    esfRate = rateTableManager.getEsfRate()
                )
            } else {
                val esfRate = WaterRates.getESFForType(rateType)
                BillCalculator.calculateBill(
                    usage = usage,
                    rateType = rateType,
                    customRates = customRates,
                    esfRate = esfRate
                )
            }
        }

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
        projectedReadingManager.recalculateProjectionsAfterHistoryChange(calendar.timeInMillis)
        PeriodicScanReminderScheduler.schedule(this)

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
