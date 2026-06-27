package com.phoenix.citizen.alerts

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.phoenix.citizen.MainActivity
import com.phoenix.citizen.PhoenixApp
import com.phoenix.citizen.R
import com.phoenix.citizen.data.api.NetworkModule
import com.phoenix.citizen.data.api.PhoenixApiV2
import com.phoenix.citizen.data.repository.AreaAlertStore
import com.phoenix.citizen.data.repository.DevicePrefs
import kotlinx.coroutines.flow.first

/**
 * Runs the area-alert check: for each enabled watch area, asks the backend which
 * fires are inside / approaching it, applies the pure [AreaAlertLogic], posts any
 * resulting local notification, and persists updated per-area state.
 *
 * Called from [com.phoenix.citizen.worker.SyncWorker] (every ~15 min) and from
 * the Alerts screen's "Check now" button.
 *
 * Delivery is entirely on-device polling + local notifications — it needs no
 * Firebase / FCM credentials and works on any Android 8+ device.
 */
class AreaAlertChecker(
    private val context: Context,
    private val store: AreaAlertStore = AreaAlertStore(context),
    private val prefs: DevicePrefs = DevicePrefs(context),
    private val api: PhoenixApiV2 = NetworkModule.apiV2,
) {

    data class AreaResult(val label: String, val inside: Int, val approaching: Int, val posted: Int)
    data class CheckSummary(val ranAreas: Int, val posted: Int, val results: List<AreaResult>)

    /**
     * @param force when true, runs even if the global push toggle is off (used by
     *              the manual "Check now" button so the user can see live counts);
     *              notifications are still only posted when push is enabled.
     */
    suspend fun runCheck(force: Boolean = false): CheckSummary {
        val pushEnabled = prefs.pushEnabledFlow.first()
        if (!pushEnabled && !force) return CheckSummary(0, 0, emptyList())

        val lang = if (prefs.languageFlow.first() == "en") "en" else "it"
        val areas = store.getAreas().filter { it.enabled }

        var totalPosted = 0
        val results = ArrayList<AreaResult>(areas.size)

        for (area in areas) {
            val resp = try {
                val r = api.firesNear(
                    lat = area.lat,
                    lon = area.lon,
                    radiusKm = area.radiusKm,
                    approachKm = area.approachKm,
                )
                if (r.isSuccessful) r.body() else null
            } catch (_: Throwable) {
                null
            } ?: continue

            val prior = store.getState(area.id)
            val plan = AreaAlertLogic.plan(
                area = area,
                resp = resp,
                prior = prior,
                nowMs = System.currentTimeMillis(),
                lang = lang,
            )

            var posted = 0
            if (pushEnabled && canPostNotifications()) {
                plan.notifications.forEach { n ->
                    postNotification(n)
                    posted++
                }
            }
            store.putState(plan.newState)
            totalPosted += posted
            results.add(AreaResult(area.label, resp.counts.inside, resp.counts.approaching, posted))
        }

        return CheckSummary(areas.size, totalPosted, results)
    }

    private fun canPostNotifications(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    private fun postNotification(n: AreaAlertLogic.PlannedNotification) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // Tapping the alert takes the user straight to the map to see the fire.
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_MAP)
        }
        val pi = PendingIntent.getActivity(
            context,
            n.dedupeKey.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, PhoenixApp.CHANNEL_AREA)
            .setSmallIcon(R.drawable.ic_fire)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setPriority(if (n.priorityHigh) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        // Stable id per (area × classification) so a refresh replaces rather than stacks.
        NotificationManagerCompat.from(context).notify(n.dedupeKey.hashCode(), notif)
    }
}
