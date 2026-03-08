package com.kokeshi.metersnap.utils

import android.content.Context
import android.util.Log
import com.kokeshi.metersnap.data.ProjectedReading
import com.kokeshi.metersnap.data.ProjectionSource
import com.kokeshi.metersnap.data.ReadingDataPoint
import com.kokeshi.metersnap.data.RegressionResult
import com.kokeshi.metersnap.data.ScanHistoryEntry
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Predicts billing-period-end meter readings using SMART baseline approach with regression fallback.
 * 
 * When a CWD charge date (1-28) is set, all calculations use billing period boundaries
 * instead of calendar month boundaries. Otherwise falls back to calendar months.
 * 
 * ## Algorithm Overview (Priority Order):
 * 
 * ### 1. SMART APPROACH (Preferred) - Billing Period Baseline Extrapolation
 * Uses the "previous reading" (start of billing period baseline) for stable predictions.
 * 
 * **How it works:**
 * - Previous reading: 2096 cu.m
 * - Current reading (day 24 of billing period): 2098 cu.m  
 * - Usage so far: 2 cu.m in 24 days = 0.083 cu.m/day
 * - Prediction: 2096 + (0.083 × period_length) = ~2099 cu.m by charge date
 * 
 * **Why it's stable:**
 * - Uses entire billing period's context, not just recent scans
 * - Immune to short-term usage spikes
 * - Simple math: total_usage ÷ days_elapsed × total_days_in_period
 * 
 * ### 2. REGRESSION APPROACH (Fallback)
 * When previous reading unavailable, uses linear regression on scan history.
 * 
 * **Stability improvements:**
 * - Groups scans within 1-hour windows, keeps latest from each window
 * - Caps slope to max 8 cu.m/day (prevents insane predictions from close scans)
 * - Anchors prediction to actual last reading (not regression line)
 * - Rejects if scans span < 6 hours
 * 
 * ### 3. SINGLE POINT (Last Resort)
 * Returns current reading if insufficient data for extrapolation
 * 
 * ## Minimum Requirements:
 * - SMART: Previous reading + current reading
 * - REGRESSION: At least 2 scans spanning 6+ hours
 * - FALLBACK: At least 1 scan (returns flat prediction)
 */
class ReadingPredictor(context: Context) {
    
    private val chargeDate: Int
    private val chargeDateSet: Boolean
    
    init {
        val prefs = context.getSharedPreferences("MeterSnapPrefs", Context.MODE_PRIVATE)
        chargeDateSet = prefs.getBoolean("charge_date_set", false)
        chargeDate = prefs.getInt("charge_date", 28)
    }
    
    companion object {
        private const val TAG = "ReadingPredictor"
        
        // Minimum data points for valid regression
        private const val MIN_DATA_POINTS_REGRESSION = 2
        
        // Minimum day spread for reliable regression (in days)
        private const val MIN_DAY_SPREAD = 0.25 // At least 6 hours between first and last scan
        
        // Time window for grouping close scans (in hours)
        private const val SCAN_GROUPING_WINDOW_HOURS = 1.0
        
        // Maximum reasonable daily usage for a household (cu.m/day)
        // This prevents insane slopes from corrupting predictions
        private const val MAX_REASONABLE_DAILY_USAGE = 8.0
        
        // R² threshold for "good" fit
        private const val R_SQUARED_GOOD = 0.7
        private const val R_SQUARED_ACCEPTABLE = 0.5
        
        // Default daily usage estimate if no history (cu.m/day)
        private const val DEFAULT_DAILY_USAGE = 0.8
    }
    
    private val historyManager = HistoryManager(context)
    private val context = context
    
