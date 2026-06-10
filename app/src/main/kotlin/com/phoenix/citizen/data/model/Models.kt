package com.phoenix.citizen.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

/** Server response after a successful POST. */
@Serializable
data class CitizenReportResponse(
    @SerialName("report_id") val reportId: String,
    @SerialName("accepted") val accepted: Boolean = true,
    @SerialName("message") val message: String? = null
)

/** Response from GET /api/citizen_report_status */
@Serializable
data class CitizenReportStatus(
    @SerialName("corroborated_at") val corroboratedAt: String? = null,
    @SerialName("corroborating_sources") val corroboratingSources: List<String> = emptyList(),
    @SerialName("witness_count") val witnessCount: Int = 0,
    @SerialName("reputation_tier") val reputationTier: String? = null
)

/** A detection returned by /api/detections. */
@Serializable
data class Detection(
    @SerialName("id") val id: String,
    @SerialName("source_class") val sourceClass: String,
    @SerialName("source") val source: String? = null,
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double,
    @SerialName("ts_utc") val tsUtc: String,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("frp_mw") val frpMw: Double? = null,
    @SerialName("voted") val voted: Boolean = false,
    @SerialName("title") val title: String? = null
)

@Serializable
data class DetectionsResponse(
    @SerialName("detections") val detections: List<Detection> = emptyList()
)

@Serializable
data class SourceHealth(
    @SerialName("status") val status: String,
    @SerialName("sources") val sources: Map<String, String> = emptyMap(),
    @SerialName("last_update_utc") val lastUpdateUtc: String? = null
)
