package com.kokeshi.metersnap.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kokeshi.metersnap.data.ProjectedReading
import com.kokeshi.metersnap.data.ScanHistoryEntry
import com.kokeshi.metersnap.data.SessionArchive
import java.util.UUID

class SessionLogManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "SessionLogsPrefs"
        private const val KEY_SESSION_ARCHIVES = "session_archives"
        private const val METER_PREFS = "MeterSnapPrefs"
        private const val KEY_PREVIOUS_READING = "previous_reading"
        private const val KEY_PREVIOUS_READING_SET = "previous_reading_set"
        private const val MAX_ARCHIVES = 50
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getAllArchives(): List<SessionArchive> {
        val json = prefs.getString(KEY_SESSION_ARCHIVES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SessionArchive>>() {}.type
            gson.fromJson<List<SessionArchive>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getArchiveById(id: String): SessionArchive? {
        return getAllArchives().find { it.id == id }
    }

    fun archiveCurrentSession(reason: String): SessionArchive? {
        val historyManager = HistoryManager(context)
        val projectedReadingManager = ProjectedReadingManager(context)
        val history = historyManager.getAllHistory()
        val projections = projectedReadingManager.getAllProjections()

        if (history.isEmpty() && projections.isEmpty()) {
            return null
        }

        val meterPrefs = context.getSharedPreferences(METER_PREFS, Context.MODE_PRIVATE)
        val previousReading = meterPrefs.getInt(KEY_PREVIOUS_READING, -1)
        val fallbackPrev = history.firstOrNull()?.previousReading ?: 0
        val previousReadingUsed = if (previousReading >= 0) previousReading else fallbackPrev
        val lastCurrentReading = history.maxByOrNull { it.timestamp }?.currentReading ?: previousReadingUsed

        val createdAt = System.currentTimeMillis()
        val dateStr = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(createdAt))
        val archive = SessionArchive(
            id = UUID.randomUUID().toString(),
            createdAt = createdAt,
            label = "Prev: $previousReadingUsed • $dateStr",
            previousReadingUsed = previousReadingUsed,
            lastCurrentReadingUsed = lastCurrentReading,
            historyEntries = history,
            projectionsByMonth = projections
        )

        val all = getAllArchives().toMutableList()
        all.add(0, archive)
        if (all.size > MAX_ARCHIVES) {
            all.subList(MAX_ARCHIVES, all.size).clear()
        }
        saveArchives(all)

        return archive
    }

    /**
     * Restores selected archive and archives current session first (if not empty).
     */
    fun restoreArchive(archiveId: String): Boolean {
        val target = getArchiveById(archiveId) ?: return false

        archiveCurrentSession(reason = "backup_before_restore")

        val historyManager = HistoryManager(context)
        val projectedReadingManager = ProjectedReadingManager(context)
        historyManager.replaceAllHistory(target.historyEntries)
        projectedReadingManager.replaceAllProjections(target.projectionsByMonth)

        val meterPrefs = context.getSharedPreferences(METER_PREFS, Context.MODE_PRIVATE)
        meterPrefs.edit()
            .putInt(KEY_PREVIOUS_READING, target.previousReadingUsed)
            .putBoolean(KEY_PREVIOUS_READING_SET, true)
            .apply()

        return true
    }

    private fun saveArchives(archives: List<SessionArchive>) {
        prefs.edit().putString(KEY_SESSION_ARCHIVES, gson.toJson(archives)).apply()
    }
}
