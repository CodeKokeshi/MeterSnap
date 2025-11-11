package com.kokeshi.metersnap.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.kokeshi.metersnap.data.TierRate
import com.kokeshi.metersnap.data.WaterRateType

class RatePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("water_rates", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RATE_TYPE = "rate_type"
        private const val KEY_ESF_RATE = "esf_rate"
        private const val KEY_CUSTOM_TIER_PREFIX = "custom_tier_"
        private const val DEFAULT_ESF_RATE = 3.50  // ESF rate as per Carmona Water District
    }

    fun saveRateType(type: WaterRateType) {
        prefs.edit {
            putString(KEY_RATE_TYPE, type.name)
        }
    }

    fun getRateType(): WaterRateType {
        val typeName = prefs.getString(KEY_RATE_TYPE, WaterRateType.RESIDENTIAL.name)
        return try {
            WaterRateType.valueOf(typeName ?: WaterRateType.RESIDENTIAL.name)
        } catch (_: IllegalArgumentException) {
            WaterRateType.RESIDENTIAL
        }
    }

    fun saveESFRate(rate: Double) {
        prefs.edit {
            putFloat(KEY_ESF_RATE, rate.toFloat())
        }
    }

    fun getESFRate(): Double {
        return prefs.getFloat(KEY_ESF_RATE, DEFAULT_ESF_RATE.toFloat()).toDouble()
    }

    fun saveCustomRates(rates: List<TierRate>) {
        prefs.edit {
            // Clear existing custom rates
            val allKeys = prefs.all.keys.filter { it.startsWith(KEY_CUSTOM_TIER_PREFIX) }
            allKeys.forEach { remove(it) }

            // Save new rates
            rates.forEachIndexed { index, rate ->
                putString("${KEY_CUSTOM_TIER_PREFIX}${index}_min", rate.minCubicMeters.toString())
                putString("${KEY_CUSTOM_TIER_PREFIX}${index}_max", rate.maxCubicMeters.toString())
                putFloat("${KEY_CUSTOM_TIER_PREFIX}${index}_rate", rate.ratePerCubicMeter.toFloat())
            }
            putInt("${KEY_CUSTOM_TIER_PREFIX}count", rates.size)
        }
    }

    fun getCustomRates(): List<TierRate> {
        val count = prefs.getInt("${KEY_CUSTOM_TIER_PREFIX}count", 0)
        if (count == 0) return emptyList()

        val rates = mutableListOf<TierRate>()
        for (i in 0 until count) {
            val min = prefs.getString("${KEY_CUSTOM_TIER_PREFIX}${i}_min", "0")?.toIntOrNull() ?: 0
            val max = prefs.getString("${KEY_CUSTOM_TIER_PREFIX}${i}_max", "0")?.toIntOrNull() ?: 0
            val rate = prefs.getFloat("${KEY_CUSTOM_TIER_PREFIX}${i}_rate", 0f).toDouble()
            rates.add(TierRate(min, max, rate))
        }
        return rates
    }
}