    /**
     * Predict the meter reading at the end of the billing period for the specified month.
     * When charge date is set, the billing period end is the charge date.
     * Otherwise, the end is 11:59:59 PM on the last calendar day of the month.
     * 
     * @param year The year (e.g., 2026)
     * @param month The month (1-12, where 1 = January)
     * @return ProjectedReading or null if prediction is impossible
     */
    fun predictMonthEndReading(year: Int, month: Int): ProjectedReading? {
        val monthYear = String.format("%04d-%02d", year, month)
        Log.d(TAG, "Predicting end-of-month reading for $monthYear")
        
        // Get readings for this month
        val monthReadings = getReadingsForMonth(year, month)
        Log.d(TAG, "Found ${monthReadings.size} readings for $monthYear")
        
        if (monthReadings.isEmpty()) {
            Log.w(TAG, "No readings available for $monthYear, trying previous months")
            return predictUsingPreviousMonths(year, month)
        }
        
        // Get the last actual reading
        val lastReading = monthReadings.maxByOrNull { it.timestamp }!!
        
        // Calculate the last day of month timestamp (11:59:59 PM)
        val lastDayTimestamp = getMonthEndTimestamp(year, month)
        
        // TRY SMART APPROACH FIRST: Use month baseline (previous reading) if available
        val previousReading = getPreviousMonthReading(year, month)
        if (previousReading != null && previousReading < lastReading.currentReading) {
            Log.d(TAG, "Using SMART month-baseline approach: previous=$previousReading, current=${lastReading.currentReading}")
            return predictUsingMonthBaseline(
                previousReading = previousReading,
                lastReading = lastReading,
                lastDayTimestamp = lastDayTimestamp,
                monthYear = monthYear,
                year = year,
                month = month
            )
        }
        
        // FALLBACK: Use regression on scan history
        Log.d(TAG, "Previous reading not available, using regression approach")
        
        // Get the earliest reading timestamp as reference
        val referenceTimestamp = monthReadings.minByOrNull { it.timestamp }?.timestamp 
            ?: return null
        
        // Convert to data points and filter out scans that are too close together
        val allDataPoints = monthReadings
            .sortedBy { it.timestamp }
            .map { entry ->
                ReadingDataPoint.create(entry.timestamp, entry.currentReading, referenceTimestamp)
            }
        
        // Filter to keep only scans with sufficient time spacing
        val filteredDataPoints = filterCloseScanPoints(allDataPoints)
        
        Log.d(TAG, "Filtered from ${allDataPoints.size} to ${filteredDataPoints.size} data points after removing close scans")
        
        // Check if we have enough data for regression after filtering
        return if (filteredDataPoints.size >= MIN_DATA_POINTS_REGRESSION) {
            predictUsingRegression(
                dataPoints = filteredDataPoints,
                referenceTimestamp = referenceTimestamp,
                lastDayTimestamp = lastDayTimestamp,
                lastReading = lastReading,
                monthYear = monthYear
            )
        } else {
            // Single data point - use extrapolation with historical average
            predictUsingSinglePoint(
                dataPoint = filteredDataPoints.first(),
                lastReading = lastReading,
                referenceTimestamp = referenceTimestamp,
                lastDayTimestamp = lastDayTimestamp,
                year = year,
                month = month,
                monthYear = monthYear
            )
        }
    }
    
    /**
     * Filter out data points that are too close together in time.
     * Groups scans into time windows and keeps ONLY THE LATEST scan from each window.
     * This handles the case where users take multiple scans in quick succession.
     */
    private fun filterCloseScanPoints(dataPoints: List<ReadingDataPoint>): List<ReadingDataPoint> {
        if (dataPoints.size <= 1) return dataPoints
        
        val windowSizeDays = SCAN_GROUPING_WINDOW_HOURS / 24.0
        val filtered = mutableListOf<ReadingDataPoint>()
        
        var currentWindowStart = dataPoints.first().dayIndex
        var latestInWindow = dataPoints.first()
        
        for (i in 1 until dataPoints.size) {
            val current = dataPoints[i]
            val timeSinceWindowStart = current.dayIndex - currentWindowStart
            
            if (timeSinceWindowStart < windowSizeDays) {
                // Still in same window - keep the latest reading
                latestInWindow = current
                Log.d(TAG, "Grouping scan at dayIndex ${current.dayIndex} into same window")
            } else {
                // New window - save the latest from previous window
                filtered.add(latestInWindow)
                currentWindowStart = current.dayIndex
                latestInWindow = current
            }
        }
        
        // Don't forget the last window
        filtered.add(latestInWindow)
        
        Log.d(TAG, "Grouped ${dataPoints.size} scans into ${filtered.size} representative points")
        return filtered
    }
    
