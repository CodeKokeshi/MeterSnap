package com.kokeshi.metersnap.utils

import com.kokeshi.metersnap.data.TierRate
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.data.WaterRates

object BillCalculator {

    /**
     * Bill calculation result containing itemized breakdown
     */
    data class BillResult(
        val waterBill: Double,
        val esfFee: Double,
        val totalBill: Double,
        val tier1Usage: Int,
        val tier1Amount: Double,
        val tier2Usage: Int,
        val tier2Amount: Double,
        val tier3Usage: Int,
        val tier3Amount: Double,
        val tier4Usage: Int,
        val tier4Amount: Double,
        val tier5Usage: Int,
        val tier5Amount: Double
    )

    /**
     * Calculate water bill based on usage and rate type
     *
     * Calculation Logic (from Development.md):
     * - 1-10 cu.m.: Minimum charge (fixed rate for entire range)
     * - 11-20 cu.m.: Rate per cu.m. × usage in this tier
     * - 21-30 cu.m.: Rate per cu.m. × usage in this tier
     * - 31-40 cu.m.: Rate per cu.m. × usage in this tier
     * - 41+ cu.m.: Rate per cu.m. × usage in this tier
     *
     * ESF = Total Usage × ESF Rate
     * Total Bill = Water Bill + ESF
     */
    fun calculateBill(
        usage: Int,
        rateType: WaterRateType,
        customRates: List<TierRate>,
        esfRate: Double
    ): BillResult {
        // Get applicable rates
        val rates = when (rateType) {
            WaterRateType.RESIDENTIAL -> WaterRates.RESIDENTIAL_RATES
            WaterRateType.COMMERCIAL -> WaterRates.COMMERCIAL_RATES
            WaterRateType.GOVERNMENT -> WaterRates.GOVERNMENT_RATES
            WaterRateType.SEMI_COMMERCIAL_A -> WaterRates.SEMI_COMMERCIAL_A_RATES
            WaterRateType.SEMI_COMMERCIAL_B -> WaterRates.SEMI_COMMERCIAL_B_RATES
            WaterRateType.SEMI_COMMERCIAL_C -> WaterRates.SEMI_COMMERCIAL_C_RATES
            WaterRateType.CUSTOM -> customRates
        }

        var remainingUsage = usage
        var waterBill = 0.0

        // Initialize tier tracking
        var tier1Usage = 0
        var tier1Amount = 0.0
        var tier2Usage = 0
        var tier2Amount = 0.0
        var tier3Usage = 0
        var tier3Amount = 0.0
        var tier4Usage = 0
        var tier4Amount = 0.0
        var tier5Usage = 0
        var tier5Amount = 0.0

        // Process each tier
        for ((index, tier) in rates.withIndex()) {
            if (remainingUsage <= 0) break

            val tierSize = tier.maxCubicMeters - tier.minCubicMeters + 1
            val usageInTier = if (tier.maxCubicMeters == Int.MAX_VALUE) {
                // Last tier (41+)
                remainingUsage
            } else {
                minOf(remainingUsage, tierSize)
            }

            val tierCost = if (tier.minCubicMeters == 1) {
                // First tier (1-10) is minimum charge (fixed)
                tier.ratePerCubicMeter
            } else {
                // Other tiers are per cu.m.
                usageInTier * tier.ratePerCubicMeter
            }

            waterBill += tierCost

            // Track tier breakdown
            when (index) {
                0 -> {
                    tier1Usage = usageInTier
                    tier1Amount = tierCost
                }
                1 -> {
                    tier2Usage = usageInTier
                    tier2Amount = tierCost
                }
                2 -> {
                    tier3Usage = usageInTier
                    tier3Amount = tierCost
                }
                3 -> {
                    tier4Usage = usageInTier
                    tier4Amount = tierCost
                }
                4 -> {
                    tier5Usage = usageInTier
                    tier5Amount = tierCost
                }
            }

            remainingUsage -= usageInTier
        }

        // Calculate ESF: Total Usage × ESF Rate
        val esfFee = usage * esfRate

        // Total Bill = Water Bill + ESF
        val totalBill = waterBill + esfFee

        return BillResult(
            waterBill = waterBill,
            esfFee = esfFee,
            totalBill = totalBill,
            tier1Usage = tier1Usage,
            tier1Amount = tier1Amount,
            tier2Usage = tier2Usage,
            tier2Amount = tier2Amount,
            tier3Usage = tier3Usage,
            tier3Amount = tier3Amount,
            tier4Usage = tier4Usage,
            tier4Amount = tier4Amount,
            tier5Usage = tier5Usage,
            tier5Amount = tier5Amount
        )
    }
}
