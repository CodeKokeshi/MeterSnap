package com.kokeshi.metersnap.data

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a projected (predicted) meter reading at month end.
 * 
 * This is used to provide an alternative "previous reading" for the next month,
 * independent of CWD's reading which may have human error.
 * 
 * The projection is calculated using linear regression on the user's scan history.
 */
data class ProjectedReading(
    /** Unique identifier */
    val id: String = UUID.randomUUID().toString(),
    
    /** The year and month this projection is for (e.g., "2026-01" for January 2026) */
    @SerializedName("month_year")
    val monthYear: String,
    
    /** Timestamp when this projection was calculated */
    @SerializedName("calculated_at")
    val calculatedAt: Long = System.currentTimeMillis(),
    
    /** The projected meter reading at 11:59:59 PM on the last day of the month */
    @SerializedName("projected_reading")
    val projectedReading: Double,
    
    /** The actual last scan reading used as basis for projection */
    @SerializedName("last_actual_reading")
    val lastActualReading: Int,
    
    /** Timestamp of the last actual scan */
    @SerializedName("last_actual_timestamp")
    val lastActualTimestamp: Long,
    
    /** Confidence score (0.0 to 1.0) based on data quality */
    @SerializedName("confidence_score")
    val confidenceScore: Double,
    
    /** 
     * Daily usage rate (cu.m/day) used in projection.
     * This is the slope from linear regression.
     */
    @SerializedName("daily_usage_rate")
    val dailyUsageRate: Double,
    
    /** Number of data points (scans) used for the regression */
    @SerializedName("data_points_used")
    val dataPointsUsed: Int,
    
    /** R-squared value from linear regression (goodness of fit) */
    @SerializedName("r_squared")
    val rSquared: Double,
    
    /**
     * Source of this projection:
     * - REGRESSION: Calculated using linear regression on historical data
     * - MANUAL_SCAN: User scanned on the last day (actual reading, not projected)
     * - FALLBACK: Not enough data, used simple average or last reading
     */
    @SerializedName("source")
    val source: ProjectionSource = ProjectionSource.REGRESSION,
    
    /** Whether user confirmed/verified this projection manually */
    @SerializedName("user_verified")
    val userVerified: Boolean = false,
    
    /** Optional user-provided actual reading (if they scanned on last day) */
    @SerializedName("user_actual_reading")
    val userActualReading: Int? = null
) {
    /**
     * Get the reading to use as "previous reading" for next month.
     * Prefers user-verified actual reading over projection.
     */
    fun getEffectiveReading(): Int {
        return userActualReading ?: projectedReading.toInt()
    }
    
    /**
     * Get a human-readable description of the projection quality
     */
    fun getQualityDescription(): String {
        return when {
            source == ProjectionSource.MANUAL_SCAN -> "✓ Actual scan"
            userVerified -> "✓ User verified"
            confidenceScore >= 0.8 -> "High confidence"
            confidenceScore >= 0.5 -> "Medium confidence"
            else -> "Low confidence"
        }
    }
    
    /**
     * Get formatted calculation date
     */
    fun getFormattedCalculationDate(): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
        return sdf.format(Date(calculatedAt))
    }
    
    /**
     * Get the month name (e.g., "January 2026")
     */
    fun getMonthDisplayName(): String {
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

/**
 * Enum representing the source/method used for a projected reading
 */
enum class ProjectionSource {
    /** Calculated using linear regression on scan history */
    REGRESSION,
    
    /** User manually scanned on the last day of the month */
    MANUAL_SCAN,
    
    /** Fallback method (not enough data for regression) */
    FALLBACK,
    
    /** Simple extrapolation from single data point */
    EXTRAPOLATION
}

/**
 * Represents data points used for regression analysis
 */
data class ReadingDataPoint(
    /** Timestamp of the reading */
    val timestamp: Long,
    
    /** The meter reading value */
    val reading: Int,
    
    /** Days since start of analysis period */
    val dayIndex: Double
) {
    companion object {
        /**
         * Create a data point with calculated day index from a reference timestamp
         */
        fun create(timestamp: Long, reading: Int, referenceTimestamp: Long): ReadingDataPoint {
            val dayIndex = (timestamp - referenceTimestamp).toDouble() / (24 * 60 * 60 * 1000)
            return ReadingDataPoint(timestamp, reading, dayIndex)
        }
    }
}

/**
 * Result of linear regression analysis
 */
data class RegressionResult(
    /** Slope (daily usage rate) */
    val slope: Double,
    
    /** Y-intercept (reading at day 0) */
    val intercept: Double,
    
    /** R-squared (coefficient of determination) */
    val rSquared: Double,
    
    /** Number of data points used */
    val dataPoints: Int,
    
    /** Whether the regression is considered valid/reliable */
    val isValid: Boolean
) {
    /**
     * Predict the reading at a given day index
     */
    fun predict(dayIndex: Double): Double {
        return slope * dayIndex + intercept
    }
}
