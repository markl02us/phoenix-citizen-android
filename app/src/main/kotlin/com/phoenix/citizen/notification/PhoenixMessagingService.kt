package com.phoenix.citizen.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.phoenix.citizen.MainActivity
import com.phoenix.citizen.PhoenixApp
import com.phoenix.citizen.R

/**
 * Firebase Cloud Messaging service. STUBBED — extends Android's plain
 * Service interface until Mark adds `google-services.json` + uncomments
 * the Firebase BoM in app/build.gradle.kts. At that point change the
 * superclass to `com.google.firebase.messaging.FirebaseMessagingService`
 * and override `onMessageReceived`.
 *
 * Expected payload (server-side):
 *   {
 *     "channel": "nearby_fires" | "confirmed",
 *     "title": "...",
 *     "body": "...",
 *     "report_id": "...?"  // for confirmed-channel deep-link
 *   }
 */
class PhoenixMessagingService : android.app.Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // When migrated to FirebaseMessagingService, route here from onMessageReceived.
        intent?.let {
            val channel = it.getStringExtra("channel") ?: PhoenixApp.CHANNEL_NEARBY
            val title = it.getStringExtra("title") ?: getString(R.string.app_name)
            val body = it.getStringExtra("body") ?: ""
            showNotification(channel, title, body)
        }
        return START_NOT_STICKY
    }

    private fun showNotification(channelKey: String, title: String, body: String) {
        val channelId = when (channelKey) {
            "confirmed" -> PhoenixApp.CHANNEL_CONFIRMED
            else -> PhoenixApp.CHANNEL_NEARBY
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_fire)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
    }
}
