package com.kokeshi.metersnap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.textfield.TextInputEditText
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.data.WaterRates
import com.kokeshi.metersnap.utils.BillCalculator
import com.kokeshi.metersnap.utils.RatePreferences

class ManualInputActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var editCurrentReading: TextInputEditText
    private lateinit var editPreviousReading: TextInputEditText
    private lateinit var btnCalculate: Button
    private lateinit var ratePreferences: RatePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_input)

        ratePreferences = RatePreferences(this)
        
        initializeViews()
        setupToolbar()
        setupListeners()
        
        // Auto-populate previous reading
        val prefs = getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        val previousReading = prefs.getInt("previous_reading", -1)
        if (previousReading != -1) {
            editPreviousReading.setText(previousReading.toString())
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

        // Get current rate settings
        val rateType = ratePreferences.getRateType()
        val customRates = ratePreferences.getCustomRates()
        
        // Get ESF rate based on rate type
        // For custom settings, use saved custom ESF rate
        // For preset types, use the preset's ESF rate
        val esfRate = if (rateType == WaterRateType.CUSTOM) {
            ratePreferences.getESFRate()
        } else {
            WaterRates.getESFForType(rateType)
        }

        // Calculate bill
        val billResult = BillCalculator.calculateBill(
            usage = usage,
            rateType = rateType,
            customRates = customRates,
            esfRate = esfRate
        )

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
        }
        startActivity(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
