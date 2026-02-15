package com.kokeshi.metersnap

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import com.kokeshi.metersnap.utils.HistoryManager
import com.kokeshi.metersnap.utils.ProjectedReadingManager
import com.kokeshi.metersnap.data.ProjectedReading
import java.util.Locale

/**
 * Activity for managing history-related settings including:
 * - CWD Previous Reading
 * - App's Previous Reading (read-only)
 * - Projected Month-End readings (CWD and App versions)
 */
class HistorySettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var textCwdPrevious: TextView
    private lateinit var btnEditCwdPrevious: Button
    private lateinit var textAppPrevious: TextView
    private lateinit var textAppPreviousDetails: TextView
    private lateinit var textProjectedCwd: TextView
    private lateinit var textProjectedCwdDetails: TextView
    private lateinit var textProjectedApp: TextView
    private lateinit var textProjectedAppDetails: TextView
    private lateinit var btnHistoryLogs: Button
    private lateinit var cardPredictionStats: CardView
    private lateinit var textPredictionStats: TextView

    private lateinit var prefs: SharedPreferences
    private lateinit var projectedReadingManager: ProjectedReadingManager
    private lateinit var historyManager: HistoryManager
    
    companion object {
        private const val PREFS_NAME = "MeterSnapPrefs"
        private const val PREF_PREVIOUS_READING = "previous_reading"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history_settings)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        projectedReadingManager = ProjectedReadingManager(this)
        historyManager = HistoryManager(this)

        initializeViews()
        setupToolbar()
        setupWindowInsets()
        loadData()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        loadData() // Refresh data when returning from history
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

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        textCwdPrevious = findViewById(R.id.text_cwd_previous)
        btnEditCwdPrevious = findViewById(R.id.btn_edit_cwd_previous)
        textAppPrevious = findViewById(R.id.text_app_previous)
        textAppPreviousDetails = findViewById(R.id.text_app_previous_details)
        textProjectedCwd = findViewById(R.id.text_projected_cwd)
        textProjectedCwdDetails = findViewById(R.id.text_projected_cwd_details)
        textProjectedApp = findViewById(R.id.text_projected_app)
        textProjectedAppDetails = findViewById(R.id.text_projected_app_details)
        btnHistoryLogs = findViewById(R.id.btn_history_logs)
        cardPredictionStats = findViewById(R.id.card_prediction_stats)
        textPredictionStats = findViewById(R.id.text_prediction_stats)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadData() {
        loadCwdPreviousReading()
        loadAppPreviousReading()
        loadProjections()
        loadPredictionStats()
    }

    private fun loadCwdPreviousReading() {
        val previousReading = prefs.getInt(PREF_PREVIOUS_READING, -1)
        if (previousReading >= 0) {
            textCwdPrevious.text = previousReading.toString()
        } else {
            textCwdPrevious.text = "Not Set"
        }
    }

    private fun loadAppPreviousReading() {
        val appPrevious = projectedReadingManager.getAppPredictedPreviousReading()
        if (appPrevious != null) {
            textAppPrevious.text = appPrevious.toString()
            
            // Get details about where this came from
            val projection = projectedReadingManager.getPreviousMonthProjection()
            if (projection != null) {
                val confidence = (projection.confidenceScore * 100).toInt()
                textAppPreviousDetails.text = "Confidence: $confidence%"
            } else {
                textAppPreviousDetails.text = "From last month's scans"
            }
        } else {
            textAppPrevious.text = "No data"
            textAppPreviousDetails.text = "Need more scans to predict"
        }
    }

    private fun loadProjections() {
        // CWD-based projection (uses CWD previous + current scan data)
        val cwdProjection = projectedReadingManager.getCurrentMonthProjection()
        if (cwdProjection != null) {
            // Display as whole integer
            textProjectedCwd.text = cwdProjection.projectedReading.toInt().toString()
            
            val cwdPrev = prefs.getInt(PREF_PREVIOUS_READING, 0)
            val projectedUsage = cwdProjection.projectedReading.toInt() - cwdPrev
            val confidence = (cwdProjection.confidenceScore * 100).toInt()
            textProjectedCwdDetails.text = "Usage: ~$projectedUsage m³ • Confidence: $confidence%"
            
            // Make clickable to show breakdown
            val projectionCopy = cwdProjection
            textProjectedCwd.setOnClickListener { showProjectionBreakdown(projectionCopy, cwdPrev, "CWD") }
            textProjectedCwdDetails.setOnClickListener { showProjectionBreakdown(projectionCopy, cwdPrev, "CWD") }
        } else {
            textProjectedCwd.text = "Not enough data"
            textProjectedCwdDetails.text = "Need at least 2 scans this month"
        }

        // App-based projection (uses app's predicted previous)
        val appPrevious = projectedReadingManager.getAppPredictedPreviousReading()
        if (cwdProjection != null && appPrevious != null) {
            // Display as whole integer
            textProjectedApp.text = cwdProjection.projectedReading.toInt().toString()
            
            val projectedUsageApp = cwdProjection.projectedReading.toInt() - appPrevious
            val confidence = (cwdProjection.confidenceScore * 100).toInt()
            textProjectedAppDetails.text = "Usage: ~$projectedUsageApp m³ • Confidence: $confidence%"
            
            // Make clickable to show breakdown
            val projectionCopy = cwdProjection
            textProjectedApp.setOnClickListener { showProjectionBreakdown(projectionCopy, appPrevious, "App") }
            textProjectedAppDetails.setOnClickListener { showProjectionBreakdown(projectionCopy, appPrevious, "App") }
        } else {
            textProjectedApp.text = "Not enough data"
            textProjectedAppDetails.text = "Need previous month's data"
        }
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

    private fun setupListeners() {
        btnEditCwdPrevious.setOnClickListener {
            showEditCwdPreviousDialog()
        }

        btnHistoryLogs.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    private fun showEditCwdPreviousDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_reading, null)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.edit_reading)
        
        val currentValue = prefs.getInt(PREF_PREVIOUS_READING, -1)
        if (currentValue >= 0) {
            editText.setText(currentValue.toString())
        }

        AlertDialog.Builder(this)
            .setTitle("Edit CWD Previous Reading")
            .setMessage("Enter the previous reading from your CWD bill:")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newValue = editText.text.toString().toIntOrNull()
                if (newValue != null && newValue >= 0) {
                    prefs.edit()
                        .putInt(PREF_PREVIOUS_READING, newValue)
                        .putBoolean("previous_reading_set", true)
                        .apply()
                    loadCwdPreviousReading()
                    loadProjections() // Recalculate projections
                    Toast.makeText(this, "Previous reading updated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show detailed breakdown of how projection was calculated
     */
    private fun showProjectionBreakdown(projection: ProjectedReading, previousReading: Int, source: String) {
        val calendar = java.util.Calendar.getInstance()
        val monthName = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(calendar.time)
        val lastDayOfMonth = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val currentDay = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        
        val projectedInt = projection.projectedReading.toInt()
        val projectedUsage = projectedInt - previousReading
        val confidencePercent = (projection.confidenceScore * 100).toInt()
        val dailyRate = projection.dailyUsageRate
        
        val message = buildString {
            appendLine("📊 Projection Breakdown ($source)\n")
            
            appendLine("📅 Target Date:")
            appendLine("   $monthName $lastDayOfMonth at 11:59 PM\n")
            
            appendLine("📈 Projected Reading:")
            appendLine("   $projectedInt m³\n")
            
            appendLine("📉 Calculation:")
            appendLine("   Previous Reading: $previousReading m³")
            appendLine("   Projected Usage: $projectedUsage m³")
            appendLine("   Daily Rate: ${String.format(Locale.US, "%.2f", dailyRate)} m³/day\n")
            
            appendLine("🎯 Confidence: $confidencePercent%")
            appendLine("   Quality: ${projection.getQualityDescription()}")
            appendLine("   Data Points: ${projection.dataPointsUsed} scans")
            
            if (projection.rSquared > 0) {
                appendLine("   R² (fit): ${String.format(Locale.US, "%.3f", projection.rSquared)}")
            }
            
            appendLine("\n💡 Method: ${projection.source}")
            
            val daysLeft = lastDayOfMonth - currentDay
            if (daysLeft > 0) {
                appendLine("\n⏳ $daysLeft days until month end")
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("How This Was Calculated")
            .setMessage(message)
            .setPositiveButton("Got it", null)
            .show()
    }
}
