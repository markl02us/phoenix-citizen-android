package com.phoenix.citizen

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration as AndroidConfiguration
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.phoenix.citizen.worker.SyncWorker
import java.util.Locale
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

    override fun attachBaseContext(base: Context) {
        // Force Italian as default locale for Sicily-first audience.
        val it = Locale("it")
        Locale.setDefault(it)
        val config = AndroidConfiguration(base.resources.configuration)
        config.setLocale(it)
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate() {
        super.onCreate()
        // osmdroid policy requires a user agent set before any tile request.
        org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
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

        val area = NotificationChannel(
            CHANNEL_AREA,
            getString(R.string.channel_area),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_area_desc)
            enableVibration(true)
        }

        nm.createNotificationChannel(nearby)
        nm.createNotificationChannel(confirmed)
        nm.createNotificationChannel(area)
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
        const val CHANNEL_AREA = "phoenix_area_alerts"
    }
}
