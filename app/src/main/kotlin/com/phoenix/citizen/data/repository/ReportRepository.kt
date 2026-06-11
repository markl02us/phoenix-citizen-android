package com.phoenix.citizen.data.repository

import android.content.Context
import com.phoenix.citizen.data.api.NetworkModule
import com.phoenix.citizen.data.api.PhoenixApi
import com.phoenix.citizen.data.db.PhoenixDatabase
import com.phoenix.citizen.data.db.ReportDao
import com.phoenix.citizen.data.db.ReportEntity
import com.phoenix.citizen.data.model.CitizenReportPost
import com.phoenix.citizen.data.model.Detection
import com.phoenix.citizen.data.model.SourceHealth
import com.phoenix.citizen.util.IntegrityProvider
import kotlinx.coroutines.flow.Flow

/**
 * Central façade between UI / WorkManager and the rest of the data layer.
 *
 * Responsibilities:
 *  - Persist every report locally first (offline-first)
 *  - Attempt POST and update local row with status
 *  - Drain queue (called from [com.phoenix.citizen.worker.SyncWorker])
 *  - Fetch detections + source health
 */
class ReportRepository(
    private val context: Context,
    private val api: PhoenixApi = NetworkModule.api,
    private val dao: ReportDao = PhoenixDatabase.get(context).reportDao(),
    private val prefs: DevicePrefs = DevicePrefs(context),
    private val integrity: IntegrityProvider = IntegrityProvider(context)
) {

    fun observeReports(): Flow<List<ReportEntity>> = dao.observeAll()

    /**
     * Insert a fresh report (status=QUEUED) and immediately try to submit.
     * Returns the local row id. UI can then look up the row to display submitted state.
     */
    suspend fun submitOrQueue(
        lat: Double,
        lon: Double,
        tsUtc: String,
        observationType: String,
        windDirection: String? = null,
        photoPath: String? = null,
        note: String? = null
    ): Long {
        val deviceHash = prefs.getOrCreateDeviceHash()
        val entity = ReportEntity(
            deviceHash = deviceHash,
            lat = lat,
            lon = lon,
            tsUtc = tsUtc,
            observationType = observationType,
            windDirection = windDirection,
            photoPath = photoPath,
            note = note,
            status = ReportEntity.STATUS_QUEUED
        )
        val rowId = dao.insert(entity)
        attemptSend(rowId)
        return rowId
    }

    /** Try to ship a single queued or failed row. Updates DB on completion. */
    suspend fun attemptSend(rowId: Long): Result<Unit> {
        val row = dao.byId(rowId) ?: return Result.failure(IllegalStateException("Row $rowId missing"))
        if (row.status == ReportEntity.STATUS_SYNCED) return Result.success(Unit)

        return try {
            val token = integrity.requestTokenOrNull()
            val body = CitizenReportPost(
                deviceHash = row.deviceHash,
                lat = row.lat,
                lon = row.lon,
                tsUtc = row.tsUtc,
                observationType = row.observationType,
                windDirection = row.windDirection,
                photoPath = row.photoPath,
                note = row.note
            )
            val resp = api.submitReport(token, body)
            if (resp.isSuccessful) {
                val responseBody = resp.body()
                // Backend returns report_id as Long; Room column is String — convert at API boundary.
                dao.update(
                    row.copy(
                        status = ReportEntity.STATUS_SYNCED,
                        reportId = responseBody?.reportId?.toString(),
                        lastAttemptAt = System.currentTimeMillis(),
                        lastError = null
                    )
                )
                Result.success(Unit)
            } else {
                dao.update(
                    row.copy(
                        status = ReportEntity.STATUS_FAILED,
                        attemptCount = row.attemptCount + 1,
                        lastAttemptAt = System.currentTimeMillis(),
                        lastError = "HTTP ${resp.code()}"
                    )
                )
                Result.failure(RuntimeException("HTTP ${resp.code()}"))
            }
        } catch (t: Throwable) {
            dao.update(
                row.copy(
                    status = ReportEntity.STATUS_FAILED,
                    attemptCount = row.attemptCount + 1,
                    lastAttemptAt = System.currentTimeMillis(),
                    lastError = t.message
                )
            )
            Result.failure(t)
        }
    }

    /** Drain everything in QUEUED or FAILED state. */
    suspend fun drainQueue(): Int {
        val pending = dao.byStatus(listOf(ReportEntity.STATUS_QUEUED, ReportEntity.STATUS_FAILED))
        var sent = 0
        pending.forEach { row ->
            val r = attemptSend(row.id)
            if (r.isSuccess) sent++
        }
        return sent
    }

    /** Re-poll status for every SYNCED row that hasn't been corroborated yet. */
    suspend fun refreshCorroboration(): Int {
        val rows = dao.pendingCorroboration()
        var updated = 0
        rows.forEach { row ->
            val id = row.reportId ?: return@forEach
            try {
                val resp = api.reportStatus(id)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    dao.update(
                        row.copy(
                            corroboratedAt = body.corroboratedAt,
                            corroboratingSources = body.corroboratingSources.joinToString(",").ifBlank { null },
                            witnessCount = body.witnessCount
                        )
                    )
                    body.reputationTier?.let { prefs.setReputationTier(it) }
                    updated++
                }
            } catch (_: Throwable) { /* keep silent; will retry */ }
        }
        return updated
    }

    suspend fun fetchDetections(
        period: String,
        south: Double, west: Double, north: Double, east: Double
    ): List<Detection> {
        val bbox = "$south,$west,$north,$east"
        return try {
            val resp = api.detections(period, bbox)
            resp.body()?.detections.orEmpty()
        } catch (_: Throwable) { emptyList() }
    }

    suspend fun fetchSourceHealth(): SourceHealth? = try {
        api.sourceHealth().body()
    } catch (_: Throwable) { null }
}
