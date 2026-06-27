package com.phoenix.citizen.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * v2 API models — added 2026-06-12 for the Cloudflare-edge migration.
 *
 * v1 models are kept intact in Models.kt for backward compatibility. The app
 * tries v2 endpoints first; on 404 it falls back to the v1 surface. This file
 * holds everything that is new in v2.
 */

// ────────────────────────── system_status ──────────────────────────

/**
 * Response from GET /api/system_status. Drives the 🟢🟡🔴 banner at the top
 * of the map screen. Backend recomputes this from DGX heartbeat + queue depth
 * every 5 min and caches in KV.
 */
@Serializable
data class SystemStatus(
    @SerialName("state") val state: String,           // "green" | "yellow" | "red"
    @SerialName("message_en") val messageEn: String,
    @SerialName("message_it") val messageIt: String,
    @SerialName("last_dgx_push") val lastDgxPush: String? = null,
    @SerialName("last_queue_drain") val lastQueueDrain: String? = null,
    @SerialName("queue_depth") val queueDepth: Int = 0,
    @SerialName("oldest_queued_age_s") val oldestQueuedAgeS: Int = 0,
    @SerialName("computed_at") val computedAt: String? = null,
) {
    companion object {
        /** Used when the endpoint 404s (v1 backend during partial rollback). */
        val unknown_green: SystemStatus = SystemStatus(
            state = "green",
            messageEn = "All systems operational.",
            messageIt = "Tutti i sistemi operativi.",
        )
    }
}

// ────────────────────────── citizen_reports_public ──────────────────────────

/**
 * One row from GET /api/citizen_reports_public. Used for rendering existing
 * citizen-reported markers (orange pulsing person icon, counter badge).
 */
@Serializable
data class CitizenReportPublic(
    @SerialName("report_id") val reportId: Long,
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double,
    @SerialName("observation_type") val observationType: String,
    @SerialName("ts_utc") val tsUtc: String,
    @SerialName("validation_status") val validationStatus: String? = null,
    @SerialName("validation_source") val validationSource: String? = null,
    @SerialName("confirm_count") val confirmCount: Int = 1,
    @SerialName("uncertainty_radius_km") val uncertaintyRadiusKm: Double = 7.0,
    @SerialName("uncertainty_basis") val uncertaintyBasis: String = "default_conservative",
    @SerialName("forecast_context") val forecastContext: JsonElement? = null,
)

// ────────────────────────── all_signals ──────────────────────────

/**
 * Unified feed row from GET /api/all_signals. Each row carries `signal_kind`
 * so the map paints the right icon: 'detection' (red diamond) | 'citizen'
 * (orange person) | 'google_wildfire' (red outlined polygon) | ...
 */
@Serializable
data class SignalRow(
    @SerialName("signal_kind") val signalKind: String,
    @SerialName("id") val id: Long,
    @SerialName("ts_utc") val tsUtc: String,
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double,
    @SerialName("source") val source: String,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("severity") val severity: String? = null,
    @SerialName("frp_mw") val frpMw: Double? = null,
    @SerialName("uncertainty_radius_km") val uncertaintyRadiusKm: Double? = null,
    @SerialName("aoi") val aoi: String? = null,
    @SerialName("validation_status") val validationStatus: String? = null,
    @SerialName("confirm_count") val confirmCount: Int? = null,
)

// ────────────────────────── citizen_upload_intent ──────────────────────────

/**
 * Body for POST /api/citizen_upload_intent — ask the backend for a presigned
 * upload URL before PUTting media direct to R2.
 */
@Serializable
data class CitizenUploadIntentRequest(
    @SerialName("kind") val kind: String,              // "photo" | "voice"
    @SerialName("mime_type") val mimeType: String,
    @SerialName("size_bytes_hint") val sizeBytesHint: Long? = null,
)