    /**
     * SMART APPROACH: Predict using month baseline (previous reading).
     * This is more stable than regression because it uses the entire month's context.
     * 
     * When charge date is set, uses billing period boundaries instead of calendar month.
     * Example: Previous reading on Jan 1 = 2096, Current reading on Jan 24 = 2098
     * Days elapsed = 24, Usage so far = 2, Avg daily = 2/24 = 0.083 cu.m/day
     * Days in billing period = ~30, Predicted = 2096 + (0.083 × 30) = 2098.5
     */
    private fun predictUsingMonthBaseline(
        previousReading: Int,
        lastReading: ScanHistoryEntry,
        lastDayTimestamp: Long,
        monthYear: String,
        year: Int,
        month: Int
    ): ProjectedReading {
        // Calculate days elapsed since start of billing period
        val periodStartTimestamp = getMonthStartTimestamp(year, month)
        val daysElapsed = (lastReading.timestamp - periodStartTimestamp).toDouble() / (24 * 60 * 60 * 1000)
        
        // Calculate usage so far this billing period
        val usageSoFar = lastReading.currentReading - previousReading
        
        // Calculate average daily usage
        val avgDailyUsage = if (daysElapsed > 0) usageSoFar / daysElapsed else DEFAULT_DAILY_USAGE
        
        // Cap to reasonable maximum
        val normalizedDailyUsage = avgDailyUsage.coerceIn(0.0, MAX_REASONABLE_DAILY_USAGE)
        
        // Calculate total days in billing period
        val totalDaysInPeriod = ((lastDayTimestamp - periodStartTimestamp).toDouble() / (24 * 60 * 60 * 1000)).toInt().coerceAtLeast(28)
        
        // Predict end-of-period reading
        val rawPredictedReading = previousReading + (normalizedDailyUsage * totalDaysInPeriod)
        val predictedReading = rawPredictedReading.coerceAtLeast(lastReading.currentReading.toDouble())
        
        // Calculate confidence based on days elapsed
        val confidence = (daysElapsed / totalDaysInPeriod).coerceIn(0.1, 0.95)
        
        Log.d(TAG, "SMART PREDICTION: prev=$previousReading, current=${lastReading.currentReading}, " +
                "usage=$usageSoFar in ${daysElapsed.format(1)} days = ${normalizedDailyUsage.format(3)} cu.m/day")
        Log.d(TAG, "Predicted for $totalDaysInPeriod days: ${predictedReading.format(1)} (confidence: ${(confidence*100).format(0)}%)")
        
        return ProjectedReading(
            monthYear = monthYear,
            projectedReading = predictedReading,
            lastActualReading = lastReading.currentReading,
            lastActualTimestamp = lastReading.timestamp,
            confidenceScore = confidence,
            dailyUsageRate = normalizedDailyUsage,
            dataPointsUsed = 2, // Previous + Current
            rSquared = 1.0, // Perfect fit for linear extrapolation
            source = ProjectionSource.EXTRAPOLATION
        )
    }
    
    /**
     * Get the previous reading (from end of previous month or user's manual input)
     */
    private fun getPreviousMonthReading(year: Int, month: Int): Int? {
        // First, try to get from SharedPreferences (user's manual "previous reading" setting)
        val prefs = context.getSharedPreferences("MeterSnapPrefs", android.content.Context.MODE_PRIVATE)
        val manualPrevReading = prefs.getInt("previous_reading", -1)
        
        if (manualPrevReading != -1) {
            Log.d(TAG, "Using manual previous reading from SharedPreferences: $manualPrevReading")
            return manualPrevReading
        }
        
        // Fallback: Try to get the last scan from previous month
        val prevMonth = if (month == 1) 12 else month - 1
        val prevYear = if (month == 1) year - 1 else year
        val prevMonthReadings = getReadingsForMonth(prevYear, prevMonth)
        
        if (prevMonthReadings.isNotEmpty()) {
            val lastPrevMonthReading = prevMonthReadings.maxByOrNull { it.timestamp }!!.currentReading
            Log.d(TAG, "Using last reading from previous month: $lastPrevMonthReading")
            return lastPrevMonthReading
        }
        
        Log.d(TAG, "No previous reading available")
        return null
    }
    
