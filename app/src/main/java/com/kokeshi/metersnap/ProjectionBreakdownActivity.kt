package com.kokeshi.metersnap

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kokeshi.metersnap.data.ScanHistoryEntry
import com.kokeshi.metersnap.utils.HistoryManager
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

class ProjectionBreakdownActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var textSummaryTitle: TextView
    private lateinit var textSummary: TextView
    private lateinit var textLastActualLabel: TextView
    private lateinit var textProjectedLabel: TextView
    private lateinit var progressLastActual: ProgressBar
    private lateinit var progressProjected: ProgressBar
    private lateinit var containerTrendRows: LinearLayout
    private lateinit var textTrendEmpty: TextView

    private lateinit var historyManager: HistoryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_projection_breakdown)

        historyManager = HistoryManager(this)

        initializeViews()
        setupToolbar()
        setupWindowInsets()
        loadBreakdown()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        textSummaryTitle = findViewById(R.id.text_summary_title)
        textSummary = findViewById(R.id.text_summary)
        textLastActualLabel = findViewById(R.id.text_last_actual_label)
        textProjectedLabel = findViewById(R.id.text_projected_label)
        progressLastActual = findViewById(R.id.progress_last_actual)
        progressProjected = findViewById(R.id.progress_projected)
        containerTrendRows = findViewById(R.id.container_trend_rows)
        textTrendEmpty = findViewById(R.id.text_trend_empty)
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

    private fun setupWindowInsets() {
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

        val rootView = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, systemBars.bottom)
            windowInsets
        }
    }

    private fun loadBreakdown() {
        val sourceLabel = intent.getStringExtra("EXTRA_SOURCE_LABEL") ?: "Projection"
        val monthYear = intent.getStringExtra("EXTRA_MONTH_YEAR") ?: ""
        val projectedReading = intent.getDoubleExtra("EXTRA_PROJECTED_READING", 0.0)
        val previousReading = intent.getIntExtra("EXTRA_PREVIOUS_READING", 0)
        val lastActualReading = intent.getIntExtra("EXTRA_LAST_ACTUAL_READING", 0)
        val confidenceScore = intent.getDoubleExtra("EXTRA_CONFIDENCE", 0.0)
        val dailyUsageRate = intent.getDoubleExtra("EXTRA_DAILY_RATE", 0.0)
        val dataPoints = intent.getIntExtra("EXTRA_DATA_POINTS", 0)
        val rSquared = intent.getDoubleExtra("EXTRA_R_SQUARED", 0.0)
        val quality = intent.getStringExtra("EXTRA_QUALITY") ?: "N/A"
        val method = intent.getStringExtra("EXTRA_METHOD") ?: "Unknown"

        val projectedInt = projectedReading.toInt()
        val projectedUsage = projectedInt - previousReading
        val confidencePercent = (confidenceScore * 100).toInt()

        textSummaryTitle.text = "$sourceLabel Projection"
        textSummary.text = buildString {
            append("Baseline (Previous): $previousReading m³\n")
            append("Projected End Reading: $projectedInt m³\n")
            append("Projected Usage: ~$projectedUsage m³\n")
            append("Daily Rate: ${String.format(Locale.US, "%.2f", dailyUsageRate)} m³/day\n")
            append("Confidence: $confidencePercent% ($quality)\n")
            append("Data Points: $dataPoints")
            if (rSquared > 0.0) {
                append("\nR² Fit: ${String.format(Locale.US, "%.3f", rSquared)}")
            }
            append("\nMethod: $method")
        }

        textLastActualLabel.text = "Last actual: $lastActualReading m³"
        textProjectedLabel.text = "Projected: $projectedInt m³"

        val maxValue = maxOf(previousReading, lastActualReading, projectedInt).coerceAtLeast(1)
        progressLastActual.max = maxValue
        progressProjected.max = maxValue
        progressLastActual.progress = lastActualReading.coerceAtLeast(0)
        progressProjected.progress = projectedInt.coerceAtLeast(0)

        progressLastActual.progressTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, android.R.color.holo_blue_light)
        )
        progressProjected.progressTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, android.R.color.holo_blue_dark)
        )

        renderTrendGraph(monthYear, previousReading, projectedInt)
    }

    private fun renderTrendGraph(monthYear: String, previousReading: Int, projectedInt: Int) {
        val monthEntries = historyManager.getAllHistory()
            .filter { entry -> getMonthKey(entry.timestamp) == monthYear }
            .sortedBy { it.timestamp }
            .takeLast(7)

        containerTrendRows.removeAllViews()

        if (monthEntries.isEmpty()) {
            textTrendEmpty.visibility = View.VISIBLE
            return
        }

        textTrendEmpty.visibility = View.GONE
        val maxReading = maxOf(projectedInt, monthEntries.maxOf { it.currentReading })
        val baseline = previousReading.coerceAtMost(maxReading)
        val barMax = (maxReading - baseline).coerceAtLeast(1)

        monthEntries.forEachIndexed { index, entry ->
            val row = createTrendRow(index, monthEntries.size, entry, baseline, barMax)
            containerTrendRows.addView(row)
        }
    }

    private fun createTrendRow(
        index: Int,
        totalCount: Int,
        entry: ScanHistoryEntry,
        baseline: Int,
        barMax: Int
    ): View {
        val rowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (index > 0) {
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }
        }

        val label = TextView(this).apply {
            val dateLabel = SimpleDateFormat("MMM d", Locale.getDefault()).format(entry.timestamp)
            text = "$dateLabel • ${entry.currentReading} m³"
            textSize = 12f
            setTextColor(currentTextColorSecondary())
        }

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (10 * resources.displayMetrics.density).toInt()
            )
            max = barMax
            progress = (entry.currentReading - baseline).coerceAtLeast(0)
            progressTintList = ColorStateList.valueOf(
                if (index == totalCount - 1) {
                    ContextCompat.getColor(this@ProjectionBreakdownActivity, android.R.color.holo_blue_dark)
                } else {
                    ContextCompat.getColor(this@ProjectionBreakdownActivity, android.R.color.holo_blue_light)
                }
            )
        }

        rowContainer.addView(label)
        rowContainer.addView(bar)
        return rowContainer
    }

    private fun getMonthKey(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM", Locale.US).format(Date(timestamp))
    }

    private fun currentTextColorSecondary(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.textColorSecondary, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }
}
