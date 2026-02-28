package com.kokeshi.metersnap.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kokeshi.metersnap.data.ProjectedReading
import com.kokeshi.metersnap.data.ProjectionSource
import java.util.*

/**
 * Manages storage and retrieval of projected (predicted) meter readings.
 * 
 * ## Storage Strategy:
 * - Projections are stored as a map keyed by month-year (e.g., "2026-01")
 * - Each month can have at most one projection
 * - Projections can be updated if a better one is calculated
 * - User-verified readings take precedence over predictions
 * 
 * ## Auto-Update:
 * - Automatically recalculates projection when new scans are added
 * - At month end, stores the final projection as "previous reading" for next month
 */
class ProjectedReadingManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ProjectedReadingMgr"
        private const val PREFS_NAME = "projected_readings_prefs"
        private const val KEY_PROJECTIONS = "projections_map"
        private const val KEY_LAST_AUTO_UPDATE = "last_auto_update"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val predictor = ReadingPredictor(context)
    
    /**
     * Save a projected reading for a specific month.
     * Will replace any existing projection unless user-verified.
     */
    fun saveProjection(projection: ProjectedReading, forceReplace: Boolean = false) {
        val projections = getAllProjections().toMutableMap()
        
        val existing = projections[projection.monthYear]
        
        // Don't replace user-verified readings unless forced
        if (existing != null && existing.userVerified && !forceReplace) {
            Log.d(TAG, "Not replacing user-verified projection for ${projection.monthYear}")
            return
        }
        
        // Don't replace actual scans with predictions
        if (existing != null && existing.source == ProjectionSource.MANUAL_SCAN && 
            projection.source != ProjectionSource.MANUAL_SCAN && !forceReplace) {
            Log.d(TAG, "Not replacing manual scan projection for ${projection.monthYear}")
            return
        }
        
        projections[projection.monthYear] = projection
        saveProjections(projections)
        
        Log.d(TAG, "Saved projection for ${projection.monthYear}: ${projection.projectedReading}")
    }
    
    /**
     * Get the projected reading for a specific month.
     */
    fun getProjection(year: Int, month: Int): ProjectedReading? {
        val monthYear = String.format("%04d-%02d", year, month)
        return getAllProjections()[monthYear]
    }
    
    /**
     * Get the projected reading for the current month.
     */
    fun getCurrentMonthProjection(): ProjectedReading? {
        val calendar = Calendar.getInstance()
        return getProjection(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
    }
    
    /**
     * Get the projected reading for the previous month (to use as "previous reading").
     */
    fun getPreviousMonthProjection(): ProjectedReading? {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MONTH, -1)
        return getProjection(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
    }
    
    /**
     * Get all stored projections.
     */
    fun getAllProjections(): Map<String, ProjectedReading> {
        val json = prefs.getString(KEY_PROJECTIONS, null) ?: return emptyMap()
        
        return try {
            val type = object : TypeToken<Map<String, ProjectedReading>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing projections", e)
            emptyMap()
        }
    }
    
    /**
     * Update the current month's projection using latest scan data.
     * Called automatically when a new scan is saved.
     */
    fun updateCurrentMonthProjection(): ProjectedReading? {
        val projection = predictor.predictCurrentMonthEndReading()
        
        if (projection != null) {
            saveProjection(projection)
            prefs.edit().putLong(KEY_LAST_AUTO_UPDATE, System.currentTimeMillis()).apply()
        }
        
        return projection
    }
    
    /**
     * Mark the current month's projection with a user's actual scan reading.
     * Called when user scans on the last day of the month.
     */
    fun recordActualMonthEndReading(actualReading: Int, timestamp: Long = System.currentTimeMillis()) {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val monthYear = String.format("%04d-%02d", year, month)
        
        // Check if this is the last day of the month
        val isLastDay = calendar.get(Calendar.DAY_OF_MONTH) == 
            calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        
        val existing = getProjection(year, month)
        
        val projection = if (isLastDay) {
            // This is an actual last-day reading, very high confidence!
            ProjectedReading(
                monthYear = monthYear,
                projectedReading = actualReading.toDouble(),
                lastActualReading = actualReading,
                lastActualTimestamp = timestamp,
                confidenceScore = 1.0,
                dailyUsageRate = existing?.dailyUsageRate ?: 0.0,
                dataPointsUsed = (existing?.dataPointsUsed ?: 0) + 1,
                rSquared = existing?.rSquared ?: 1.0,
                source = ProjectionSource.MANUAL_SCAN,
                userVerified = true,
                userActualReading = actualReading
            )
        } else {
            // Not last day - update the projection with this new data point
            predictor.predictMonthEndReading(year, month)
        }
        
        if (projection != null) {
            saveProjection(projection, forceReplace = isLastDay)
        }
        
        Log.d(TAG, "Recorded ${if (isLastDay) "actual last-day" else "mid-month"} reading: $actualReading")
    }
    
    /**
     * Get the "app-predicted previous reading" for bill calculation.
     * This is the projected reading from the previous month.
     */
    fun getAppPredictedPreviousReading(): Int? {
        val previousProjection = getPreviousMonthProjection()
        return previousProjection?.getEffectiveReading()
    }
    
    /**
     * Verify/confirm a projection manually by the user.
     */
    fun verifyProjection(year: Int, month: Int, verifiedReading: Int? = null) {
        val existing = getProjection(year, month) ?: return
        
        val verified = existing.copy(
            userVerified = true,
            userActualReading = verifiedReading ?: existing.projectedReading.toInt()
        )
        
        saveProjection(verified, forceReplace = true)
        Log.d(TAG, "User verified projection for ${existing.monthYear}")
    }
    
    /**
     * Clear a specific month's projection.
     */
    fun clearProjection(year: Int, month: Int) {
        val monthYear = String.format("%04d-%02d", year, month)
        val projections = getAllProjections().toMutableMap()
        projections.remove(monthYear)
        saveProjections(projections)
    }
    
    /**
     * Clear all projections.
     */
    fun clearAllProjections() {
        prefs.edit().remove(KEY_PROJECTIONS).apply()
    }

    fun replaceAllProjections(projections: Map<String, ProjectedReading>) {
        saveProjections(projections)
    }
    
    /**
     * Get statistics about projection accuracy.
     * Compares predictions with actual readings when both are available.
     */
    fun getProjectionAccuracyStats(): ProjectionAccuracyStats {
        val projections = getAllProjections().values
            .filter { it.userActualReading != null && it.source == ProjectionSource.REGRESSION }
        
        if (projections.isEmpty()) {
            return ProjectionAccuracyStats(
                sampleCount = 0,
                meanAbsoluteError = 0.0,
                meanPercentageError = 0.0,
                maxError = 0,
                minError = 0
            )
        }
        
        val errors = projections.map { projection ->
            kotlin.math.abs(projection.projectedReading - (projection.userActualReading?.toDouble() ?: projection.projectedReading))
        }
        
        val percentageErrors = projections.map { projection ->
            val actual = projection.userActualReading?.toDouble() ?: projection.projectedReading
            if (actual > 0) {
                kotlin.math.abs(projection.projectedReading - actual) / actual * 100
            } else {
                0.0
            }
        }
        
        return ProjectionAccuracyStats(
            sampleCount = projections.size,
            meanAbsoluteError = errors.average(),
            meanPercentageError = percentageErrors.average(),
            maxError = errors.maxOrNull()?.toInt() ?: 0,
            minError = errors.minOrNull()?.toInt() ?: 0
        )
    }
    
    private fun saveProjections(projections: Map<String, ProjectedReading>) {
        val json = gson.toJson(projections)
        prefs.edit().putString(KEY_PROJECTIONS, json).apply()
    }
}

/**
 * Statistics about projection accuracy.
 */
data class ProjectionAccuracyStats(
    val sampleCount: Int,
    val meanAbsoluteError: Double,
    val meanPercentageError: Double,
    val maxError: Int,
    val minError: Int
) {
    fun hasEnoughData(): Boolean = sampleCount >= 3
    
    fun getAccuracyGrade(): String {
        return when {
            sampleCount < 3 -> "Insufficient data"
            meanPercentageError <= 2.0 -> "Excellent"
            meanPercentageError <= 5.0 -> "Good"
            meanPercentageError <= 10.0 -> "Fair"
            else -> "Needs improvement"
        }
    }
}