    /**
     * Get timestamp for start of billing period (00:00:00).
     * When charge date is set: the day after the previous month's charge date.
     * Otherwise: first day of calendar month.
     */
    private fun getMonthStartTimestamp(year: Int, month: Int): Long {
        val calendar = Calendar.getInstance()
        if (chargeDateSet) {
            // Billing period starts on the day after the previous month's charge date.
            calendar.set(year, month - 1, 1) // set to this month first
            calendar.add(Calendar.MONTH, -1) // go to previous month
            calendar.set(Calendar.DAY_OF_MONTH, chargeDate.coerceAtMost(calendar.getActualMaximum(Calendar.DAY_OF_MONTH)))
            calendar.add(Calendar.DAY_OF_MONTH, 1) // day after previous charge date
        } else {
            calendar.set(year, month - 1, 1)
        }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    
    /**
     * Predict using linear regression on multiple data points.
     */
    private fun predictUsingRegression(
        dataPoints: List<ReadingDataPoint>,
        referenceTimestamp: Long,
        lastDayTimestamp: Long,
        lastReading: ScanHistoryEntry,
        monthYear: String
    ): ProjectedReading {
        Log.d(TAG, "Using regression with ${dataPoints.size} data points")
        
        // Perform linear regression
        val regression = performLinearRegression(dataPoints)
        
        // Normalize slope if it's unreasonably high
        val normalizedSlope = regression.slope.coerceIn(0.0, MAX_REASONABLE_DAILY_USAGE)
        if (regression.slope > MAX_REASONABLE_DAILY_USAGE) {
            Log.w(TAG, "Slope ${regression.slope} cu.m/day is unrealistic, capped to $MAX_REASONABLE_DAILY_USAGE cu.m/day")
        }
        
        // Check if regression is valid (enough time spread between points)
        if (!regression.isValid) {
            Log.w(TAG, "Regression not valid (insufficient day spread), falling back to simple extrapolation")
            val daySpread = dataPoints.maxOf { it.dayIndex } - dataPoints.minOf { it.dayIndex }
            Log.d(TAG, "Day spread: $daySpread days (need at least $MIN_DAY_SPREAD days)")
            
            // Just return current reading as prediction since we can't extrapolate reliably
            return ProjectedReading(
                monthYear = monthYear,
                projectedReading = lastReading.currentReading.toDouble(),
                lastActualReading = lastReading.currentReading,
                lastActualTimestamp = lastReading.timestamp,
                confidenceScore = 0.0,
                dailyUsageRate = 0.0,
                dataPointsUsed = dataPoints.size,
                rSquared = 0.0,
                source = ProjectionSource.EXTRAPOLATION
            )
        }
        
        // Smart prediction: Use actual last reading as anchor, extrapolate with normalized slope
        val daysUntilEndOfMonth = (lastDayTimestamp - lastReading.timestamp).toDouble() / (24 * 60 * 60 * 1000)
        val projectedUsage = normalizedSlope * daysUntilEndOfMonth
        val predictedReading = (lastReading.currentReading + projectedUsage).coerceAtLeast(lastReading.currentReading.toDouble())
        
        Log.d(TAG, "Smart prediction: last reading ${lastReading.currentReading} + ${daysUntilEndOfMonth.format(2)} days * ${normalizedSlope.format(2)} cu.m/day = $predictedReading")
        
        // Calculate confidence score
        val confidence = calculateConfidence(regression.copy(slope = normalizedSlope), dataPoints)
        
        Log.d(TAG, "Regression result: slope=${normalizedSlope} (original: ${regression.slope}), r²=${regression.rSquared}")
        
        return ProjectedReading(
            monthYear = monthYear,
            projectedReading = predictedReading,
            lastActualReading = lastReading.currentReading,
            lastActualTimestamp = lastReading.timestamp,
            confidenceScore = confidence,
            dailyUsageRate = normalizedSlope,
            dataPointsUsed = dataPoints.size,
            rSquared = regression.rSquared,
            source = ProjectionSource.REGRESSION
        )
    }
    
    // Extension function for formatting doubles
    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
    
    /**
     * Perform ordinary least squares linear regression.
     * 
     * Formula: y = mx + b
     * where m = Σ((x - x̄)(y - ȳ)) / Σ((x - x̄)²)
     *       b = ȳ - m * x̄
     */
    private fun performLinearRegression(dataPoints: List<ReadingDataPoint>): RegressionResult {
        val n = dataPoints.size
        
        if (n < 2) {
            return RegressionResult(
                slope = 0.0,
                intercept = dataPoints.firstOrNull()?.reading?.toDouble() ?: 0.0,
                rSquared = 0.0,
                dataPoints = n,
                isValid = false
            )
        }
        
        // Calculate means
        val xMean = dataPoints.map { it.dayIndex }.average()
        val yMean = dataPoints.map { it.reading.toDouble() }.average()
        
        // Calculate slope (m)
        var numerator = 0.0
        var denominator = 0.0
        
        for (point in dataPoints) {
            val xDiff = point.dayIndex - xMean
            val yDiff = point.reading - yMean
            numerator += xDiff * yDiff
            denominator += xDiff * xDiff
        }
        
        val slope = if (denominator != 0.0) numerator / denominator else 0.0
        
        // Calculate intercept (b)
        val intercept = yMean - slope * xMean
        
        // Calculate R-squared
        val rSquared = calculateRSquared(dataPoints, slope, intercept, yMean)
        
        // Check validity
        val daySpread = dataPoints.maxOf { it.dayIndex } - dataPoints.minOf { it.dayIndex }
        val isValid = slope >= 0 && daySpread >= MIN_DAY_SPREAD
        
        return RegressionResult(
            slope = slope,
            intercept = intercept,
            rSquared = rSquared,
            dataPoints = n,
            isValid = isValid
        )
    }
    
    /**
     * Calculate R-squared (coefficient of determination).
     * 
     * R² = 1 - (SS_res / SS_tot)
     * where SS_res = Σ(y - ŷ)² (residual sum of squares)
     *       SS_tot = Σ(y - ȳ)² (total sum of squares)
     */
    private fun calculateRSquared(
        dataPoints: List<ReadingDataPoint>,
        slope: Double,
        intercept: Double,
        yMean: Double
    ): Double {
        var ssRes = 0.0
        var ssTot = 0.0
        
        for (point in dataPoints) {
            val predicted = slope * point.dayIndex + intercept
            ssRes += (point.reading - predicted).pow(2)
            ssTot += (point.reading - yMean).pow(2)
        }
        
        return if (ssTot > 0) 1 - (ssRes / ssTot) else 0.0
    }
    
    /**
     * Calculate confidence score based on regression quality and data characteristics.
     */
    private fun calculateConfidence(
        regression: RegressionResult,
        dataPoints: List<ReadingDataPoint>
    ): Double {
        var confidence = 0.0
        
        // R² contribution (40% weight)
        confidence += regression.rSquared * 0.4
        
        // Data points contribution (30% weight)
        val pointsScore = (dataPoints.size.coerceAtMost(10) / 10.0)
        confidence += pointsScore * 0.3
        
        // Day spread contribution (20% weight)
        val daySpread = dataPoints.maxOf { it.dayIndex } - dataPoints.minOf { it.dayIndex }
        val spreadScore = (daySpread.coerceAtMost(20.0) / 20.0)
        confidence += spreadScore * 0.2
        
        // Validity bonus (10% weight)
        if (regression.isValid && regression.slope > 0) {
            confidence += 0.1
        }
        
        return confidence.coerceIn(0.0, 1.0)
    }
    
    /**
     * Predict using a single data point and historical average usage.
     */
    private fun predictUsingSinglePoint(
        dataPoint: ReadingDataPoint,
        lastReading: ScanHistoryEntry,
        referenceTimestamp: Long,
        lastDayTimestamp: Long,
        year: Int,
        month: Int,
        monthYear: String
    ): ProjectedReading {
        Log.d(TAG, "Using single point extrapolation")
        
        // Get historical average daily usage
        val avgDailyUsage = getHistoricalAverageDailyUsage(year, month)
        
        // Calculate days from last reading to month end
        val daysRemaining = (lastDayTimestamp - lastReading.timestamp).toDouble() / (24 * 60 * 60 * 1000)
        
        // Extrapolate; never return below the latest actual reading
        val rawPredictedReading = lastReading.currentReading + avgDailyUsage * daysRemaining
        val predictedReading = rawPredictedReading.coerceAtLeast(lastReading.currentReading.toDouble())
        
        Log.d(TAG, "Single point prediction: base=${lastReading.currentReading}, daysRemaining=$daysRemaining, avgDaily=$avgDailyUsage")
        
        return ProjectedReading(
            monthYear = monthYear,
            projectedReading = predictedReading,
            lastActualReading = lastReading.currentReading,
            lastActualTimestamp = lastReading.timestamp,
            confidenceScore = 0.3, // Low confidence for single point
            dailyUsageRate = avgDailyUsage,
            dataPointsUsed = 1,
            rSquared = 0.0,
            source = ProjectionSource.EXTRAPOLATION
        )
    }
    
    /**
     * Predict using data from previous months when current month has no data.
     */
    private fun predictUsingPreviousMonths(year: Int, month: Int): ProjectedReading? {
        Log.d(TAG, "Attempting prediction using previous months")
        
        val allHistory = historyManager.getAllHistory()
        if (allHistory.isEmpty()) {
            Log.w(TAG, "No history available at all")
            return null
        }
        
        // Get the most recent reading
        val lastReading = allHistory.maxByOrNull { it.timestamp } ?: return null
        
        // Calculate average daily usage from all history
        val avgDailyUsage = getHistoricalAverageDailyUsage(year, month)
        
        // Get month end timestamp
        val lastDayTimestamp = getMonthEndTimestamp(year, month)
        
        // Calculate days from last reading to month end
        val daysRemaining = (lastDayTimestamp - lastReading.timestamp).toDouble() / (24 * 60 * 60 * 1000)
        
        if (daysRemaining < 0) {
            // Last reading is after the month end - this shouldn't happen
            Log.w(TAG, "Last reading is after target month end")
            return null
        }
        
        val rawPredictedReading = lastReading.currentReading + avgDailyUsage * daysRemaining
        val predictedReading = rawPredictedReading.coerceAtLeast(lastReading.currentReading.toDouble())
        
        val monthYear = String.format("%04d-%02d", year, month)
        
        return ProjectedReading(
            monthYear = monthYear,
            projectedReading = predictedReading,
            lastActualReading = lastReading.currentReading,
            lastActualTimestamp = lastReading.timestamp,
            confidenceScore = 0.2, // Very low confidence
            dailyUsageRate = avgDailyUsage,
            dataPointsUsed = 0,
            rSquared = 0.0,
            source = ProjectionSource.FALLBACK
        )
    }
    
    /**
     * Calculate average daily usage from historical data.
     * Excludes the current prediction month.
     */
    fun getHistoricalAverageDailyUsage(excludeYear: Int? = null, excludeMonth: Int? = null): Double {
        val allHistory = historyManager.getAllHistory()
        
        if (allHistory.isEmpty()) {
            return DEFAULT_DAILY_USAGE
        }
        
        // Group by month and calculate monthly usages
        val monthlyUsages = mutableMapOf<String, Int>()
        val monthlyDays = mutableMapOf<String, Int>()
        
        val calendar = Calendar.getInstance()
        
        for (entry in allHistory) {
            val key: String
            val entryYear: Int
            val entryMonth: Int
            
            if (chargeDateSet) {
                val periodKey = ChargeDateHelper.getBillingPeriodKey(entry.timestamp, chargeDate)
                val parts = periodKey.split("-")
                entryYear = parts[0].toInt()
                entryMonth = parts[1].toInt()
                key = periodKey
            } else {
                calendar.timeInMillis = entry.timestamp
                entryYear = calendar.get(Calendar.YEAR)
                entryMonth = calendar.get(Calendar.MONTH) + 1
                key = "$entryYear-$entryMonth"
            }
            
            // Skip excluded month
            if (excludeYear == entryYear && excludeMonth == entryMonth) continue
            
            monthlyUsages[key] = (monthlyUsages[key] ?: 0) + entry.usage
        }
        
        if (monthlyUsages.isEmpty()) {
            // Try to calculate from consecutive readings
            return calculateDailyUsageFromConsecutiveReadings(allHistory)
        }
        
        // Calculate average daily usage (assuming ~30 days per month)
        val totalMonthlyUsage = monthlyUsages.values.sum()
        val monthCount = monthlyUsages.size
        
        return (totalMonthlyUsage.toDouble() / monthCount / 30.0).coerceAtLeast(0.1)
    }
    
    /**
     * Calculate daily usage from time gaps between consecutive readings.
     */
    private fun calculateDailyUsageFromConsecutiveReadings(history: List<ScanHistoryEntry>): Double {
        if (history.size < 2) return DEFAULT_DAILY_USAGE
        
        val sorted = history.sortedBy { it.timestamp }
        val dailyRates = mutableListOf<Double>()
        
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            
            val daysDiff = (curr.timestamp - prev.timestamp).toDouble() / (24 * 60 * 60 * 1000)
            val readingDiff = curr.currentReading - prev.currentReading
            
            if (daysDiff > 0 && readingDiff >= 0) {
                dailyRates.add(readingDiff / daysDiff)
            }
        }
        
        return if (dailyRates.isNotEmpty()) {
            dailyRates.average().coerceAtLeast(0.1)
        } else {
            DEFAULT_DAILY_USAGE
        }
    }
    
