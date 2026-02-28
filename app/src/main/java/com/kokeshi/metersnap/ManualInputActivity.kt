package com.kokeshi.metersnap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
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
import com.kokeshi.metersnap.utils.ProjectedReadingManager
import com.kokeshi.metersnap.utils.SessionResetManager

class ManualInputActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var editCurrentReading: TextInputEditText
    private lateinit var editPreviousReading: TextInputEditText
    private lateinit var btnCalculate: Button
    private lateinit var ratePreferences: RatePreferences
    private lateinit var rateTableManager: RateTableManager
    private lateinit var projectedReadingManager: ProjectedReadingManager

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
        
        initializeViews()
        setupToolbar()
        setupWindowInsets()
        setupListeners()
        
        // Auto-populate previous reading
        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val previousReading = prefs.getInt("previous_reading", -1)
        if (previousReading != -1) {
            editPreviousReading.setText(previousReading.toString())
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
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "Manual Input"
        
        // Apply status bar insets to toolbar using modern WindowInsets API
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, windowInsets ->
            val statusBarInsets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft, 
                statusBarInsets.top, 
                view.paddingRight, 
                view.paddingBottom
            )
            windowInsets
        }
    }

    private fun setupListeners() {
        btnCalculate.setOnClickListener {
            calculateBill()
        }
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
            Toast.makeText(this, "Current reading must be greater than previous reading", Toast.LENGTH_SHORT).show()
            return
        }

        // Calculate usage
        val usage = currentReading - previousReading

        if (usage == 0) {
            Toast.makeText(this, "Usage cannot be zero", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPreviousReading = prefs.getInt(PREF_PREVIOUS_READING, -1)
        val hasSavedBaseline = savedPreviousReading >= 0

        if (hasSavedBaseline && previousReading != savedPreviousReading) {
            showPreviousReadingChangeOptions(
                newPreviousReading = previousReading,
                currentReading = currentReading,
                rateType = ratePreferences.getRateType()
            )
            return
        }

        proceedWithCalculation(
            currentReading = currentReading,
            previousReading = previousReading,
            rateType = ratePreferences.getRateType(),
            calculatorMode = false
        )
    }

    private fun showPreviousReadingChangeOptions(
        newPreviousReading: Int,
        currentReading: Int,
        rateType: WaterRateType
    ) {
        AlertDialog.Builder(this)
            .setTitle("Previous Reading Changed")
            .setMessage(
                "You entered a different Previous Reading.\n\n" +
                    "Choose how to continue:\n" +
                    "• Start new meter session: clears history + projections and uses this as your new baseline\n" +
                    "• Temporary calculator mode: use this only for this calculation\n\n" +
                    "You can view previous sessions in:\n" +
                    "Settings > History and Readings > Session Logs"
            )
            .setPositiveButton("Start New Session") { _, _ ->
                SessionResetManager.resetForNewMeterSession(this, newPreviousReading)
                Toast.makeText(this, "New meter session started. Previous session archived to Session Logs", Toast.LENGTH_LONG).show()
                proceedWithCalculation(
                    currentReading = currentReading,
                    previousReading = newPreviousReading,
                    rateType = rateType,
                    calculatorMode = false
                )
            }
            .setNeutralButton("Temporary Calculator") { _, _ ->
                Toast.makeText(this, "Temporary calculator mode. Session Logs remain available from Settings > History and Readings > Session Logs", Toast.LENGTH_LONG).show()
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
        calculatorMode: Boolean
    ) {
        val usage = currentReading - previousReading

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

        // Check if we have a predicted previous reading available
        val appPredictedPreviousReading = projectedReadingManager.getAppPredictedPreviousReading()
        
        if (appPredictedPreviousReading != null && appPredictedPreviousReading != previousReading) {
            // Auto-navigate to bill comparison (will save to history on exit)
            navigateToBillComparison(currentReading, previousReading, rateType, calculatorMode)
        } else {
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
        calculatorMode: Boolean
    ) {
        val intent = Intent(this, BillComparisonActivity::class.java).apply {
            putExtra("CURRENT_READING", currentReading)
            putExtra("PREVIOUS_READING", previousReading)
            putExtra("RATE_TYPE", rateType.name)
            putExtra("CALCULATOR_MODE", calculatorMode)
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