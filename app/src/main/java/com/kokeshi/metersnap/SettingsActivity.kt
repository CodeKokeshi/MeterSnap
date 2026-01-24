package com.kokeshi.metersnap

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.kokeshi.metersnap.data.TierRate
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.data.WaterRates
import com.kokeshi.metersnap.data.RemoteServiceType
import com.kokeshi.metersnap.data.RemoteTier
import com.kokeshi.metersnap.utils.RatePreferences
import com.kokeshi.metersnap.utils.RateTableManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var spinnerRateType: Spinner
    private lateinit var textESFRate: TextView
    private lateinit var btnEditESF: Button
    private lateinit var containerRateTiers: LinearLayout
    private lateinit var btnEditCustomRates: Button
    private lateinit var btnSaveSettings: Button
    private lateinit var textRatesSubtitle: TextView
    private lateinit var textPreviousReading: TextView
    private lateinit var btnEditPreviousReading: Button
    private lateinit var btnHistoryLogs: Button
    private lateinit var btnUpdateRates: Button
    private lateinit var textLastUpdated: TextView

    private lateinit var ratePreferences: RatePreferences
    private lateinit var rateTableManager: RateTableManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var customRates: MutableList<TierRate> = mutableListOf()
    private var currentESFRate: Double = 3.50
    
    // Dynamic rate types from remote config
    private var dynamicRateTypes: List<String> = emptyList()
    private var dynamicServiceTypes: List<RemoteServiceType> = emptyList()
    
    // Flag to indicate if we're using remote config
    private var usingRemoteConfig = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Enable edge-to-edge layout and handle window insets (modern approach)
        WindowCompat.setDecorFitsSystemWindows(window, false)

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
        textPreviousReading = findViewById(R.id.text_previous_reading)
        btnEditPreviousReading = findViewById(R.id.btn_edit_previous_reading)
        btnHistoryLogs = findViewById(R.id.btn_history_logs)
        btnUpdateRates = findViewById(R.id.btn_update_rates)
        textLastUpdated = findViewById(R.id.text_last_updated)
    }
    
    /**
     * Load rate types from RateTableManager (uses cached remote or defaults)
     */
    private fun loadDynamicRateTypes() {
        val config = rateTableManager.getActiveConfig()
        dynamicServiceTypes = config.serviceTypes
        usingRemoteConfig = rateTableManager.hasCachedConfig()
        
        // Build display names list (add "Custom Settings" at the end)
        dynamicRateTypes = config.serviceTypes.map { it.displayName } + listOf("Custom Settings")
        
        // Setup spinner adapter with dynamic types
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dynamicRateTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerRateType.adapter = adapter
    }
    
    /**
     * Update the "last fetched" display text
     */
    private fun updateLastFetchedDisplay() {
        val lastFetch = rateTableManager.getLastFetchTime()
        if (lastFetch > 0) {
            val dateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.US)
            val dateStr = dateFormat.format(Date(lastFetch))
            val version = rateTableManager.getCachedConfigVersion()
            textLastUpdated.text = "Last updated: $dateStr (v$version)"
        } else {
            textLastUpdated.text = "Using default built-in rates"
        }
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
        // Load Previous Reading
        val prefs = getSharedPreferences("MeterSnapPrefs", android.content.Context.MODE_PRIVATE)
        val previousReading = prefs.getInt("previous_reading", -1)
        if (previousReading != -1) {
            textPreviousReading.text = previousReading.toString()
        } else {
            textPreviousReading.text = "Not Set"
        }

        // Load rate type and find position in dynamic list
        val savedType = ratePreferences.getRateType()
        val position = when (savedType) {
            WaterRateType.CUSTOM -> dynamicRateTypes.size - 1 // Custom is always last
            else -> {
                // Find by type ID in dynamic service types
                val typeId = savedType.name
                val idx = dynamicServiceTypes.indexOfFirst { it.typeId == typeId }
                if (idx >= 0) idx else 0
            }
        }
        
        spinnerRateType.setSelection(position)
        
        // Load ESF rate from active config
        currentESFRate = rateTableManager.getEsfRate()
        textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
        
        // Handle Custom type visibility
        if (savedType == WaterRateType.CUSTOM) {
            btnEditESF.visibility = View.VISIBLE
            btnEditCustomRates.visibility = View.VISIBLE
            customRates = ratePreferences.getCustomRates().toMutableList()
            if (customRates.isEmpty()) {
                // Initialize with first service type's rates as TierRate
                val firstType = dynamicServiceTypes.firstOrNull()
                if (firstType != null) {
                    customRates = firstType.tiers.map { 
                        TierRate(it.min, it.max ?: Int.MAX_VALUE, it.rate) 
                    }.toMutableList()
                }
            }
            // Load saved custom ESF rate
            currentESFRate = ratePreferences.getESFRate()
            textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
        } else {
            btnEditESF.visibility = View.GONE
            btnEditCustomRates.visibility = View.GONE
        }

        updateRateTiersDisplay()
    }

    private fun setupListeners() {
        btnEditPreviousReading.setOnClickListener {
            showPreviousReadingDialog()
        }
        
        btnHistoryLogs.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }
        
        btnUpdateRates.setOnClickListener {
            showUpdateConfirmationDialog()
        }

        spinnerRateType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isCustom = position == dynamicRateTypes.size - 1
                
                if (isCustom) {
                    // Custom Settings
                    btnEditCustomRates.visibility = View.VISIBLE
                    btnEditESF.visibility = View.VISIBLE
                    textRatesSubtitle.text = getString(R.string.custom_settings_editable)
                    
                    // Load saved custom rates and ESF
                    customRates = ratePreferences.getCustomRates().toMutableList()
                    if (customRates.isEmpty() && dynamicServiceTypes.isNotEmpty()) {
                        val firstType = dynamicServiceTypes.first()
                        customRates = firstType.tiers.map { 
                            TierRate(it.min, it.max ?: Int.MAX_VALUE, it.rate) 
                        }.toMutableList()
                    }
                    currentESFRate = ratePreferences.getESFRate()
                    textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
                } else {
                    // Dynamic preset type
                    btnEditCustomRates.visibility = View.GONE
                    btnEditESF.visibility = View.GONE
                    
                    if (position < dynamicServiceTypes.size) {
                        val serviceType = dynamicServiceTypes[position]
                        textRatesSubtitle.text = "${serviceType.displayName} - ${rateTableManager.getTableLabel()}"
                        currentESFRate = rateTableManager.getEsfRate()
                        textESFRate.text = String.format(Locale.US, "%.2f", currentESFRate)
                    }
                }
                
                updateRateTiersDisplay()
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
    
    /**
     * Show confirmation dialog before fetching (warns about internet requirement)
     */
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
    
    /**
     * Fetch latest rates from remote server
     */
    private fun fetchLatestRates() {
        // Show loading state
        btnUpdateRates.isEnabled = false
        btnUpdateRates.text = "Fetching..."
        textLastUpdated.text = "Connecting to server..."
        
        // Fetch in background
        Thread {
            val result = rateTableManager.fetchRemoteConfig()
            
            // Update UI on main thread
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
                        
                        // Reload the UI with new config
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
    
    /**
     * Create tier view for RemoteTier (dynamic format with is_minimum_charge support)
     */
    private fun createRemoteTierView(tier: RemoteTier): View {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.item_rate_tier, containerRateTiers, false)

        val textRange = view.findViewById<TextView>(R.id.text_tier_range)
        val textRate = view.findViewById<TextView>(R.id.text_tier_rate)

        // Build range text
        val rangeText = if (tier.max == null) {
            "${tier.min}+ m³"
        } else {
            "${tier.min}-${tier.max} m³"
        }
        textRange.text = rangeText
        
        // Build rate text based on is_minimum_charge flag
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

    private fun showPreviousReadingDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Edit Previous Reading")
        builder.setMessage("Enter the reading from your last water bill.")

        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val margin = (16 * resources.displayMetrics.density).toInt()
        params.leftMargin = margin
        params.rightMargin = margin
        input.layoutParams = params
        container.addView(input)
        builder.setView(container)

        // Pre-fill
        val currentText = textPreviousReading.text.toString()
        if (currentText != "Not Set") {
            input.setText(currentText)
        }

        builder.setPositiveButton("Save") { dialog, _ ->
            val text = input.text.toString()
            if (text.isNotEmpty()) {
                val reading = text.toIntOrNull()
                if (reading != null) {
                    val prefs = getSharedPreferences("MeterSnapPrefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit()
                        .putInt("previous_reading", reading)
                        .putBoolean("previous_reading_set", true)
                        .apply()
                    
                    textPreviousReading.text = reading.toString()
                    Toast.makeText(this, "Previous reading updated!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show()
                }
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun saveSettings() {
        try {
            val position = spinnerRateType.selectedItemPosition
            val isCustom = position == dynamicRateTypes.size - 1
            
            // Determine the rate type to save
            val rateType: WaterRateType = if (isCustom) {
                WaterRateType.CUSTOM
            } else if (position < dynamicServiceTypes.size) {
                // Map dynamic service type ID to WaterRateType enum
                val typeId = dynamicServiceTypes[position].typeId
                try {
                    WaterRateType.valueOf(typeId)
                } catch (e: IllegalArgumentException) {
                    // If the remote config has a new type not in our enum, default to RESIDENTIAL
                    // In future, we could save the typeId string directly
                    WaterRateType.RESIDENTIAL
                }
            } else {
                WaterRateType.RESIDENTIAL
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