    /**
     * Get all scan history entries for a specific billing period.
     * When charge date is set, uses billing period boundaries.
     * Otherwise, filters by calendar month.
     */
    fun getReadingsForMonth(year: Int, month: Int): List<ScanHistoryEntry> {
        val allHistory = historyManager.getAllHistory()
        
        if (chargeDateSet) {
            val periodStart = getMonthStartTimestamp(year, month)
            val periodEnd = getMonthEndTimestamp(year, month)
            return allHistory.filter { entry ->
                entry.timestamp in periodStart..periodEnd
            }
        }
        
        val calendar = Calendar.getInstance()
        return allHistory.filter { entry ->
            calendar.timeInMillis = entry.timestamp
            calendar.get(Calendar.YEAR) == year && calendar.get(Calendar.MONTH) + 1 == month
        }
    }
    
    /**
     * Get timestamp for 11:59:59 PM on the end of the billing period.
     * When charge date is set: this month's charge date.
     * Otherwise: last day of calendar month.
     */
    private fun getMonthEndTimestamp(year: Int, month: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, month - 1) // Calendar months are 0-indexed
        if (chargeDateSet) {
            calendar.set(Calendar.DAY_OF_MONTH, chargeDate.coerceAtMost(calendar.getActualMaximum(Calendar.DAY_OF_MONTH)))
        } else {
            calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        }
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
    }
    
    /**
     * Get the current month and year.
     */
    fun getCurrentMonthYear(): Pair<Int, Int> {
        val calendar = Calendar.getInstance()
        return Pair(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
    }
    
    /**
     * Returns the number of days until the billing period end.
     * Uses charge date when set, otherwise calendar month end.
     */
    fun getDaysUntilMonthEnd(): Int {
        if (chargeDateSet) {
            return ChargeDateHelper.getDaysUntilChargeDate(chargeDate)
        }
        val calendar = Calendar.getInstance()
        val today = calendar.get(Calendar.DAY_OF_MONTH)
        val lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        return lastDay - today
    }
    
    /**
     * Check if today is the billing period end date.
     */
    fun isLastDayOfMonth(): Boolean {
        return getDaysUntilMonthEnd() == 0
    }
    
    /**
     * Get month-end reading prediction for the current month.
     */
    fun predictCurrentMonthEndReading(): ProjectedReading? {
        val (year, month) = getCurrentMonthYear()
        return predictMonthEndReading(year, month)
    }
}