@Serializable
data class CitizenUploadIntentResponse(
    @SerialName("kind") val kind: String,
    @SerialName("key") val key: String,                // R2 key to send back in the report
    @SerialName("upload_url") val uploadUrl: String,   // PUT the bytes here
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("max_bytes") val maxBytes: Long,
)

// ────────────────────────── v2 citizen_report (new contract) ──────────────────────────

/**
 * v2 body for POST /api/citizen_report. Differences from v1: required
 * `client_token` for idempotent retries, `media_keys` carries R2 keys after
 * direct-upload (no multipart body), optional `accuracy_m` from GPS.
 */
@Serializable
data class CitizenReportV2Post(
    @SerialName("client_token") val clientToken: String,
    @SerialName("device_id") val deviceId: String?,
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    @SerialName("ts_observed_utc") val tsObservedUtc: String,
    @SerialName("observation_type") val observationType: String,
    @SerialName("intensity") val intensity: String? = null,
    @SerialName("text_note") val textNote: String? = null,
    @SerialName("voice_transcript") val voiceTranscript: String? = null,
    @SerialName("media_keys") val mediaKeys: List<String> = emptyList(),
)

@Serializable
data class CitizenReportV2Response(
    @SerialName("status") val status: String,          // "queued" | "deduped_confirmed_existing" | ...
    @SerialName("client_token") val clientToken: String,
    @SerialName("report_id") val reportId: Long,
    @SerialName("poll_url") val pollUrl: String? = null,
    @SerialName("estimated_wait_s") val estimatedWaitS: Int? = null,
    @SerialName("user_message") val userMessage: BilingualMessage? = null,
)

// ────────────────────────── citizen_report_status (v2 contract) ──────────────────────────

/**
 * v2 status response polled by /api/citizen_report_status?token=<client_token>.
 * Replaces v1 CitizenReportStatus which was keyed on report_id and returned a
 * different shape.
 */
@Serializable
data class CitizenReportStatusV2(
    @SerialName("report_id") val reportId: Long? = null,
    @SerialName("status") val status: String,         // "queued" | "accepted" | "rejected" | "expired" | "corroborated"
    @SerialName("validation_status") val validationStatus: String? = null,
    @SerialName("validation_source") val validationSource: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("processed_at") val processedAt: String? = null,
    @SerialName("confirm_count") val confirmCount: Int = 1,
    @SerialName("result") val result: JsonObject? = null,
    @SerialName("user_message") val userMessage: BilingualMessage? = null,
)

// ────────────────────────── citizen_confirm ──────────────────────────

/**
 * "I see this fire too." Increments confirm_count on a known report. No DGX
 * involvement — Worker writes straight to D1.
 */
@Serializable
data class CitizenConfirmRequest(
    @SerialName("client_token") val clientToken: String,
    @SerialName("report_id") val reportId: Long,
    @SerialName("device_id") val deviceId: String? = null,
)

@Serializable
data class CitizenConfirmResponse(
    @SerialName("status") val status: String,
    @SerialName("report_id") val reportId: Long,
    @SerialName("confirm_count") val confirmCount: Int,
    @SerialName("client_token") val clientToken: String,
    @SerialName("user_message") val userMessage: BilingualMessage? = null,
)

// ────────────────────────── user_fp_flag ──────────────────────────

/**
 * "This detection is wrong." Long-press a satellite detection marker.
 * Forwarded to DGX for offline correction analysis.
 */
@Serializable
data class UserFpFlagRequest(
    @SerialName("client_token") val clientToken: String,
    @SerialName("detection_id") val detectionId: Long,
    @SerialName("reason") val reason: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
)

@Serializable
data class UserFpFlagResponse(
    @SerialName("status") val status: String,
    @SerialName("client_token") val clientToken: String,
)

// ────────────────────────── shared ──────────────────────────

@Serializable
data class BilingualMessage(
    @SerialName("en") val en: String,
    @SerialName("it") val it: String,
)
