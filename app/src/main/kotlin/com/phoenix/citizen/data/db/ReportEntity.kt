package com.phoenix.citizen.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local row representing one citizen report.
 *
 * Lifecycle:
 *  - status = QUEUED: not yet submitted to backend
 *  - status = SYNCED: server accepted; reportId set
 *  - status = FAILED: server rejected or network errored after retries
 */
@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val deviceHash: String,
    val lat: Double,
    val lon: Double,
    /** Horizontal GPS accuracy radius in metres at capture; null if unknown. */
    val accuracyM: Float? = null,
    /** Provenance of lat/lon — see [com.phoenix.citizen.data.model.LocationSource]. */
    val locationSource: String? = null,
    val tsUtc: String,
    val observationType: String,
    val windDirection: String? = null,
    val photoPath: String? = null,
    val note: String? = null,
    val status: String = STATUS_QUEUED,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val reportId: String? = null,
    val corroboratedAt: String? = null,
    val corroboratingSources: String? = null, // CSV
    val witnessCount: Int = 0
) {
    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_SYNCED = "SYNCED"
        const val STATUS_FAILED = "FAILED"
    }
}
