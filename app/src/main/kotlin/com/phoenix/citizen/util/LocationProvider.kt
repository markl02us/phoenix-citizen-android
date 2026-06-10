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
 * Thin coroutine wrapper around [FusedLocationProviderClient].
 *
 * Caller MUST already hold ACCESS_FINE_LOCATION (UI requests the permission
 * via accompanist-permissions before calling).
 */
class LocationProvider(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun currentOrNull(): Pair<Double, Double>? {
        if (!hasFinePermission()) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        return try {
            val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                ?: client.lastLocation.await()
            loc?.let { it.latitude to it.longitude }
        } catch (_: Throwable) { null }
    }

    private fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
