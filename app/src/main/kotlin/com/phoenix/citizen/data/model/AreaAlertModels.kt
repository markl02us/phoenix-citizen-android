package com.phoenix.citizen.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Personal "watch area" (range ring) the user draws to be notified about fires
 * near a place that matters to them — a farm, the animals, a second home they
 * can't physically check on. Stored device-locally (DataStore); the device polls
 * /api/fires_near for each enabled area and raises a local notification.
 *
 *  - [radiusKm]   inner ring: a fire at/inside this counts as "in your area".
 *  - [approachKm] outer buffer: a fire in (radius, radius+approach] counts as
 *                 "approaching" the area. 0 disables the approach ring.
 */
@Serializable
data class WatchArea(
    val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
    val approachKm: Double,
    val enabled: Boolean = true,
    val createdUtc: Long = 0L,
)

/**
 * Per-area runtime state (not user-facing). Tracks which detections we've already
 * notified about (so an ongoing fire isn't announced repeatedly), the last known
 * nearest-fire distance (so we can say "moving closer"), and the last time we
 * raised a notification for this area (cooldown).
 */
@Serializable
data class AreaAlertState(
    val areaId: String,
    val notifiedIds: List<Long> = emptyList(),
    val lastNearestKm: Double? = null,
    val lastNotifiedAtMs: Long = 0L,
)

// ────────────────────────── /api/fires_near response ──────────────────────────

@Serializable
data class NearbyFire(
    @SerialName("detection_id") val detectionId: Long,
    val lat: Double,
    val lon: Double,
    @SerialName("distance_km") val distanceKm: Double,
    @SerialName("bearing_deg") val bearingDeg: Int = 0,
    val compass: String = "",
    val source: String? = null,
    val severity: String? = null,
    @SerialName("frp_mw") val frpMw: Double? = null,
    val confidence: Double? = null,
    @SerialName("ts_utc") val tsUtc: String? = null,
    @SerialName("verification_tier") val verificationTier: String? = null,
)

@Serializable
data class FiresNearCounts(
    val inside: Int = 0,
    val approaching: Int = 0,
)

@Serializable
data class FiresNearResponse(
    val counts: FiresNearCounts = FiresNearCounts(),
    val inside: List<NearbyFire> = emptyList(),
    val approaching: List<NearbyFire> = emptyList(),
    @SerialName("generated_utc") val generatedUtc: String? = null,
)
