package com.phoenix.citizen.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Observation type sent in citizen reports. */
enum class ObservationType(val wire: String) {
    FLAME("flame"),
    SMOKE("smoke"),
    UNSURE("unsure");

    companion object {
        fun fromWire(s: String?): ObservationType = entries.firstOrNull { it.wire == s } ?: UNSURE
    }
}

enum class WindDirection(val wire: String) {
    UNKNOWN("unknown"),
    N("N"), NE("NE"), E("E"), SE("SE"),
    S("S"), SW("SW"), W("W"), NW("NW");

    companion object {
        fun fromWire(s: String?): WindDirection = entries.firstOrNull { it.wire == s } ?: UNKNOWN
    }
}

enum class ReputationTier { NEW, TRUSTED, VETERAN, AUTHORITY }

/** Body for POST /api/citizen_report */
@Serializable
data class CitizenReportPost(
    @SerialName("device_hash") val deviceHash: String,
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double,
    @SerialName("ts_utc") val tsUtc: String,
    @SerialName("observation_type") val observationType: String,
    @SerialName("wind_direction_observed") val windDirection: String? = null,
    @SerialName("photo_path") val photoPath: String? = null,
    @SerialName("note") val note: String? = null
)

/** Server response after a successful POST. Backend returns report_id as an Int. */
@Serializable
data class CitizenReportResponse(
    @SerialName("report_id") val reportId: Long,
    @SerialName("message") val message: String? = null,
    @SerialName("validation_status") val validationStatus: String? = null,
    @SerialName("forecast_context") val forecastContext: JsonElement? = null,
)

/** Response from GET /api/citizen_report_status */
@Serializable
data class CitizenReportStatus(
    @SerialName("corroborated_at") val corroboratedAt: String? = null,
    @SerialName("corroborating_sources") val corroboratingSources: List<String> = emptyList(),
    @SerialName("witness_count") val witnessCount: Int = 0,
    @SerialName("reputation_tier") val reputationTier: String? = null
)

/** A detection returned by /api/detections (matches live backend shape). */
@Serializable
data class Detection(
    @SerialName("id") val id: Long? = null,
    @SerialName("source") val source: String,
    @SerialName("aoi_id") val aoiId: String? = null,
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
    @SerialName("lon") val lon: Double? = null,
    @SerialName("timestamp") val timestamp: String? = null,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("layer") val layer: String? = null,
    @SerialName("voted_alpha") val votedAlpha: Boolean = false,
    @SerialName("voter_count") val voterCount: Int? = null,
    @SerialName("sole_reporter") val soleReporter: Boolean = false,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("frp_mw") val frpMw: Double? = null,
    @SerialName("fire_temperature_c") val fireTempC: Double? = null,
    @SerialName("uncertainty_radius_km") val uncertaintyRadiusKm: Double? = null,
    @SerialName("uncertainty_basis") val uncertaintyBasis: String? = null,
)

@Serializable
data class DetectionsResponse(
    @SerialName("detections") val detections: List<Detection> = emptyList()
)

/** Response from GET /api/source_health. Live backend shape (2026-06-08). */
@Serializable
data class SourceHealth(
    @SerialName("count") val count: Int = 0,
    @SerialName("threshold_min_total") val thresholdMinTotal: Int = 0,
    @SerialName("threshold_precision_pct") val thresholdPrecisionPct: Double = 0.0,
    @SerialName("warnings") val warnings: List<SourceHealthWarning> = emptyList(),
)

@Serializable
data class SourceHealthWarning(
    @SerialName("source") val source: String,
    @SerialName("kind") val kind: String? = null,
    @SerialName("severity") val severity: String? = null,
    @SerialName("precision_pct") val precisionPct: Double? = null,
    @SerialName("total") val total: Int? = null,
    @SerialName("persistent_fp") val persistentFp: Int? = null,
)
