package com.kokeshi.metersnap.utils

import com.kokeshi.metersnap.data.RemoteServiceType
import com.kokeshi.metersnap.data.RemoteTier
import com.kokeshi.metersnap.data.TierRate
import com.kokeshi.metersnap.data.WaterRateType
import com.kokeshi.metersnap.data.WaterRates

object BillCalculator {

    /**
     * Bill calculation result containing itemized breakdown.
     * 
     * Note: tier1-5 fields are kept for backward compatibility with existing UI.
     * For dynamic tier support, use tierBreakdown list instead.
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
        val tier5Amount: Double,
        // Dynamic tier breakdown for future UI that supports any number of tiers
        val tierBreakdown: List<TierBreakdownItem> = emptyList()
    )
    
    /**
     * Single tier breakdown item for dynamic rendering
     */
    data class TierBreakdownItem(
        val tierIndex: Int,
        val label: String,
        val usageInTier: Int,
        val amount: Double,
        val isMinimumCharge: Boolean
    )

    /**
     * Calculate water bill using RemoteServiceType (new flexible format).
     * Supports any number of tiers and respects is_minimum_charge flag.
     * 
     * Math Logic:
     * - If is_minimum_charge = true: tier_cost = rate (fixed amount, regardless of usage in tier)
     * - If is_minimum_charge = false: tier_cost = usage_in_tier × rate
     * - ESF = total_usage × esf_rate
     * - Total = water_bill + ESF
     */
    fun calculateBillDynamic(
        usage: Int,
        serviceType: RemoteServiceType,
        esfRate: Double
    ): BillResult {
        var remainingUsage = usage
        var waterBill = 0.0
        val breakdownList = mutableListOf<TierBreakdownItem>()
        
        // Legacy tier tracking (for backward compatibility with existing UI)
        val legacyUsage = IntArray(5)
        val legacyAmount = DoubleArray(5)

        // Process each tier dynamically
        for ((index, tier) in serviceType.tiers.withIndex()) {
            if (remainingUsage <= 0) break

            // Calculate tier capacity
            val tierCapacity = if (tier.max == null) {
                // Open-ended tier (e.g., 41+)
                remainingUsage
            } else {
                tier.max - tier.min + 1
            }
            
            val usageInTier = minOf(remainingUsage, tierCapacity)

            // Calculate cost based on tier type
            val tierCost = if (tier.isMinimumCharge) {
                // Minimum charge: fixed amount for the entire tier
                tier.rate
            } else {
                // Per-unit: usage × rate
                usageInTier * tier.rate
            }

            waterBill += tierCost
            
            // Add to dynamic breakdown
            breakdownList.add(
                TierBreakdownItem(
                    tierIndex = index,
                    label = tier.getLabelWithType(),
                    usageInTier = usageInTier,
                    amount = tierCost,
                    isMinimumCharge = tier.isMinimumCharge
                )
            )

            // Legacy tier tracking (first 5 tiers only)
            if (index < 5) {
                legacyUsage[index] = usageInTier
                legacyAmount[index] = tierCost
            }

            remainingUsage -= usageInTier
        }

        // Calculate ESF
        val esfFee = usage * esfRate

        // Total Bill
        val totalBill = waterBill + esfFee

        return BillResult(
            waterBill = waterBill,
            esfFee = esfFee,
            totalBill = totalBill,
            tier1Usage = legacyUsage[0],
            tier1Amount = legacyAmount[0],
            tier2Usage = legacyUsage[1],
            tier2Amount = legacyAmount[1],
            tier3Usage = legacyUsage[2],
            tier3Amount = legacyAmount[2],
            tier4Usage = legacyUsage[3],
            tier4Amount = legacyAmount[3],
            tier5Usage = legacyUsage[4],
            tier5Amount = legacyAmount[4],
            tierBreakdown = breakdownList
        )
    }

    /**
     * Legacy calculate bill method for backward compatibility.
     * Uses the old WaterRateType enum and TierRate format.
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
