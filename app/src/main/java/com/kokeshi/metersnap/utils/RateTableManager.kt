package com.kokeshi.metersnap.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.kokeshi.metersnap.data.RemoteRateConfig
import com.kokeshi.metersnap.data.RemoteServiceType
import com.kokeshi.metersnap.data.RemoteTier
import com.kokeshi.metersnap.data.TierRate
import com.kokeshi.metersnap.data.toTierRates
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Manages fetching, caching, and providing rate tables.
 * 
 * Supports:
 * - Fetching from remote GitHub Gist
 * - Local caching of fetched config
 * - Fallback to hardcoded defaults
 * - Dynamic tier count and boundaries
 * - Dynamic service type count
 */
class RateTableManager(private val context: Context) {
    
    companion object {
        private const val TAG = "RateTableManager"
        
        // GitHub Gist raw URL for the rate config
        const val GIST_RAW_URL = "https://gist.githubusercontent.com/CodeKokeshi/47f94dab0f7ba3610fc5dc58574cfd2c/raw/cwd_rates_and_maths.json"
        
        private const val PREFS_NAME = "rate_table_cache"
        private const val KEY_CACHED_CONFIG = "cached_config_json"
        private const val KEY_CACHED_VERSION = "cached_config_version"
        private const val KEY_LAST_FETCH_TIME = "last_fetch_timestamp"
        private const val KEY_TABLE_LABEL = "table_label"
        
        // Default hardcoded config version
        const val DEFAULT_CONFIG_VERSION = 2025.0
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()
    
    /**
     * Result wrapper for fetch operations
     */
    sealed class FetchResult {
        data class Success(val config: RemoteRateConfig, val isNew: Boolean) : FetchResult()
        data class Error(val message: String) : FetchResult()
    }
    
    /**
     * Fetch the latest rate table from the remote Gist.
     * This is a blocking call - run on background thread.
     */
    fun fetchRemoteConfig(): FetchResult {
        return try {
            Log.d(TAG, "Fetching remote config from: $GIST_RAW_URL")
            
            val url = URL(GIST_RAW_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")
            
            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                connection.disconnect()
                
                Log.d(TAG, "Raw response length: ${response.length}")
                
                val config = gson.fromJson(response, RemoteRateConfig::class.java)
                
                if (config != null && config.serviceTypes.isNotEmpty()) {
                    // Check if this is a new version
                    val cachedVersion = getCachedConfigVersion()
                    val isNew = config.configVersion > cachedVersion
                    
                    // Cache the config
                    cacheConfig(config, response)
                    
                    Log.d(TAG, "Successfully parsed config version ${config.configVersion}, isNew: $isNew")
                    FetchResult.Success(config, isNew)
                } else {
                    FetchResult.Error("Invalid config format: empty or null")
                }
            } else {
                connection.disconnect()
                FetchResult.Error("Server returned error code: $responseCode")
            }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "JSON parsing error", e)
            FetchResult.Error("Failed to parse rate table: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Fetch error", e)
            FetchResult.Error("Network error: ${e.message}")
        }
    }
    
    /**
     * Fetch asynchronously with callback
     */
    fun fetchRemoteConfigAsync(callback: (FetchResult) -> Unit) {
        executor.execute {
            val result = fetchRemoteConfig()
            // Run callback on main thread would require Handler, 
            // but caller can handle this
            callback(result)
        }
    }
    
