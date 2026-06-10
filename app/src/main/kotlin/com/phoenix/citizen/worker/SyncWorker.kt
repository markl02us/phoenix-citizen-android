package com.phoenix.citizen.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phoenix.citizen.data.repository.ReportRepository

/**
 * Periodic worker (15 min) that:
 *  1. Drains every QUEUED / FAILED report by re-attempting submission.
 *  2. Re-fetches /api/citizen_report_status for SYNCED reports awaiting corroboration.
 *
 * Backoff is handled by WorkManager — repository-level retries also bump
 * attemptCount + lastError for debugging.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = ReportRepository(applicationContext)
        return try {
            repo.drainQueue()
            repo.refreshCorroboration()
            Result.success()
        } catch (t: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "phoenix-sync"
    }
}
