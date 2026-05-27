package com.kokeshi.metersnap.data

/**
 * Represents a complete bill calculation with source information.
 * Used for comparing CWD-based and App-predicted bill calculations.
 */
data class BillCalculationResult(
    /** Source of the previous reading used */
    val source: BillSource,
    
    /** Current meter reading (same for both sources) */
    val currentReading: Int,
    
    /** Previous meter reading (differs between CWD and App) */
    val previousReading: Int,
    
    /** Calculated water usage */
    val usage: Int,
    
    /** Rate type used for calculation */
    val rateType: String,
    
    /** Water bill subtotal */
    val waterBill: Double,
    
    /** ESF fee */
    val esfFee: Double,
    
    /** Total bill amount */
    val totalBill: Double,
    
    /** Tier breakdown for display */
    val tierBreakdown: List<TierBreakdownDisplay>,
    
    /** Confidence score (for App predictions only) */
    val confidenceScore: Double? = null,
    
    /** Source description for display */
    val sourceDescription: String
)

/**
 * Enum representing the source of a bill calculation
 */
enum class BillSource {
    /** Based on CWD's previous reading from user's bill */
    CWD_READING,
    
    /** Based on app's predicted previous reading */
    APP_PREDICTED,
    
    /** User manually scanned on last day of previous month */
    USER_SCANNED
}

/**
 * Single tier breakdown for display purposes
 */
data class TierBreakdownDisplay(
    val tierLabel: String,
    val usageInTier: Int,
    val amount: Double,
    val isMinimumCharge: Boolean
)

/**
 * Container for dual bill comparison
 */
data class DualBillComparison(
    /** Bill calculated using CWD's previous reading */
    val cwdBill: BillCalculationResult,
    
    /** Bill calculated using app's predicted previous reading */
    val appPredictedBill: BillCalculationResult?,
    
    /** Difference in usage between the two calculations */
    val usageDifference: Int,
    
    /** Difference in total bill amount */
    val billDifference: Double,
    
    /** Whether app prediction is available */
    val hasPrediction: Boolean
) {
    companion object {
        /**
         * Create a dual bill comparison from two calculations
         */
        fun create(
            cwdBill: BillCalculationResult,
            appBill: BillCalculationResult?
        ): DualBillComparison {
            return DualBillComparison(
                cwdBill = cwdBill,
                appPredictedBill = appBill,
                usageDifference = appBill?.let { it.usage - cwdBill.usage } ?: 0,
                billDifference = appBill?.let { it.totalBill - cwdBill.totalBill } ?: 0.0,
                hasPrediction = appBill != null
            )
        }
    }
    
    /**
     * Get a description of the difference
     */
    fun getDifferenceDescription(): String {
        if (!hasPrediction) return "No prediction available"
        
        return when {
            usageDifference == 0 -> "Both calculations match!"
            usageDifference > 0 -> "App predicts ${usageDifference} m\u00B3 more usage"
            else -> "App predicts ${-usageDifference} m\u00B3 less usage"
        }
    }
    
    /**
     * Get formatted bill difference with sign
     */
    fun getFormattedBillDifference(): String {
        val prefix = if (billDifference >= 0) "+" else ""
        return "${prefix}\u20B1${String.format("%.2f", billDifference)}"
    }
    
    /**
     * Get formatted usage difference with sign
     */
    fun getFormattedUsageDifference(): String {
        val prefix = if (usageDifference >= 0) "+" else ""
        return "${prefix}${usageDifference} m\u00B3"
    }
}