    /**
     * Get the cached remote config, or null if none exists
     */
    fun getCachedConfig(): RemoteRateConfig? {
        val json = prefs.getString(KEY_CACHED_CONFIG, null) ?: return null
        return try {
            gson.fromJson(json, RemoteRateConfig::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cached config", e)
            null
        }
    }
    
    /**
     * Get the currently active config (cached remote or default hardcoded)
     */
    fun getActiveConfig(): RemoteRateConfig {
        return getCachedConfig() ?: getDefaultConfig()
    }
    
    /**
     * Get the cached config version number
     */
    fun getCachedConfigVersion(): Double {
        return prefs.getFloat(KEY_CACHED_VERSION, 0f).toDouble()
    }
    
    /**
     * Get the table label (e.g., "January 2025 - Carmona Water District")
     */
    fun getTableLabel(): String {
        return prefs.getString(KEY_TABLE_LABEL, null) 
            ?: getDefaultConfig().tableLabel
    }
    
    /**
     * Get the last fetch timestamp
     */
    fun getLastFetchTime(): Long {
        return prefs.getLong(KEY_LAST_FETCH_TIME, 0)
    }
    
    /**
     * Check if we have a cached remote config
     */
    fun hasCachedConfig(): Boolean {
        return prefs.contains(KEY_CACHED_CONFIG)
    }
    
    /**
     * Clear the cached config (revert to defaults)
     */
    fun clearCache() {
        prefs.edit {
            remove(KEY_CACHED_CONFIG)
            remove(KEY_CACHED_VERSION)
            remove(KEY_LAST_FETCH_TIME)
            remove(KEY_TABLE_LABEL)
        }
    }
    
    /**
     * Cache the config locally
     */
    private fun cacheConfig(config: RemoteRateConfig, rawJson: String) {
        prefs.edit {
            putString(KEY_CACHED_CONFIG, rawJson)
            putFloat(KEY_CACHED_VERSION, config.configVersion.toFloat())
            putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
            putString(KEY_TABLE_LABEL, config.tableLabel)
        }
    }
    
    /**
     * Get service types from active config
     */
    fun getServiceTypes(): List<RemoteServiceType> {
        return getActiveConfig().serviceTypes
    }
    
    /**
     * Get a specific service type by ID
     */
    fun getServiceType(typeId: String): RemoteServiceType? {
        return getActiveConfig().serviceTypes.find { it.typeId == typeId }
    }
    
    /**
     * Get tiers for a service type as TierRate list (for compatibility with existing code)
     */
    fun getTierRates(typeId: String): List<TierRate> {
        return getServiceType(typeId)?.toTierRates() ?: emptyList()
    }
    
    /**
     * Get the ESF rate from active config
     */
    fun getEsfRate(): Double {
        return getActiveConfig().esfRate
    }
    
    /**
     * Get all service type display names for spinner
     */
    fun getServiceTypeDisplayNames(): List<String> {
        return getActiveConfig().serviceTypes.map { it.displayName }
    }
    
    /**
     * Get service type ID at index (for spinner selection)
     */
    fun getServiceTypeIdAtIndex(index: Int): String? {
        val types = getActiveConfig().serviceTypes
        return if (index in types.indices) types[index].typeId else null
    }
    
    /**
     * Get index for a service type ID
     */
    fun getIndexForServiceTypeId(typeId: String): Int {
        return getActiveConfig().serviceTypes.indexOfFirst { it.typeId == typeId }
    }
    
    /**
     * Default hardcoded config matching the original WaterRates.kt values.
     * This serves as fallback when no remote config is cached.
     */
    fun getDefaultConfig(): RemoteRateConfig {
        return RemoteRateConfig(
            configVersion = DEFAULT_CONFIG_VERSION,
            tableLabel = "January 2025 - Carmona Water District",
            effectiveDate = "2025-01-01",
            esfRate = 3.5,
            serviceTypes = listOf(
                RemoteServiceType(
                    typeId = "RESIDENTIAL",
                    displayName = "Residential",
                    tiers = listOf(
                        RemoteTier(1, 10, true, 238.60),
                        RemoteTier(11, 20, false, 26.20),
                        RemoteTier(21, 30, false, 29.00),
                        RemoteTier(31, 40, false, 32.60),
                        RemoteTier(41, null, false, 37.00)
                    )
                ),
                RemoteServiceType(
                    typeId = "COMMERCIAL",
                    displayName = "Commercial/Industrial",
                    tiers = listOf(
                        RemoteTier(1, 10, true, 477.20),
                        RemoteTier(11, 20, false, 52.40),
                        RemoteTier(21, 30, false, 58.00),
                        RemoteTier(31, 40, false, 65.20),
                        RemoteTier(41, null, false, 74.00)
                    )
                ),
                RemoteServiceType(
                    typeId = "GOVERNMENT",
                    displayName = "Government",
                    tiers = listOf(
                        RemoteTier(1, 10, true, 238.60),
                        RemoteTier(11, 20, false, 26.20),
                        RemoteTier(21, 30, false, 29.00),
                        RemoteTier(31, 40, false, 32.60),
                        RemoteTier(41, null, false, 37.00)
                    )
                ),
                RemoteServiceType(
                    typeId = "SEMI_COMMERCIAL_A",
                    displayName = "Semi-Commercial A",
                    tiers = listOf(
                        RemoteTier(1, 10, true, 417.55),
                        RemoteTier(11, 20, false, 45.85),
                        RemoteTier(21, 30, false, 50.75),
                        RemoteTier(31, 40, false, 57.05),
                        RemoteTier(41, null, false, 64.75)
                    )
                ),
                RemoteServiceType(
                    typeId = "SEMI_COMMERCIAL_B",
                    displayName = "Semi-Commercial B",
                    tiers = listOf(
                        RemoteTier(1, 10, true, 357.90),
                        RemoteTier(11, 20, false, 39.30),
                        RemoteTier(21, 30, false, 43.50),
                        RemoteTier(31, 40, false, 48.90),
                        RemoteTier(41, null, false, 55.50)
                    )
                ),
                RemoteServiceType(
                    typeId = "SEMI_COMMERCIAL_C",
                    displayName = "Semi-Commercial C",
                    tiers = listOf(
                        RemoteTier(1, 10, true, 298.25),
                        RemoteTier(11, 20, false, 32.75),
                        RemoteTier(21, 30, false, 36.25),
                        RemoteTier(31, 40, false, 40.75),
                        RemoteTier(41, null, false, 46.25)
                    )
                )
            )
        )
    }
}
