package com.kokeshi.metersnap

import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kokeshi.metersnap.data.ScanHistoryEntry
import com.kokeshi.metersnap.utils.ChargeDateHelper
import com.kokeshi.metersnap.utils.HistoryManager
import java.text.SimpleDateFormat
import java.util.*

class ProjectionBreakdownActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var chartCanvas: View
    private lateinit var textChartTitle: TextView
    private lateinit var textChartSubtitle: TextView
    private lateinit var textStats: TextView

    private lateinit var historyManager: HistoryManager

    // Chart colours
    private val colorActualLine = Color.parseColor("#4CAF50")   // green
    private val colorPredictLine = Color.parseColor("#FF9800")  // orange
    private val colorBaselineLine = Color.parseColor("#9E9E9E") // grey
    private val colorGrid = Color.parseColor("#2C2C2C")
    private val colorGainFill = Color.parseColor("#264CAF50")   // semi-transparent green
    private val colorPredictFill = Color.parseColor("#26FF9800") // semi-transparent orange

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
        chartCanvas = findViewById(R.id.chart_canvas)
        textChartTitle = findViewById(R.id.text_chart_title)
        textChartSubtitle = findViewById(R.id.text_chart_subtitle)
        textStats = findViewById(R.id.text_stats)
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
            view.setPadding(view.paddingLeft, statusBarInsets.top, view.paddingRight, view.paddingBottom)
            windowInsets
        }
        val rootView = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, systemBars.bottom)
            windowInsets
        }
    }

    // ── Data loading ──────────────────────────────────────────────────

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

        // Header text
        textChartTitle.text = "$sourceLabel Projection"
        textChartSubtitle.text = formatMonthDisplay(monthYear)

        // Stats card
        textStats.text = buildString {
            append("Baseline (Previous):  $previousReading m³\n")
            append("Last Scanned:  $lastActualReading m³\n")
            append("Projected End:  $projectedInt m³\n")
            append("Projected Usage:  ~$projectedUsage m³\n")
            append("Daily Rate:  ${String.format(Locale.US, "%.2f", dailyUsageRate)} m³/day\n")
            append("Confidence:  $confidencePercent%  ($quality)\n")
            append("Data Points:  $dataPoints")
            if (rSquared > 0.0) append("\nR² Fit:  ${String.format(Locale.US, "%.3f", rSquared)}")
            append("\nMethod:  $method")
        }

        // Collect scan points for this billing month (billing period key when charge date is set)
        val chargeDate = intent.getIntExtra("EXTRA_CHARGE_DATE", 0)
        val monthEntries = historyManager.getAllHistory()
            .filter { entry ->
                if (chargeDate > 0) {
                    ChargeDateHelper.getBillingPeriodKey(entry.timestamp, chargeDate) == monthYear
                } else {
                    getMonthKey(entry.timestamp) == monthYear
                }
            }
            .sortedBy { it.timestamp }

        // Build the chart once the view is laid out and has dimensions
        chartCanvas.post {
            drawChart(chartCanvas, previousReading, monthEntries, projectedInt, projectedReading, monthYear, chargeDate)
        }
    }

    // ── Chart drawing ─────────────────────────────────────────────────

    private fun drawChart(
        view: View,
        baseline: Int,
        entries: List<ScanHistoryEntry>,
        projectedInt: Int,
        projectedDouble: Double,
        monthYear: String = "",
        chargeDate: Int = 0
    ) {
        val w = view.width
        val h = view.height
        if (w == 0 || h == 0) return

        val density = resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Resolve dark mode
        val isDark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val textPrimary = if (isDark) Color.WHITE else Color.DKGRAY
        val gridColor = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")

        // Chart padding
        val padLeft = 54 * density
        val padRight = 16 * density
        val padTop = 20 * density
        val padBottom = 36 * density
        val chartW = w - padLeft - padRight
        val chartH = h - padTop - padBottom

        // ── Build data series ─────────────────────────────────────────
        // Timeline: baseline (day 0) → scans → projected end
        data class ChartPoint(val dayFraction: Float, val reading: Float, val label: String, val isProjected: Boolean)

        val allPoints = mutableListOf<ChartPoint>()

        // Work out time span: from first-of-month to last-of-month (or charge-date boundaries)
        val cal = Calendar.getInstance()
        val firstTimestamp: Long
        val lastTimestamp: Long

        if (chargeDate > 0 && monthYear.isNotEmpty()) {
            // Use billing period boundaries so X-axis matches the actual billing cycle
            val pStart = ChargeDateHelper.getBillingPeriodStartTimestamp(monthYear, chargeDate)
            val pEnd = ChargeDateHelper.getBillingPeriodEndTimestamp(monthYear, chargeDate)
            if (pStart > 0L && pEnd > 0L) {
                firstTimestamp = pStart
                lastTimestamp = pEnd
            } else {
                // Fallback to calendar month
                if (entries.isNotEmpty()) cal.timeInMillis = entries.first().timestamp
                else cal.timeInMillis = System.currentTimeMillis()
                cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
                firstTimestamp = cal.timeInMillis
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
                lastTimestamp = cal.timeInMillis
            }
        } else {
            // No charge date — fall back to calendar month
            if (entries.isNotEmpty()) cal.timeInMillis = entries.first().timestamp
            else cal.timeInMillis = System.currentTimeMillis()
            cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
            firstTimestamp = cal.timeInMillis
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
            lastTimestamp = cal.timeInMillis
        }

        val totalSpanMs = (lastTimestamp - firstTimestamp).toFloat().coerceAtLeast(1f)

        // Baseline at day 0
        allPoints.add(ChartPoint(0f, baseline.toFloat(), "Start", false))

        // Actual scan points
        for (entry in entries) {
            val frac = ((entry.timestamp - firstTimestamp).toFloat() / totalSpanMs).coerceIn(0f, 1f)
            val dateStr = SimpleDateFormat("d", Locale.US).format(Date(entry.timestamp))
            allPoints.add(ChartPoint(frac, entry.currentReading.toFloat(), dateStr, false))
        }

        // Projected endpoint at day 1.0
        allPoints.add(ChartPoint(1f, projectedDouble.toFloat(), "End", true))

        // ── Y-axis range ──────────────────────────────────────────────
        val minY = allPoints.minOf { it.reading }
        val maxY = allPoints.maxOf { it.reading }
        val yRange = (maxY - minY).coerceAtLeast(1f)
        // Add 10% padding on both sides
        val yFloor = minY - yRange * 0.10f
        val yCeil = maxY + yRange * 0.10f
        val ySpan = yCeil - yFloor

        fun xOf(frac: Float) = padLeft + frac * chartW
        fun yOf(reading: Float) = padTop + chartH - ((reading - yFloor) / ySpan) * chartH

        // ── Grid lines ────────────────────────────────────────────────
        val gridPaint = Paint().apply {
            color = gridColor
            strokeWidth = 1f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
        }
        val gridLabelPaint = Paint().apply {
            color = textPrimary
            textSize = 9 * density
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }

        // Horizontal grid: ~4 lines
        val gridSteps = 4
        for (i in 0..gridSteps) {
            val reading = yFloor + ySpan * i / gridSteps
            val y = yOf(reading)
            canvas.drawLine(padLeft, y, padLeft + chartW, y, gridPaint)
            canvas.drawText(reading.toInt().toString(), 4 * density, y + 4 * density, gridLabelPaint)
        }

        // Vertical grid: ticks for each week-ish
        for (week in 0..4) {
            val frac = week / 4f
            val x = xOf(frac)
            canvas.drawLine(x, padTop, x, padTop + chartH, gridPaint)
        }

        // ── Baseline horizontal line ──────────────────────────────────
        val baselinePaint = Paint().apply {
            color = colorBaselineLine
            strokeWidth = 1.5f * density
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
            isAntiAlias = true
        }
        val baselineY = yOf(baseline.toFloat())
        canvas.drawLine(padLeft, baselineY, padLeft + chartW, baselineY, baselinePaint)

        // ── Actual data line + fill ───────────────────────────────────
        val actualPoints = allPoints.filter { !it.isProjected }
        if (actualPoints.size >= 2) {
            val linePaint = Paint().apply {
                color = colorActualLine
                strokeWidth = 2.5f * density
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val fillPath = Path()
            val linePath = Path()
            val firstX = xOf(actualPoints.first().dayFraction)
            val firstYy = yOf(actualPoints.first().reading)
            linePath.moveTo(firstX, firstYy)
            fillPath.moveTo(firstX, yOf(yFloor))
            fillPath.lineTo(firstX, firstYy)

            for (i in 1 until actualPoints.size) {
                val px = xOf(actualPoints[i].dayFraction)
                val py = yOf(actualPoints[i].reading)
                linePath.lineTo(px, py)
                fillPath.lineTo(px, py)
            }
            val lastActualX = xOf(actualPoints.last().dayFraction)
            fillPath.lineTo(lastActualX, yOf(yFloor))
            fillPath.close()

            val fillPaint = Paint().apply {
                shader = LinearGradient(
                    0f, padTop, 0f, padTop + chartH,
                    colorGainFill, Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, linePaint)
        }

        // ── Prediction dashed line + fill from last actual → end ──────
        val lastActual = actualPoints.lastOrNull()
        val predictionPoint = allPoints.last()
        if (lastActual != null) {
            val predLinePaint = Paint().apply {
                color = colorPredictLine
                strokeWidth = 2.5f * density
                style = Paint.Style.STROKE
                isAntiAlias = true
                pathEffect = DashPathEffect(floatArrayOf(8 * density, 5 * density), 0f)
                strokeCap = Paint.Cap.ROUND
            }
            val startX = xOf(lastActual.dayFraction)
            val startYy = yOf(lastActual.reading)
            val endX = xOf(predictionPoint.dayFraction)
            val endYy = yOf(predictionPoint.reading)

            canvas.drawLine(startX, startYy, endX, endYy, predLinePaint)

            // Fill under prediction segment
            val predFill = Path().apply {
                moveTo(startX, yOf(yFloor))
                lineTo(startX, startYy)
                lineTo(endX, endYy)
                lineTo(endX, yOf(yFloor))
                close()
            }
            val predFillPaint = Paint().apply {
                shader = LinearGradient(
                    0f, padTop, 0f, padTop + chartH,
                    colorPredictFill, Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawPath(predFill, predFillPaint)
        }

        // ── Dots + value labels ───────────────────────────────────────
        val dotRadius = 4.5f * density
        val dotStroke = 2f * density
        val dotOuterPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = dotStroke
            isAntiAlias = true
        }
        val dotFillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val valueLabelPaint = Paint().apply {
            color = textPrimary
            textSize = 10 * density
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val dateLabelPaint = Paint().apply {
            color = textPrimary
            textSize = 9 * density
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        for (pt in allPoints) {
            val cx = xOf(pt.dayFraction)
            val cy = yOf(pt.reading)
            val col = if (pt.isProjected) colorPredictLine else colorActualLine

            dotFillPaint.color = if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE
            dotOuterPaint.color = col
            canvas.drawCircle(cx, cy, dotRadius, dotFillPaint)
            canvas.drawCircle(cx, cy, dotRadius, dotOuterPaint)

            // Value above dot
            canvas.drawText(pt.reading.toInt().toString(), cx, cy - 8 * density, valueLabelPaint)

            // Date label below axis
            if (pt.label.isNotEmpty()) {
                canvas.drawText(pt.label, cx, padTop + chartH + 14 * density, dateLabelPaint)
            }
        }

        // ── X-axis line ───────────────────────────────────────────────
        val axisPaint = Paint().apply {
            color = textPrimary
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(padLeft, padTop + chartH, padLeft + chartW, padTop + chartH, axisPaint)

        // Apply to the view as background
        view.background = android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun getMonthKey(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM", Locale.US).format(Date(timestamp))
    }

    private fun formatMonthDisplay(monthYear: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM", Locale.US)
            val outputFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            val date = inputFormat.parse(monthYear)
            date?.let { outputFormat.format(it) } ?: monthYear
        } catch (e: Exception) {
            monthYear
        }
    }
}
