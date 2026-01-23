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
