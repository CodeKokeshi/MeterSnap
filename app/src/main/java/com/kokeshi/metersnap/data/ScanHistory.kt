package com.kokeshi.metersnap.data

import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a single scan history entry
 */
data class ScanHistoryEntry(
    val id: String,
    val timestamp: Long,
    val currentReading: Int,
    val previousReading: Int,
    val usage: Int,
    val rateType: String,
    val waterBill: Double,
    val esfFee: Double,
    val totalBill: Double,
    // Tier details
    val tier1Usage: Int = 0,
    val tier1Amount: Double = 0.0,
    val tier2Usage: Int = 0,
    val tier2Amount: Double = 0.0,
    val tier3Usage: Int = 0,
    val tier3Amount: Double = 0.0,
    val tier4Usage: Int = 0,
    val tier4Amount: Double = 0.0,
    val tier5Usage: Int = 0,
    val tier5Amount: Double = 0.0
) {
    fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    fun getFormattedTotalBill(): String {
        return String.format("₱%.2f", totalBill)
    }
}
