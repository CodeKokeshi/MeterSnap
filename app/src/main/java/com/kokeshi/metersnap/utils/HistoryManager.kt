package com.kokeshi.metersnap.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kokeshi.metersnap.data.ScanHistoryEntry

/**
 * Manages scan history using SharedPreferences
 */
class HistoryManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val PREFS_NAME = "ScanHistoryPrefs"
        private const val KEY_HISTORY = "history_list"
        private const val MAX_HISTORY_ITEMS = 100 // Limit to prevent storage issues
    }
    
    /**
     * Add a new scan entry to history
     */
    fun addScanEntry(entry: ScanHistoryEntry) {
        val history = getAllHistory().toMutableList()
        
        // Add at the beginning (newest first)
        history.add(0, entry)
        
        // Limit history size
        if (history.size > MAX_HISTORY_ITEMS) {
            history.removeAt(history.size - 1)
        }
        
        saveHistory(history)
    }
    
    /**
     * Update an existing entry by ID, or add if not found
     */
    fun updateOrAddEntry(entry: ScanHistoryEntry) {
        val history = getAllHistory().toMutableList()
        
        // Find and remove existing entry with same ID
        val existingIndex = history.indexOfFirst { it.id == entry.id }
        
        if (existingIndex >= 0) {
            // Update existing entry (keep same position)
            history[existingIndex] = entry
        } else {
            // Add as new entry at the beginning
            history.add(0, entry)
            
            // Limit history size
            if (history.size > MAX_HISTORY_ITEMS) {
                history.removeAt(history.size - 1)
            }
        }
        
        saveHistory(history)
    }
    
    /**
     * Check if an entry with given ID exists
     */
    fun hasEntry(id: String): Boolean {
        return getAllHistory().any { it.id == id }
    }
    
    /**
     * Get all history entries (newest first)
     */
    fun getAllHistory(): List<ScanHistoryEntry> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        
        return try {
            val type = object : TypeToken<List<ScanHistoryEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get a specific history entry by ID
     */
    fun getEntryById(id: String): ScanHistoryEntry? {
        return getAllHistory().find { it.id == id }
    }
    
    /**
     * Clear all history
     */
    fun clearAllHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }
    
    /**
     * Clear history for a specific month
     * @param monthKey Format: "yyyy-MM" (e.g., "2025-01")
     */
    fun clearMonthHistory(monthKey: String) {
        val history = getAllHistory().toMutableList()
        val monthFormat = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
        
        history.removeAll { entry ->
            val calendar = java.util.Calendar.getInstance()
            calendar.timeInMillis = entry.timestamp
            monthFormat.format(calendar.time) == monthKey
        }
        
        saveHistory(history)
    }

    /**
     * Clear history for a specific billing period (using charge date).
     * @param periodKey Format: "yyyy-MM" (e.g., "2025-01") - the billing period
     * @param chargeDate The user's charge date (1-28)
     */
    fun clearBillingPeriodHistory(periodKey: String, chargeDate: Int) {
        val history = getAllHistory().toMutableList()
        
        history.removeAll { entry ->
            ChargeDateHelper.getBillingPeriodKey(entry.timestamp, chargeDate) == periodKey
        }
        
        saveHistory(history)
    }
    
    /**
     * Delete a specific entry
     */
    fun deleteEntry(id: String) {
        val history = getAllHistory().toMutableList()
        history.removeAll { it.id == id }
        saveHistory(history)
    }
    
    private fun saveHistory(history: List<ScanHistoryEntry>) {
        val json = gson.toJson(history)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }
}
