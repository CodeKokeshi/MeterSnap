package com.kokeshi.metersnap.data

import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a single scan history entry.
 * Can contain both CWD-based and App-predicted calculations when comparison was available.
 */
data class ScanHistoryEntry(
    val id: String,
    val timestamp: Long,
    val currentReading: Int,
    
    // CWD-based calculation (always present)
    val previousReading: Int,
    val usage: Int,
    val rateType: String,
    val waterBill: Double,
    val esfFee: Double,
    val totalBill: Double,
    // Tier details for CWD calculation
    val tier1Usage: Int = 0,
    val tier1Amount: Double = 0.0,
    val tier2Usage: Int = 0,
    val tier2Amount: Double = 0.0,
    val tier3Usage: Int = 0,
    val tier3Amount: Double = 0.0,
    val tier4Usage: Int = 0,
    val tier4Amount: Double = 0.0,
    val tier5Usage: Int = 0,
    val tier5Amount: Double = 0.0,
    
    // App-predicted calculation (optional - only when comparison was available)
    val appPreviousReading: Int? = null,
    val appUsage: Int? = null,
    val appWaterBill: Double? = null,
    val appEsfFee: Double? = null,
    val appTotalBill: Double? = null,
    // Tier details for App calculation
    val appTier1Usage: Int? = null,
    val appTier1Amount: Double? = null,
    val appTier2Usage: Int? = null,
    val appTier2Amount: Double? = null,
    val appTier3Usage: Int? = null,
    val appTier3Amount: Double? = null,
    val appTier4Usage: Int? = null,
    val appTier4Amount: Double? = null,
    val appTier5Usage: Int? = null,
    val appTier5Amount: Double? = null,
    
    // Whether this entry has a comparison (both CWD and App calculations)
    val hasComparison: Boolean = false
) {
    fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    fun getFormattedTotalBill(): String {
        return String.format("₱%.2f", totalBill)
    }
    
    fun getFormattedAppTotalBill(): String? {
        return appTotalBill?.let { String.format("₱%.2f", it) }
    }
}
