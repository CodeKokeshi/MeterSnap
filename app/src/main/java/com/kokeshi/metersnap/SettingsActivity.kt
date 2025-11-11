package com.kokeshi.metersnap

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import com.kokeshi.metersnap.data.TierRate
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.data.WaterRates
import com.kokeshi.metersnap.utils.RatePreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var radioGroupRateType: RadioGroup
    private lateinit var radioResidential: RadioButton
    private lateinit var radioCommercial: RadioButton
    private lateinit var radioGovernment: RadioButton
    private lateinit var radioSemiCommercialA: RadioButton
    private lateinit var radioSemiCommercialB: RadioButton
    private lateinit var radioSemiCommercialC: RadioButton
    private lateinit var radioCustom: RadioButton
    private lateinit var editESFRate: TextInputEditText
    private lateinit var containerRateTiers: LinearLayout
    private lateinit var btnEditCustomRates: Button
    private lateinit var btnSaveSettings: Button
    private lateinit var textRatesSubtitle: TextView

    private lateinit var ratePreferences: RatePreferences
    private var customRates: MutableList<TierRate> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        ratePreferences = RatePreferences(this)

        initializeViews()
        setupToolbar()
        loadSavedSettings()
        setupListeners()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        radioGroupRateType = findViewById(R.id.radio_group_rate_type)
        radioResidential = findViewById(R.id.radio_residential)
        radioCommercial = findViewById(R.id.radio_commercial)
        radioGovernment = findViewById(R.id.radio_government)
        radioSemiCommercialA = findViewById(R.id.radio_semi_commercial_a)
        radioSemiCommercialB = findViewById(R.id.radio_semi_commercial_b)
        radioSemiCommercialC = findViewById(R.id.radio_semi_commercial_c)
        radioCustom = findViewById(R.id.radio_custom)
        editESFRate = findViewById(R.id.edit_esf_rate)
        containerRateTiers = findViewById(R.id.container_rate_tiers)
        btnEditCustomRates = findViewById(R.id.btn_edit_custom_rates)
        btnSaveSettings = findViewById(R.id.btn_save_settings)
        textRatesSubtitle = findViewById(R.id.text_rates_subtitle)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    private fun loadSavedSettings() {
        // Load rate type
        val savedType = ratePreferences.getRateType()
        when (savedType) {
            WaterRateType.RESIDENTIAL -> {
                radioResidential.isChecked = true
                editESFRate.isEnabled = false
                editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.RESIDENTIAL_ESF))
            }
            WaterRateType.COMMERCIAL -> {
                radioCommercial.isChecked = true
                editESFRate.isEnabled = false
                editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.COMMERCIAL_ESF))
            }
            WaterRateType.GOVERNMENT -> {
                radioGovernment.isChecked = true
                editESFRate.isEnabled = false
                editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.GOVERNMENT_ESF))
            }
            WaterRateType.SEMI_COMMERCIAL_A -> {
                radioSemiCommercialA.isChecked = true
                editESFRate.isEnabled = false
                editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.SEMI_COMMERCIAL_A_ESF))
            }
            WaterRateType.SEMI_COMMERCIAL_B -> {
                radioSemiCommercialB.isChecked = true
                editESFRate.isEnabled = false
                editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.SEMI_COMMERCIAL_B_ESF))
            }
            WaterRateType.SEMI_COMMERCIAL_C -> {
                radioSemiCommercialC.isChecked = true
                editESFRate.isEnabled = false
                editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.SEMI_COMMERCIAL_C_ESF))
            }
            WaterRateType.CUSTOM -> {
                radioCustom.isChecked = true
                editESFRate.isEnabled = true
                btnEditCustomRates.visibility = View.VISIBLE
                customRates = ratePreferences.getCustomRates().toMutableList()
                if (customRates.isEmpty()) {
                    // Initialize with default residential rates
                    customRates = WaterRates.RESIDENTIAL_RATES.toMutableList()
                }
                // Load saved custom ESF rate
                val customESF = ratePreferences.getESFRate()
                editESFRate.setText(String.format(Locale.US, "%.2f", customESF))
            }
        }

        updateRateTiersDisplay()
    }

    private fun setupListeners() {
        radioGroupRateType.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_residential -> {
                    btnEditCustomRates.visibility = View.GONE
                    editESFRate.isEnabled = false
                    textRatesSubtitle.text = "Residential/Government Rates - Jan 2025"
                    editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.RESIDENTIAL_ESF))
                    updateRateTiersDisplay()
                }
                R.id.radio_commercial -> {
                    btnEditCustomRates.visibility = View.GONE
                    editESFRate.isEnabled = false
                    textRatesSubtitle.text = "Commercial/Industrial Rates - Jan 2025"
                    editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.COMMERCIAL_ESF))
                    updateRateTiersDisplay()
                }
                R.id.radio_government -> {
                    btnEditCustomRates.visibility = View.GONE
                    editESFRate.isEnabled = false
                    textRatesSubtitle.text = "Residential/Government Rates - Jan 2025"
                    editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.GOVERNMENT_ESF))
                    updateRateTiersDisplay()
                }
                R.id.radio_semi_commercial_a -> {
                    btnEditCustomRates.visibility = View.GONE
                    editESFRate.isEnabled = false
                    textRatesSubtitle.text = "Semi-Commercial A Rates - Jan 2025"
                    editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.SEMI_COMMERCIAL_A_ESF))
                    updateRateTiersDisplay()
                }
                R.id.radio_semi_commercial_b -> {
                    btnEditCustomRates.visibility = View.GONE
                    editESFRate.isEnabled = false
                    textRatesSubtitle.text = "Semi-Commercial B Rates - Jan 2025"
                    editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.SEMI_COMMERCIAL_B_ESF))
                    updateRateTiersDisplay()
                }
                R.id.radio_semi_commercial_c -> {
                    btnEditCustomRates.visibility = View.GONE
                    editESFRate.isEnabled = false
                    textRatesSubtitle.text = "Semi-Commercial C Rates - Jan 2025"
                    editESFRate.setText(String.format(Locale.US, "%.2f", WaterRates.SEMI_COMMERCIAL_C_ESF))
                    updateRateTiersDisplay()
                }
                R.id.radio_custom -> {
                    btnEditCustomRates.visibility = View.VISIBLE
                    editESFRate.isEnabled = true
                    textRatesSubtitle.text = "Your custom settings (editable)"
                    // Load saved custom rates and ESF
                    customRates = ratePreferences.getCustomRates().toMutableList()
                    if (customRates.isEmpty()) {
                        customRates = WaterRates.RESIDENTIAL_RATES.toMutableList()
                    }
                    // Load saved custom ESF rate
                    val customESF = ratePreferences.getESFRate()
                    editESFRate.setText(String.format(Locale.US, "%.2f", customESF))
                    updateRateTiersDisplay()
                }
            }
        }

        btnEditCustomRates.setOnClickListener {
            showCustomRatesDialog()
        }

        btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }

    private fun updateRateTiersDisplay() {
        containerRateTiers.removeAllViews()

        val rates = when {
            radioResidential.isChecked -> WaterRates.RESIDENTIAL_RATES
            radioCommercial.isChecked -> WaterRates.COMMERCIAL_RATES
            radioGovernment.isChecked -> WaterRates.GOVERNMENT_RATES
            radioSemiCommercialA.isChecked -> WaterRates.SEMI_COMMERCIAL_A_RATES
            radioSemiCommercialB.isChecked -> WaterRates.SEMI_COMMERCIAL_B_RATES
            radioSemiCommercialC.isChecked -> WaterRates.SEMI_COMMERCIAL_C_RATES
            radioCustom.isChecked -> customRates
            else -> WaterRates.RESIDENTIAL_RATES
        }

        rates.forEach { tier ->
            val tierView = createTierView(tier)
            containerRateTiers.addView(tierView)
        }
    }

    private fun createTierView(tier: TierRate): View {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.item_rate_tier, containerRateTiers, false)

        val textRange = view.findViewById<TextView>(R.id.text_tier_range)
        val textRate = view.findViewById<TextView>(R.id.text_tier_rate)

        val rangeText = if (tier.maxCubicMeters == Int.MAX_VALUE) {
            "${tier.minCubicMeters}+ m³"
        } else {
            "${tier.minCubicMeters}-${tier.maxCubicMeters} m³"
        }

        textRange.text = rangeText
        
        // First tier (1-10) is a minimum charge, not per m³
        val rateText = if (tier.minCubicMeters == 1 && tier.maxCubicMeters == 10) {
            String.format(Locale.US, "₱%.2f (minimum charge)", tier.ratePerCubicMeter)
        } else {
            String.format(Locale.US, "₱%.2f per m³", tier.ratePerCubicMeter)
        }
        textRate.text = rateText

        return view
    }

    private fun showCustomRatesDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_custom_rates, null)

        val editTier1 = dialogView.findViewById<TextInputEditText>(R.id.edit_tier1_rate)
        val editTier2 = dialogView.findViewById<TextInputEditText>(R.id.edit_tier2_rate)
        val editTier3 = dialogView.findViewById<TextInputEditText>(R.id.edit_tier3_rate)
        val editTier4 = dialogView.findViewById<TextInputEditText>(R.id.edit_tier4_rate)
        val editTier5 = dialogView.findViewById<TextInputEditText>(R.id.edit_tier5_rate)

        // Pre-fill with current custom rates
        if (customRates.size >= 5) {
            editTier1.setText(String.format(Locale.US, "%.2f", customRates[0].ratePerCubicMeter))
            editTier2.setText(String.format(Locale.US, "%.2f", customRates[1].ratePerCubicMeter))
            editTier3.setText(String.format(Locale.US, "%.2f", customRates[2].ratePerCubicMeter))
            editTier4.setText(String.format(Locale.US, "%.2f", customRates[3].ratePerCubicMeter))
            editTier5.setText(String.format(Locale.US, "%.2f", customRates[4].ratePerCubicMeter))
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Enable keyboard to resize dialog content instead of covering it
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        dialogView.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btn_save).setOnClickListener {
            try {
                val rate1 = editTier1.text.toString().toDoubleOrNull()
                val rate2 = editTier2.text.toString().toDoubleOrNull()
                val rate3 = editTier3.text.toString().toDoubleOrNull()
                val rate4 = editTier4.text.toString().toDoubleOrNull()
                val rate5 = editTier5.text.toString().toDoubleOrNull()

                if (rate1 == null || rate2 == null || rate3 == null || rate4 == null || rate5 == null) {
                    Toast.makeText(this, "Please fill all rate fields with valid numbers", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                customRates.clear()
                customRates.add(TierRate(1, 10, rate1))
                customRates.add(TierRate(11, 20, rate2))
                customRates.add(TierRate(21, 30, rate3))
                customRates.add(TierRate(31, 40, rate4))
                customRates.add(TierRate(41, Int.MAX_VALUE, rate5))

                // Immediately save custom settings to persist across app closures
                ratePreferences.saveCustomRates(customRates)
                
                updateRateTiersDisplay()
                dialog.dismiss()
                Toast.makeText(this, "Custom settings saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error saving custom settings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun saveSettings() {
        try {
            // Save rate type
            val rateType = when {
                radioResidential.isChecked -> WaterRateType.RESIDENTIAL
                radioCommercial.isChecked -> WaterRateType.COMMERCIAL
                radioGovernment.isChecked -> WaterRateType.GOVERNMENT
                radioSemiCommercialA.isChecked -> WaterRateType.SEMI_COMMERCIAL_A
                radioSemiCommercialB.isChecked -> WaterRateType.SEMI_COMMERCIAL_B
                radioSemiCommercialC.isChecked -> WaterRateType.SEMI_COMMERCIAL_C
                radioCustom.isChecked -> WaterRateType.CUSTOM
                else -> WaterRateType.RESIDENTIAL
            }
            ratePreferences.saveRateType(rateType)

            // Only save ESF rate for custom settings
            if (rateType == WaterRateType.CUSTOM) {
                val esfRateText = editESFRate.text.toString()
                val esfRate = esfRateText.toDoubleOrNull()
                if (esfRate == null || esfRate < 0) {
                    Toast.makeText(this, "Please enter a valid ESF rate", Toast.LENGTH_SHORT).show()
                    return
                }
                ratePreferences.saveESFRate(esfRate)
                
                // Save custom rates
                if (customRates.isNotEmpty()) {
                    ratePreferences.saveCustomRates(customRates)
                }
            }

            Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

