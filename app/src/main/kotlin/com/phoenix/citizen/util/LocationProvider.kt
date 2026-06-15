package com.phoenix.citizen.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

/**
 * A single GPS fix with the provenance the location-quality split needs.
 *
 * @param lat        latitude (WGS84)
 * @param lon        longitude (WGS84)
 * @param accuracyM  horizontal accuracy radius in metres (Android
 *                   [android.location.Location.getAccuracy]); null when the
 *                   underlying fix carried no accuracy (rare — `lastLocation`
 *                   fallbacks usually still report one).
 * @param fixTsUtc   UTC ISO-8601 timestamp of the fix itself (not submission
 *                   time); null when unavailable.
 */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val fixTsUtc: String? = null,
)

/**
 * Thin coroutine wrapper around [FusedLocationProviderClient].
 *
 * Caller MUST already hold ACCESS_FINE_LOCATION (UI requests the permission
 * via accompanist-permissions before calling).
 */
class LocationProvider(private val context: Context) {

    /**
     * Full fix — preserves accuracy + fix timestamp so the report payload can
     * carry `accuracy_m` (and the location-quality split can classify precision
     * at the source instead of guessing from the comune-centroid heuristic).
     */
    @SuppressLint("MissingPermission")
    suspend fun currentFixOrNull(): LocationFix? {
        if (!hasFinePermission()) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        return try {
            val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                ?: client.lastLocation.await()
            loc?.let {
                LocationFix(
                    lat = it.latitude,
                    lon = it.longitude,
                    // Location.hasAccuracy() guards the sentinel-0.0 case.
                    accuracyM = if (it.hasAccuracy()) it.accuracy else null,
                    fixTsUtc = TimeUtils.epochMillisToUtcIso(it.time),
                )
            }
        } catch (_: Throwable) { null }
    }

    /**
     * Back-compat shim: callers that only need (lat, lon) keep working.
     * Prefer [currentFixOrNull] so accuracy/provenance is not discarded.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentOrNull(): Pair<Double, Double>? =
        currentFixOrNull()?.let { it.lat to it.lon }

    private fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
