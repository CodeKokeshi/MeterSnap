package com.kokeshi.metersnap

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * Main Settings screen - Simple navigation hub with 3 buttons:
 * 1. History and Readings - Previous readings, projections, history logs
 * 2. Rate Configuration - Water rate type, tiers, ESF
 * 3. Other Options - Reminders, prediction stats
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var btnHistoryReadings: MaterialButton
    private lateinit var btnRateConfig: MaterialButton
    private lateinit var btnOtherOptions: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        initializeViews()
        setupToolbar()
        setupWindowInsets()
        setupListeners()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        btnHistoryReadings = findViewById(R.id.btn_history_readings)
        btnRateConfig = findViewById(R.id.btn_rate_config)
        btnOtherOptions = findViewById(R.id.btn_other_options)
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

    private fun setupListeners() {
        btnHistoryReadings.setOnClickListener {
            startActivity(Intent(this, HistorySettingsActivity::class.java))
        }

        btnRateConfig.setOnClickListener {
            startActivity(Intent(this, RateConfigurationActivity::class.java))
        }

        btnOtherOptions.setOnClickListener {
            startActivity(Intent(this, OtherOptionsActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

