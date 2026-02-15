package com.kokeshi.metersnap

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import com.kokeshi.metersnap.data.RemoteServiceType
import com.kokeshi.metersnap.data.RemoteTier
import com.kokeshi.metersnap.data.TierRate
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.utils.RatePreferences
import com.kokeshi.metersnap.utils.RateTableManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity for configuring water rates.
 * Includes rate type selection, tier display, ESF editing, and save functionality.
 */
class RateConfigurationActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var spinnerRateType: Spinner
    private lateinit var btnUpdateRates: Button
    private lateinit var textLastUpdated: TextView
    private lateinit var textRatesSubtitle: TextView
    private lateinit var containerRateTiers: LinearLayout
    private lateinit var btnEditCustomRates: Button
    private lateinit var textESFRate: TextView
    private lateinit var btnEditESF: Button
    private lateinit var btnSaveSettings: Button

    private lateinit var ratePreferences: RatePreferences
    private lateinit var rateTableManager: RateTableManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Dynamic rate types from remote config
    private var dynamicServiceTypes: List<RemoteServiceType> = emptyList()
    private var dynamicRateTypes: List<String> = emptyList()

    // Custom rates storage
    private val customRates = mutableListOf<TierRate>()
    private var currentESFRate: Double = 3.50

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_rate_configuration)

        ratePreferences = RatePreferences(this)
        rateTableManager = RateTableManager(this)

        initializeViews()
        setupToolbar()
        setupWindowInsets()
        loadDynamicRateTypes()
        loadSavedSettings()
        setupListeners()
        updateLastFetchedDisplay()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        spinnerRateType = findViewById(R.id.spinner_rate_type)
        btnUpdateRates = findViewById(R.id.btn_update_rates)
        textLastUpdated = findViewById(R.id.text_last_updated)
        textRatesSubtitle = findViewById(R.id.text_rates_subtitle)
        containerRateTiers = findViewById(R.id.container_rate_tiers)
        btnEditCustomRates = findViewById(R.id.btn_edit_custom_rates)
        textESFRate = findViewById(R.id.text_esf_rate)
        btnEditESF = findViewById(R.id.btn_edit_esf)
        btnSaveSettings = findViewById(R.id.btn_save_settings)
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

    private fun loadDynamicRateTypes() {
        // Get config from cache or default
        val config = rateTableManager.getCachedConfig() ?: rateTableManager.getDefaultConfig()
        
        // Store the dynamic service types for later use
        dynamicServiceTypes = config.serviceTypes
        
        // Build list of display names + custom option
        val typeNames = dynamicServiceTypes.map { it.displayName }.toMutableList()
        typeNames.add("Custom Settings")
        dynamicRateTypes = typeNames
        
        // Update subtitle with config info
        textRatesSubtitle.text = config.tableLabel
        
        // Setup spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dynamicRateTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerRateType.adapter = adapter
    }

    private fun loadSavedSettings() {
        // Load current rate type
        val savedType = ratePreferences.getRateType()
        
        // Find position in dynamic list
        val position = when (savedType) {
            WaterRateType.CUSTOM -> dynamicRateTypes.size - 1
            else -> {
                val typeId = savedType.name
                dynamicServiceTypes.indexOfFirst { it.typeId == typeId }.takeIf { it >= 0 } ?: 0
            }
        }
        spinnerRateType.setSelection(position)

        // Load ESF rate
        currentESFRate = ratePreferences.getESFRate()
        textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)

        // Load custom rates if any
        val savedCustomRates = ratePreferences.getCustomRates()
        if (savedCustomRates.isNotEmpty()) {
            customRates.clear()
            customRates.addAll(savedCustomRates)
        } else {
            // Initialize with default custom tiers
            customRates.clear()
            customRates.add(TierRate(1, 10, 165.00))
            customRates.add(TierRate(11, 20, 22.00))
            customRates.add(TierRate(21, 30, 30.00))
            customRates.add(TierRate(31, 40, 55.00))
            customRates.add(TierRate(41, Int.MAX_VALUE, 80.00))
        }
    }

    private fun setupListeners() {
        spinnerRateType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isCustom = position == dynamicRateTypes.size - 1

                btnEditCustomRates.visibility = if (isCustom) View.VISIBLE else View.GONE
                btnEditESF.visibility = if (isCustom) View.VISIBLE else View.GONE

                updateRateTiersDisplay()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnEditCustomRates.setOnClickListener { showCustomRatesDialog() }
        btnEditESF.setOnClickListener { showESFDialog() }
        btnUpdateRates.setOnClickListener { showUpdateConfirmationDialog() }
        btnSaveSettings.setOnClickListener { saveSettings() }
    }

    private fun updateLastFetchedDisplay() {
        val lastFetchTime = rateTableManager.getLastFetchTime()
        val cachedVersion = rateTableManager.getCachedConfigVersion()
        
        if (lastFetchTime > 0) {
            val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
            val dateStr = dateFormat.format(Date(lastFetchTime))
            textLastUpdated.text = "v${String.format("%.1f", cachedVersion)} • Last checked: $dateStr"
        } else {
            textLastUpdated.text = "Using default rates v${String.format("%.1f", RateTableManager.DEFAULT_CONFIG_VERSION)}"
        }
    }

    private fun updateRateTiersDisplay() {
        containerRateTiers.removeAllViews()

        val position = spinnerRateType.selectedItemPosition
        val isCustom = position == dynamicRateTypes.size - 1

        if (isCustom) {
            // Custom rates use TierRate format
            customRates.forEach { tier ->
                val tierView = createTierView(tier)
                containerRateTiers.addView(tierView)
            }
        } else if (position < dynamicServiceTypes.size) {
            // Dynamic service type from remote/default config
            val serviceType = dynamicServiceTypes[position]
            serviceType.tiers.forEach { tier ->
                val tierView = createRemoteTierView(tier)
                containerRateTiers.addView(tierView)
            }
        }
    }

    private fun createRemoteTierView(tier: RemoteTier): View {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.item_rate_tier, containerRateTiers, false)

        val textRange = view.findViewById<TextView>(R.id.text_tier_range)
        val textRate = view.findViewById<TextView>(R.id.text_tier_rate)

        val rangeText = if (tier.max == null) {
            "${tier.min}+ m³"
        } else {
            "${tier.min}-${tier.max} m³"
        }
        textRange.text = rangeText

        val rateText = if (tier.isMinimumCharge) {
            String.format(Locale.US, "₱%.2f (minimum charge)", tier.rate)
        } else {
            String.format(Locale.US, "₱%.2f per m³", tier.rate)
        }
        textRate.text = rateText

        return view
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
        editESF.setText(String.format(Locale.US, "%.2f", currentESFRate))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

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

                currentESFRate = esfRate
                textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
                ratePreferences.saveESFRate(currentESFRate)

                dialog.dismiss()
                Toast.makeText(this, "ESF rate updated", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error updating ESF rate: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showUpdateConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Fetch Latest Rate Table")
            .setMessage("This will connect to the internet to check for updated water rates from Carmona Water District.\n\nPlease make sure you have an active internet connection.")
            .setPositiveButton("Fetch Now") { _, _ ->
                fetchLatestRates()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchLatestRates() {
        btnUpdateRates.isEnabled = false
        btnUpdateRates.text = "Fetching..."
        textLastUpdated.text = "Connecting to server..."

        Thread {
            val result = rateTableManager.fetchRemoteConfig()

            mainHandler.post {
                btnUpdateRates.isEnabled = true
                btnUpdateRates.text = "Fetch Latest Rate Table"

                when (result) {
                    is RateTableManager.FetchResult.Success -> {
                        if (result.isNew) {
                            Toast.makeText(
                                this,
                                "Rate table updated to version ${result.config.configVersion}!",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                this,
                                "You already have the latest rates (v${result.config.configVersion})",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        loadDynamicRateTypes()
                        loadSavedSettings()
                        updateLastFetchedDisplay()
                    }
                    is RateTableManager.FetchResult.Error -> {
                        Toast.makeText(
                            this,
                            "Failed to fetch rates: ${result.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        updateLastFetchedDisplay()
                    }
                }
            }
        }.start()
    }

    private fun saveSettings() {
        try {
            val position = spinnerRateType.selectedItemPosition
            val isCustom = position == dynamicRateTypes.size - 1

            val rateType: WaterRateType = if (isCustom) {
                WaterRateType.CUSTOM
            } else if (position < dynamicServiceTypes.size) {
                val typeId = dynamicServiceTypes[position].typeId
                try {
                    WaterRateType.valueOf(typeId)
                } catch (e: IllegalArgumentException) {
                    WaterRateType.RESIDENTIAL
                }
            } else {
                WaterRateType.RESIDENTIAL
            }

            ratePreferences.saveRateType(rateType)

            if (rateType == WaterRateType.CUSTOM) {
                if (currentESFRate < 0) {
                    Toast.makeText(this, "Please enter a valid ESF rate", Toast.LENGTH_SHORT).show()
                    return
                }
                ratePreferences.saveESFRate(currentESFRate)

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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
