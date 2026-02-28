package com.kokeshi.metersnap.data

/**
 * Snapshot of a full working session that can be restored later.
 */
data class SessionArchive(
    val id: String,
    val createdAt: Long,
    val label: String,
    val previousReadingUsed: Int,
    val lastCurrentReadingUsed: Int,
    val historyEntries: List<ScanHistoryEntry>,
    val projectionsByMonth: Map<String, ProjectedReading>
)
