package com.kokeshi.metersnap

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.data.WaterRates
import com.kokeshi.metersnap.data.ScanHistoryEntry
import com.kokeshi.metersnap.notifications.EndOfMonthReminderScheduler
import com.kokeshi.metersnap.utils.BillCalculator
import com.kokeshi.metersnap.utils.RatePreferences
import com.kokeshi.metersnap.utils.RateTableManager
import com.kokeshi.metersnap.utils.BillCalculator.BillResult
import com.kokeshi.metersnap.utils.DeveloperOptions
import com.kokeshi.metersnap.utils.HistoryManager
import com.kokeshi.metersnap.utils.ProjectedReadingManager
import com.kokeshi.metersnap.utils.SessionResetManager

class ManualInputActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var editCurrentReading: TextInputEditText
    private lateinit var editPreviousReading: TextInputEditText
    private lateinit var btnCalculate: Button
    private lateinit var btnTorchManual: ImageButton
    private lateinit var ratePreferences: RatePreferences
    private lateinit var rateTableManager: RateTableManager
    private lateinit var projectedReadingManager: ProjectedReadingManager
    private lateinit var historyManager: HistoryManager

    private var isTorchOn = false

    companion object {
        private const val PREFS_NAME = "MeterSnapPrefs"
        private const val PREF_PREVIOUS_READING = "previous_reading"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_manual_input)

        ratePreferences = RatePreferences(this)
        rateTableManager = RateTableManager(this)
        projectedReadingManager = ProjectedReadingManager(this)
        historyManager = HistoryManager(this)
        
        initializeViews()
        setupToolbar()
        setupWindowInsets()
        setupListeners()
        
        // Auto-populate previous reading from saved preferences
        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val previousReading = prefs.getInt("previous_reading", -1)
        if (previousReading != -1) {
            editPreviousReading.setText(previousReading.toString())
        }

        // Pre-fill current reading if the snap camera detected (or partially detected) a value.
        // This avoids the user having to retype a number the camera already read.
        val snapReading = intent.getStringExtra("SNAP_READING")
        if (!snapReading.isNullOrBlank()) {
            editCurrentReading.setText(snapReading)
            // Move cursor to end so the user can quickly correct/confirm
            editCurrentReading.setSelection(snapReading.length)
            Toast.makeText(
                this,
                "Snap reading pre-filled: $snapReading - please verify before calculating.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    private fun setupWindowInsets() {
        // Apply status bar inset to toolbar only
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

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        editCurrentReading = findViewById(R.id.edit_current_reading)
        editPreviousReading = findViewById(R.id.edit_previous_reading)
        btnCalculate = findViewById(R.id.btn_calculate)
        btnTorchManual = findViewById(R.id.btn_torch_manual)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "Manual Input"
    }

    private fun setupListeners() {
        btnCalculate.setOnClickListener {
            calculateBill()
        }
        btnTorchManual.setOnClickListener {
            toggleTorch()
        }
    }

    // â”€â”€ Torch helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun flashCameraId(): String? {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    private fun toggleTorch() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = flashCameraId()
        if (cameraId == null) {
            Toast.makeText(this, "Torch not available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            isTorchOn = !isTorchOn
            cm.setTorchMode(cameraId, isTorchOn)
            btnTorchManual.setImageResource(
                if (isTorchOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off
            )
        } catch (e: Exception) {
            isTorchOn = false
            Toast.makeText(this, "Could not toggle torch", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureTorchOff() {
        if (!isTorchOn) return
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            flashCameraId()?.let { cm.setTorchMode(it, false) }
        } catch (_: Exception) {}
        isTorchOn = false
    }

    override fun onStop() {
        super.onStop()
        ensureTorchOff()
    }

    override fun onDestroy() {
        super.onDestroy()
        ensureTorchOff()
    }

    private fun calculateBill() {
        val currentReadingText = editCurrentReading.text.toString()
        val previousReadingText = editPreviousReading.text.toString()

        // Validate inputs
        if (currentReadingText.isEmpty() || previousReadingText.isEmpty()) {
            Toast.makeText(this, "Please enter both readings", Toast.LENGTH_SHORT).show()
            return
        }

        val currentReading = currentReadingText.toIntOrNull()
        val previousReading = previousReadingText.toIntOrNull()

        if (currentReading == null || previousReading == null) {
            Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentReading < previousReading) {
            // Meter rollover: digits physically wrap from 99999 â†’ 00000
            if (previousReading > 80000 && currentReading < 20000) {
                val rolloverUsage = (99999 - previousReading) + currentReading + 1
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Meter Rolled Over?")
                    .setMessage(
                        "The current reading ($currentReading) is lower than the previous reading ($previousReading).\n\n" +
                        "If your meter rolled over from 99999 back to 00000, the actual usage would be:\n" +
                        "$rolloverUsage m\u00B3\n\n" +
                        "Is that correct?"
                    )
                    .setPositiveButton("Yes, Meter Rolled Over") { _, _ ->
                        continueCalculation(currentReading, previousReading, rolloverUsage)
                    }
                    .setNegativeButton("No, Re-enter") { dialog, _ -> dialog.dismiss() }
                    .show()
                return
            }
            Toast.makeText(this, "Current reading must be greater than previous reading", Toast.LENGTH_SHORT).show()
            return
        }

        // Calculate usage
        val usage = currentReading - previousReading

        // 0 usage is valid (property vacant, duplicate read) - minimum charge still applies

        // â”€â”€ Impossible / anomalous usage check â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Manual entry is intentional, so we warn but always allow proceeding.
        val predictor = com.kokeshi.metersnap.utils.ReadingPredictor(this)
        val (dynamicThreshold, hardCap) = predictor.getUsagePlausibilityThresholds()

        val isHardImpossible = usage > hardCap
        val isDynamicAnomaly = dynamicThreshold != null && usage > dynamicThreshold && !isHardImpossible

        if (isHardImpossible || isDynamicAnomaly) {
            val title = if (isHardImpossible) "Unusually High Reading" else "Usage Looks High"
            val message = if (isHardImpossible) {
                "Usage of $usage m\u00B3 in one billing period is beyond a realistic residential amount.\n\n" +
                "Double-check your readings before proceeding."
            } else {
                val avgMonthly = dynamicThreshold!! / 3
                "Usage of $usage m\u00B3 is more than 3\u00D7 your usual ~$avgMonthly m\u00B3/month.\n\n" +
                "Possible typo, or unusual usage (leak, extended family visit, etc.)?\n\n" +
                "You can still proceed - this is just a heads-up."
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Proceed Anyway") { _, _ ->
                    continueCalculation(currentReading, previousReading, usage)
                }
                .setNegativeButton("Re-enter") { dialog, _ -> dialog.dismiss() }
                .show()
            return
        }

        continueCalculation(currentReading, previousReading, usage)
    }

    /**
     * Continues bill calculation after usage validation passes (or user confirms a warning).
     */
    private fun continueCalculation(currentReading: Int, previousReading: Int, usage: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPreviousReading = prefs.getInt(PREF_PREVIOUS_READING, -1)
        val hasSavedBaseline = savedPreviousReading >= 0
        val latestSavedCurrentReading = historyManager.getLatestEntry()?.currentReading
        val isPossibleMeterRollover =
            latestSavedCurrentReading != null && latestSavedCurrentReading > 80000 && currentReading < 20000

        if (latestSavedCurrentReading != null
            && currentReading < latestSavedCurrentReading
            && !isPossibleMeterRollover
        ) {
            showCurrentReadingRegressionDialog(currentReading, latestSavedCurrentReading)
            return
        }

        if (hasSavedBaseline && previousReading != savedPreviousReading) {
            showPreviousReadingChangeOptions(
                newPreviousReading = previousReading,
                currentReading = currentReading,
                rateType = ratePreferences.getRateType(),
                latestSavedCurrentReading = latestSavedCurrentReading
            )
            return
        }

        proceedWithCalculation(
            currentReading = currentReading,
            previousReading = previousReading,
            rateType = ratePreferences.getRateType(),
            calculatorMode = false,
            usageOverride = usage
        )
    }

    private fun showCurrentReadingRegressionDialog(currentReading: Int, latestSavedReading: Int) {
        AlertDialog.Builder(this)
            .setTitle("Inconsistencies Detected")
            .setMessage(
                "The new reading ($currentReading) is lower than your latest saved reading ($latestSavedReading).\n\n" +
                    "Water usage should not go backward within the same session.\n\n" +
                    "If this belongs to an older meter session, start a new session first. " +
                    "Otherwise, use Temporary Calculator so history and projections stay unchanged."
            )
            .setPositiveButton("Start New Session") { _, _ ->
                startActivity(Intent(this, HistorySettingsActivity::class.java))
            }
            .setNeutralButton("Temporary Calculator") { _, _ ->
                proceedWithCalculation(
                    currentReading = currentReading,
                    previousReading = editPreviousReading.text.toString().toIntOrNull() ?: return@setNeutralButton,
                    rateType = ratePreferences.getRateType(),
                    calculatorMode = true
                )
            }
            .setNegativeButton("Re-enter", null)
            .show()
    }

    private fun showPreviousReadingChangeOptions(
        newPreviousReading: Int,
        currentReading: Int,
        rateType: WaterRateType,
        latestSavedCurrentReading: Int?
    ) {
        val isBackwardInconsistency =
            latestSavedCurrentReading != null && newPreviousReading < latestSavedCurrentReading
        val title = if (isBackwardInconsistency) "Inconsistencies Detected" else "Previous Reading Changed"
        val message = if (isBackwardInconsistency) {
            "The Previous Reading you entered ($newPreviousReading) is lower than your latest saved reading ($latestSavedCurrentReading).\n\n" +
                "This usually means the app is looking at an older meter session. Start a new session to reset history + projections, or use Temporary Calculator if you only need a one-off estimate.\n\n" +
                "You can still review older sessions in:\nSettings > History and Readings > Session Logs"
        } else {
            "You entered a different Previous Reading.\n\n" +
                "Choose how to continue:\n" +
                "\u2022 Start new meter session: clears history + projections and uses this as your new baseline\n" +
                "\u2022 Temporary calculator mode: use this only for this calculation\n\n" +
                "You can view previous sessions in:\n" +
                "Settings > History and Readings > Session Logs"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Start New Session") { _, _ ->
                // Second confirmation - resetting deletes ALL history and projections.
                // One accidental tap on the primary button should not be destructive.
                AlertDialog.Builder(this)
                    .setTitle("Delete All History?")
                    .setMessage(
                        "This will permanently clear all your scan history and projections.\n\n" +
                        "Your previous session will be archived to Session Logs, but the detailed " +
                        "history used for bill projections will be gone.\n\n" +
                        "Are you sure?"
                    )
                    .setPositiveButton("Yes, Reset") { _, _ ->
                        SessionResetManager.resetForNewMeterSession(this, newPreviousReading)
                        Toast.makeText(this, "New meter session started. Previous session archived to Session Logs", Toast.LENGTH_LONG).show()
                        proceedWithCalculation(
                            currentReading = currentReading,
                            previousReading = newPreviousReading,
                            rateType = rateType,
                            calculatorMode = false
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNeutralButton("Temporary Calculator") { _, _ ->
                val toastMessage = if (isBackwardInconsistency) {
                    "Temporary calculator mode only. History and projections were left unchanged."
                } else {
                    "Temporary calculator mode. Session Logs remain available from Settings > History and Readings > Session Logs"
                }
                Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()
                proceedWithCalculation(
                    currentReading = currentReading,
                    previousReading = newPreviousReading,
                    rateType = rateType,
                    calculatorMode = true
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun proceedWithCalculation(
        currentReading: Int,
        previousReading: Int,
        rateType: WaterRateType,
        calculatorMode: Boolean,
        usageOverride: Int? = null
    ) {
        val usage = usageOverride ?: (currentReading - previousReading)

        val customRates = ratePreferences.getCustomRates()
        
        // Calculate bill using dynamic method if not custom
        val billResult: BillResult = if (rateType == WaterRateType.CUSTOM) {
            // Custom uses legacy calculation with saved custom rates
            val esfRate = ratePreferences.getESFRate()
            BillCalculator.calculateBill(
                usage = usage,
                rateType = rateType,
                customRates = customRates,
                esfRate = esfRate
            )
        } else {
            // Use dynamic calculation from RateTableManager
            val serviceType = rateTableManager.getServiceType(rateType.name)
            if (serviceType != null) {
                BillCalculator.calculateBillDynamic(
                    usage = usage,
                    serviceType = serviceType,
                    esfRate = rateTableManager.getEsfRate()
                )
            } else {
                // Fallback to legacy if service type not found
                val esfRate = WaterRates.getESFForType(rateType)
                BillCalculator.calculateBill(
                    usage = usage,
                    rateType = rateType,
                    customRates = customRates,
                    esfRate = esfRate
                )
            }
        }
        
        // DON'T save to history here - let the destination activity handle it
        // This prevents duplicate entries

        val isForcedDualBillingEnabled = DeveloperOptions.isForceDualBillingEnabled(this)
        val appPredictedPreviousReading = projectedReadingManager.getAppPredictedPreviousReading()
        val comparisonPreviousReading = if (isForcedDualBillingEnabled) {
            DeveloperOptions.buildForcedAppPreviousReading(currentReading, previousReading)
                ?: appPredictedPreviousReading?.takeIf { it != previousReading }
        } else {
            appPredictedPreviousReading?.takeIf { it != previousReading }
        }

        if (comparisonPreviousReading != null) {
            if (isForcedDualBillingEnabled) {
                Toast.makeText(
                    this,
                    "Developer preview: forced dual billing will not be saved to history",
                    Toast.LENGTH_LONG
                ).show()
            }
            navigateToBillComparison(
                currentReading = currentReading,
                previousReading = previousReading,
                rateType = rateType,
                calculatorMode = calculatorMode,
                devForcedComparison = isForcedDualBillingEnabled,
                comparisonPreviousReading = comparisonPreviousReading
            )
        } else {
            if (isForcedDualBillingEnabled) {
                Toast.makeText(
                    this,
                    "Developer preview could not create a valid discrepancy for these readings",
                    Toast.LENGTH_LONG
                ).show()
            }
            // Navigate to results screen (will save to history on exit)
            navigateToBillResult(currentReading, previousReading, rateType, billResult, calculatorMode)
        }
    }
    
    /**
     * Navigate to bill comparison activity (dual view)
     */
    private fun navigateToBillComparison(
        currentReading: Int,
        previousReading: Int,
        rateType: WaterRateType,
        calculatorMode: Boolean,
        devForcedComparison: Boolean,
        comparisonPreviousReading: Int
    ) {
        val intent = Intent(this, BillComparisonActivity::class.java).apply {
            putExtra("CURRENT_READING", currentReading)
            putExtra("PREVIOUS_READING", previousReading)
            putExtra("RATE_TYPE", rateType.name)
            putExtra("CALCULATOR_MODE", calculatorMode)
            putExtra(DeveloperOptions.EXTRA_DEV_FORCED_COMPARISON, devForcedComparison)
            putExtra(DeveloperOptions.EXTRA_FORCED_APP_PREVIOUS_READING, comparisonPreviousReading)
        }
        startActivity(intent)
    }
    
    /**
     * Navigate to standard bill result activity
     */
    private fun navigateToBillResult(
        currentReading: Int,
        previousReading: Int,
        rateType: WaterRateType,
        billResult: BillResult,
        calculatorMode: Boolean
    ) {
        val usage = currentReading - previousReading
        
        // Navigate to results screen
        val intent = Intent(this, BillResultActivity::class.java).apply {
            putExtra("USAGE", usage)
            putExtra("CURRENT_READING", currentReading)
            putExtra("PREVIOUS_READING", previousReading)
            putExtra("RATE_TYPE", rateType.name)
            putExtra("WATER_BILL", billResult.waterBill)
            putExtra("ESF_FEE", billResult.esfFee)
            putExtra("TOTAL_BILL", billResult.totalBill)
            putExtra("TIER_1_USAGE", billResult.tier1Usage)
            putExtra("TIER_1_AMOUNT", billResult.tier1Amount)
            putExtra("TIER_2_USAGE", billResult.tier2Usage)
            putExtra("TIER_2_AMOUNT", billResult.tier2Amount)
            putExtra("TIER_3_USAGE", billResult.tier3Usage)
            putExtra("TIER_3_AMOUNT", billResult.tier3Amount)
            putExtra("TIER_4_USAGE", billResult.tier4Usage)
            putExtra("TIER_4_AMOUNT", billResult.tier4Amount)
            putExtra("TIER_5_USAGE", billResult.tier5Usage)
            putExtra("TIER_5_AMOUNT", billResult.tier5Amount)
            putExtra("CALCULATOR_MODE", calculatorMode)
        }
        startActivity(intent)
    }
    
    // saveToHistory removed - now handled by destination activities

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
