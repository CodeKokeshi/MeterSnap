package com.kokeshi.metersnap

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Main Settings screen - just 3 navigation buttons to sub-screens:
 * 1. History & Readings
 * 2. Rate Configuration
 * 3. Other Options
 */
class SettingsActivityNew : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var btnHistoryReadings: Button
    private lateinit var btnRateConfiguration: Button
    private lateinit var btnOtherOptions: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings_new)

        initializeViews()
        setupToolbar()
        setupWindowInsets()
        setupListeners()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        btnHistoryReadings = findViewById(R.id.btn_history_readings)
        btnRateConfiguration = findViewById(R.id.btn_rate_configuration)
        btnOtherOptions = findViewById(R.id.btn_other_options)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    private fun setupWindowInsets() {
        val rootView = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(0, systemBars.top, 0, 0)
            view.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            windowInsets
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupListeners() {
        btnHistoryReadings.setOnClickListener {
            startActivity(Intent(this, HistorySettingsActivity::class.java))
        }

        btnRateConfiguration.setOnClickListener {
            startActivity(Intent(this, RateConfigurationActivity::class.java))
        }

        btnOtherOptions.setOnClickListener {
            startActivity(Intent(this, OtherOptionsActivity::class.java))
        }
    }
}
