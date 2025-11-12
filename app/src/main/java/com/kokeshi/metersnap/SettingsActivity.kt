package com.kokeshi.metersnap

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import com.kokeshi.metersnap.data.TierRate
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.data.WaterRates
import com.kokeshi.metersnap.utils.RatePreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var spinnerRateType: Spinner
    private lateinit var textESFRate: TextView
    private lateinit var btnEditESF: Button
    private lateinit var containerRateTiers: LinearLayout
    private lateinit var btnEditCustomRates: Button
    private lateinit var btnSaveSettings: Button
    private lateinit var textRatesSubtitle: TextView

    private lateinit var ratePreferences: RatePreferences
    private var customRates: MutableList<TierRate> = mutableListOf()
    private var currentESFRate: Double = 3.50
    
    private val rateTypes = listOf(
        "Residential",
        "Commercial/Industrial", 
        "Government",
        "Semi-Commercial A",
        "Semi-Commercial B",
        "Semi-Commercial C",
        "Custom Settings"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Enable edge-to-edge layout and handle window insets (modern approach)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        ratePreferences = RatePreferences(this)

        initializeViews()
        setupToolbar()
        setupWindowInsets()
        loadSavedSettings()
        setupListeners()
    }

    private fun setupWindowInsets() {
        // Handle keyboard and system bar insets for the root view
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply padding to avoid keyboard covering content
            view.setPadding(
                systemBarsInsets.left,
                0, // Top padding handled by toolbar
                systemBarsInsets.right,
                imeInsets.bottom.coerceAtLeast(systemBarsInsets.bottom)
            )

            windowInsets
        }
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        spinnerRateType = findViewById(R.id.spinner_rate_type)
        textESFRate = findViewById(R.id.text_esf_rate)
        btnEditESF = findViewById(R.id.btn_edit_esf)
        containerRateTiers = findViewById(R.id.container_rate_tiers)
        btnEditCustomRates = findViewById(R.id.btn_edit_custom_rates)
        btnSaveSettings = findViewById(R.id.btn_save_settings)
        textRatesSubtitle = findViewById(R.id.text_rates_subtitle)
        
        // Setup spinner adapter
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, rateTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerRateType.adapter = adapter
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        
        // Apply status bar insets to toolbar using modern WindowInsets API
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, windowInsets ->
            val statusBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Add extra padding for better spacing
            view.setPadding(
                view.paddingLeft, 
                statusBarInsets.top, 
                view.paddingRight, 
                view.paddingBottom
            )
            windowInsets
        }
    }

    private fun loadSavedSettings() {
        // Load rate type
        val savedType = ratePreferences.getRateType()
        val position = when (savedType) {
            WaterRateType.RESIDENTIAL -> 0
            WaterRateType.COMMERCIAL -> 1
            WaterRateType.GOVERNMENT -> 2
            WaterRateType.SEMI_COMMERCIAL_A -> 3
            WaterRateType.SEMI_COMMERCIAL_B -> 4
            WaterRateType.SEMI_COMMERCIAL_C -> 5
            WaterRateType.CUSTOM -> 6
        }
        
        spinnerRateType.setSelection(position)
        
        // Load appropriate settings based on type
        when (savedType) {
            WaterRateType.RESIDENTIAL -> {
                currentESFRate = WaterRates.RESIDENTIAL_ESF
                btnEditESF.visibility = View.GONE
                textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
            }
            WaterRateType.COMMERCIAL -> {
                currentESFRate = WaterRates.COMMERCIAL_ESF
                btnEditESF.visibility = View.GONE
                textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
            }
            WaterRateType.GOVERNMENT -> {
                currentESFRate = WaterRates.GOVERNMENT_ESF
                btnEditESF.visibility = View.GONE
                textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
            }
            WaterRateType.SEMI_COMMERCIAL_A -> {
                currentESFRate = WaterRates.SEMI_COMMERCIAL_A_ESF
                btnEditESF.visibility = View.GONE
                textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
            }
            WaterRateType.SEMI_COMMERCIAL_B -> {
                currentESFRate = WaterRates.SEMI_COMMERCIAL_B_ESF
                btnEditESF.visibility = View.GONE
                textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
            }
            WaterRateType.SEMI_COMMERCIAL_C -> {
                currentESFRate = WaterRates.SEMI_COMMERCIAL_C_ESF
                btnEditESF.visibility = View.GONE
                textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
            }
            WaterRateType.CUSTOM -> {
                btnEditESF.visibility = View.VISIBLE
                btnEditCustomRates.visibility = View.VISIBLE
                customRates = ratePreferences.getCustomRates().toMutableList()
                if (customRates.isEmpty()) {
                    // Initialize with default residential rates
                    customRates = WaterRates.RESIDENTIAL_RATES.toMutableList()
                }
                // Load saved custom ESF rate
                currentESFRate = ratePreferences.getESFRate()
                textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
            }
        }

        updateRateTiersDisplay()
    }

    private fun setupListeners() {
        spinnerRateType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> { // Residential
                        btnEditCustomRates.visibility = View.GONE
                        btnEditESF.visibility = View.GONE
                        textRatesSubtitle.text = getString(R.string.residential_government_rates_jan_2025)
                        currentESFRate = WaterRates.RESIDENTIAL_ESF
                        textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
                        updateRateTiersDisplay()
                    }
                    1 -> { // Commercial
                        btnEditCustomRates.visibility = View.GONE
                        btnEditESF.visibility = View.GONE
                        textRatesSubtitle.text = getString(R.string.commercial_industrial_rates_jan_2025)
                        currentESFRate = WaterRates.COMMERCIAL_ESF
                        textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
                        updateRateTiersDisplay()
                    }
                    2 -> { // Government
                        btnEditCustomRates.visibility = View.GONE
                        btnEditESF.visibility = View.GONE
                        textRatesSubtitle.text = getString(R.string.residential_government_rates_jan_2025)
                        currentESFRate = WaterRates.GOVERNMENT_ESF
                        textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
                        updateRateTiersDisplay()
                    }
                    3 -> { // Semi-Commercial A
                        btnEditCustomRates.visibility = View.GONE
                        btnEditESF.visibility = View.GONE
                        textRatesSubtitle.text = getString(R.string.semi_commercial_a_rates_jan_2025)
                        currentESFRate = WaterRates.SEMI_COMMERCIAL_A_ESF
                        textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
                        updateRateTiersDisplay()
                    }
                    4 -> { // Semi-Commercial B
                        btnEditCustomRates.visibility = View.GONE
                        btnEditESF.visibility = View.GONE
                        textRatesSubtitle.text = getString(R.string.semi_commercial_b_rates_jan_2025)
                        currentESFRate = WaterRates.SEMI_COMMERCIAL_B_ESF
                        textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
                        updateRateTiersDisplay()
                    }
                    5 -> { // Semi-Commercial C
                        btnEditCustomRates.visibility = View.GONE
                        btnEditESF.visibility = View.GONE
                        textRatesSubtitle.text = getString(R.string.semi_commercial_c_rates_jan_2025)
                        currentESFRate = WaterRates.SEMI_COMMERCIAL_C_ESF
                        textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
                        updateRateTiersDisplay()
                    }
                    6 -> { // Custom
                        btnEditCustomRates.visibility = View.VISIBLE
                        btnEditESF.visibility = View.VISIBLE
                        textRatesSubtitle.text = getString(R.string.custom_settings_editable)
                        // Load saved custom rates and ESF
                        customRates = ratePreferences.getCustomRates().toMutableList()
                        if (customRates.isEmpty()) {
                            customRates = WaterRates.RESIDENTIAL_RATES.toMutableList()
                        }
                        // Load saved custom ESF rate
                        currentESFRate = ratePreferences.getESFRate()
                        textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
                        updateRateTiersDisplay()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }

        btnEditCustomRates.setOnClickListener {
            showCustomRatesDialog()
        }

        btnEditESF.setOnClickListener {
            showESFDialog()
        }

        btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }

    private fun updateRateTiersDisplay() {
        containerRateTiers.removeAllViews()

        val rates = when (spinnerRateType.selectedItemPosition) {
            0 -> WaterRates.RESIDENTIAL_RATES
            1 -> WaterRates.COMMERCIAL_RATES
            2 -> WaterRates.GOVERNMENT_RATES
            3 -> WaterRates.SEMI_COMMERCIAL_A_RATES
            4 -> WaterRates.SEMI_COMMERCIAL_B_RATES
            5 -> WaterRates.SEMI_COMMERCIAL_C_RATES
            6 -> customRates
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

        // Enable keyboard to resize dialog content instead of covering it (modern approach)
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ViewCompat.setOnApplyWindowInsetsListener(dialogView) { view, windowInsets ->
                val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, imeInsets.bottom)
                windowInsets
            }
        }

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

    private fun showESFDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_esf, null)

        val editESF = dialogView.findViewById<TextInputEditText>(R.id.edit_esf_dialog)

        // Pre-fill with current ESF rate
        editESF.setText(String.format(Locale.US, "%.2f", currentESFRate))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Enable keyboard to resize dialog content instead of covering it (modern approach)
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ViewCompat.setOnApplyWindowInsetsListener(dialogView) { view, windowInsets ->
                val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, imeInsets.bottom)
                windowInsets
            }
        }

        dialogView.findViewById<Button>(R.id.btn_cancel_esf).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btn_save_esf).setOnClickListener {
            try {
                val esfText = editESF.text.toString()
                val esfRate = esfText.toDoubleOrNull()

                if (esfRate == null || esfRate < 0) {
                    Toast.makeText(this, "Please enter a valid ESF rate", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Update current ESF rate and display
                currentESFRate = esfRate
                textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)

                // Immediately save to preferences
                ratePreferences.saveESFRate(currentESFRate)

                dialog.dismiss()
                Toast.makeText(this, "ESF rate updated", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error updating ESF rate: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun saveSettings() {
        try {
            // Save rate type
            val rateType = when (spinnerRateType.selectedItemPosition) {
                0 -> WaterRateType.RESIDENTIAL
                1 -> WaterRateType.COMMERCIAL
                2 -> WaterRateType.GOVERNMENT
                3 -> WaterRateType.SEMI_COMMERCIAL_A
                4 -> WaterRateType.SEMI_COMMERCIAL_B
                5 -> WaterRateType.SEMI_COMMERCIAL_C
                6 -> WaterRateType.CUSTOM
                else -> WaterRateType.RESIDENTIAL
            }
            ratePreferences.saveRateType(rateType)

            // Only save ESF rate for custom settings
            if (rateType == WaterRateType.CUSTOM) {
                if (currentESFRate < 0) {
                    Toast.makeText(this, "Please enter a valid ESF rate", Toast.LENGTH_SHORT).show()
                    return
                }
                ratePreferences.saveESFRate(currentESFRate)
                
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

