package com.phoenix.citizen

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.phoenix.citizen.worker.SyncWorker
import java.util.concurrent.TimeUnit

/**
 * Top-level Application class.
 *
 * Responsibilities:
 *  - Set up notification channels
 *  - Initialize WorkManager with custom config
 *  - Schedule the periodic [SyncWorker] that drains the offline report queue
 */
class PhoenixApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        scheduleSyncWorker()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val nearby = NotificationChannel(
            CHANNEL_NEARBY,
            getString(R.string.channel_nearby_fires),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_nearby_fires_desc)
            enableVibration(true)
        }

        val confirmed = NotificationChannel(
            CHANNEL_CONFIRMED,
            getString(R.string.channel_confirmed),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_confirmed_desc)
        }

        nm.createNotificationChannel(nearby)
        nm.createNotificationChannel(confirmed)
    }

    private fun scheduleSyncWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        const val CHANNEL_NEARBY = "phoenix_nearby_fires"
        const val CHANNEL_CONFIRMED = "phoenix_confirmed"
    }
}
