package com.kokeshi.metersnap.data

data class TierRate(
    val minCubicMeters: Int,
    val maxCubicMeters: Int,
    val ratePerCubicMeter: Double
)

object WaterRates {
    // January 2025 Rates for Carmona Water District
    // Note: First tier (1-10 m³) is a MINIMUM CHARGE, not per cubic meter rate
    
    // ESF (Environmental Service Fee) rates per type
    const val RESIDENTIAL_ESF = 3.50
    const val COMMERCIAL_ESF = 3.50
    const val GOVERNMENT_ESF = 3.50
    const val SEMI_COMMERCIAL_A_ESF = 3.50
    const val SEMI_COMMERCIAL_B_ESF = 3.50
    const val SEMI_COMMERCIAL_C_ESF = 3.50
    
    val RESIDENTIAL_RATES = listOf(
        TierRate(1, 10, 238.60),    // Minimum Charge for first 10 m³
        TierRate(11, 20, 26.20),    // Per m³ rate for 11-20
        TierRate(21, 30, 29.00),    // Per m³ rate for 21-30
        TierRate(31, 40, 32.60),    // Per m³ rate for 31-40
        TierRate(41, Int.MAX_VALUE, 37.00)  // Per m³ rate for 41+
    )

    val COMMERCIAL_RATES = listOf(
        TierRate(1, 10, 477.20),    // Minimum Charge for first 10 m³
        TierRate(11, 20, 52.40),    // Per m³ rate for 11-20
        TierRate(21, 30, 58.00),    // Per m³ rate for 21-30
        TierRate(31, 40, 65.20),    // Per m³ rate for 31-40
        TierRate(41, Int.MAX_VALUE, 74.00)  // Per m³ rate for 41+
    )

    // Government rates are same as Residential
    val GOVERNMENT_RATES = listOf(
        TierRate(1, 10, 238.60),    // Minimum Charge for first 10 m³
        TierRate(11, 20, 26.20),    // Per m³ rate for 11-20
        TierRate(21, 30, 29.00),    // Per m³ rate for 21-30
        TierRate(31, 40, 32.60),    // Per m³ rate for 31-40
        TierRate(41, Int.MAX_VALUE, 37.00)  // Per m³ rate for 41+
    )

    val SEMI_COMMERCIAL_A_RATES = listOf(
        TierRate(1, 10, 417.55),    // Minimum Charge for first 10 m³
        TierRate(11, 20, 45.85),    // Per m³ rate for 11-20
        TierRate(21, 30, 50.75),    // Per m³ rate for 21-30
        TierRate(31, 40, 57.05),    // Per m³ rate for 31-40
        TierRate(41, Int.MAX_VALUE, 64.75)  // Per m³ rate for 41+
    )

    val SEMI_COMMERCIAL_B_RATES = listOf(
        TierRate(1, 10, 357.90),    // Minimum Charge for first 10 m³
        TierRate(11, 20, 39.30),    // Per m³ rate for 11-20
        TierRate(21, 30, 43.50),    // Per m³ rate for 21-30
        TierRate(31, 40, 48.90),    // Per m³ rate for 31-40
        TierRate(41, Int.MAX_VALUE, 55.50)  // Per m³ rate for 41+
    )

    val SEMI_COMMERCIAL_C_RATES = listOf(
        TierRate(1, 10, 298.25),    // Minimum Charge for first 10 m³
        TierRate(11, 20, 32.75),    // Per m³ rate for 11-20
        TierRate(21, 30, 36.25),    // Per m³ rate for 21-30
        TierRate(31, 40, 40.75),    // Per m³ rate for 31-40
        TierRate(41, Int.MAX_VALUE, 46.25)  // Per m³ rate for 41+
    )

    fun getRatesForType(type: WaterRateType): List<TierRate> {
        return when (type) {
            WaterRateType.RESIDENTIAL -> RESIDENTIAL_RATES
            WaterRateType.COMMERCIAL -> COMMERCIAL_RATES
            WaterRateType.GOVERNMENT -> GOVERNMENT_RATES
            WaterRateType.SEMI_COMMERCIAL_A -> SEMI_COMMERCIAL_A_RATES
            WaterRateType.SEMI_COMMERCIAL_B -> SEMI_COMMERCIAL_B_RATES
            WaterRateType.SEMI_COMMERCIAL_C -> SEMI_COMMERCIAL_C_RATES
            WaterRateType.CUSTOM -> emptyList() // Will be loaded from preferences
        }
    }
    
    fun getESFForType(type: WaterRateType): Double {
        return when (type) {
            WaterRateType.RESIDENTIAL -> RESIDENTIAL_ESF
            WaterRateType.COMMERCIAL -> COMMERCIAL_ESF
            WaterRateType.GOVERNMENT -> GOVERNMENT_ESF
            WaterRateType.SEMI_COMMERCIAL_A -> SEMI_COMMERCIAL_A_ESF
            WaterRateType.SEMI_COMMERCIAL_B -> SEMI_COMMERCIAL_B_ESF
            WaterRateType.SEMI_COMMERCIAL_C -> SEMI_COMMERCIAL_C_ESF
            WaterRateType.CUSTOM -> 3.50 // Default for custom
        }
    }
}

