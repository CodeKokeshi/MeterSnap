package com.kokeshi.metersnap.data

import com.google.gson.annotations.SerializedName

/**
 * Data classes for parsing the remote rate table JSON from GitHub Gist.
 * 
 * The JSON structure is flexible:
 * - Any number of service types can be added
 * - Any number of tiers per service type
 * - Tier boundaries (min/max) can change
 * - is_minimum_charge flag determines if tier uses fixed charge or per-unit rate
 */

data class RemoteRateConfig(
    @SerializedName("config_version")
    val configVersion: Double,
    
    @SerializedName("table_label")
    val tableLabel: String,
    
    @SerializedName("effective_date")
    val effectiveDate: String,
    
    @SerializedName("esf_rate")
    val esfRate: Double,
    
    @SerializedName("service_types")
    val serviceTypes: List<RemoteServiceType>
)

data class RemoteServiceType(
    @SerializedName("type_id")
    val typeId: String,
    
    @SerializedName("display_name")
    val displayName: String,
    
    @SerializedName("tiers")
    val tiers: List<RemoteTier>
)

data class RemoteTier(
    @SerializedName("min")
    val min: Int,
    
    @SerializedName("max")
    val max: Int?,  // null means infinity (open-ended tier)
    
    @SerializedName("is_minimum_charge")
    val isMinimumCharge: Boolean,
    
    @SerializedName("rate")
    val rate: Double
) {
    /**
     * Get the display label for this tier (e.g., "1-10", "41+")
     */
    fun getLabel(): String {
        return if (max == null) {
            "$min+"
        } else {
            "$min-$max"
        }
    }
    
    /**
     * Get the display label with charge type indicator
     */
    fun getLabelWithType(): String {
        val base = getLabel()
        return if (isMinimumCharge) {
            "$base (Min. Charge)"
        } else {
            base
        }
    }
}

/**
 * Converts RemoteServiceType to the app's internal TierRate list format.
 * This bridges the flexible remote format with the existing app logic.
 */
fun RemoteServiceType.toTierRates(): List<TierRate> {
    return tiers.map { tier ->
        TierRate(
            minCubicMeters = tier.min,
            maxCubicMeters = tier.max ?: Int.MAX_VALUE,
            ratePerCubicMeter = tier.rate
        )
    }
}

/**
 * Extension to check if a RemoteTier's pricing is minimum charge type
 */
fun RemoteTier.isFixedMinimumCharge(): Boolean = isMinimumCharge
