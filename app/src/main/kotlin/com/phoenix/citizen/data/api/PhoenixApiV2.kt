package com.phoenix.citizen.data.api

import com.phoenix.citizen.data.model.CitizenConfirmRequest
import com.phoenix.citizen.data.model.CitizenConfirmResponse
import com.phoenix.citizen.data.model.CitizenReportPublic
import com.phoenix.citizen.data.model.CitizenReportStatusV2
import com.phoenix.citizen.data.model.CitizenReportV2Post
import com.phoenix.citizen.data.model.CitizenReportV2Response
import com.phoenix.citizen.data.model.CitizenUploadIntentRequest
import com.phoenix.citizen.data.model.CitizenUploadIntentResponse
import com.phoenix.citizen.data.model.FiresNearResponse
import com.phoenix.citizen.data.model.SignalRow
import com.phoenix.citizen.data.model.SystemStatus
import com.phoenix.citizen.data.model.UserFpFlagRequest
import com.phoenix.citizen.data.model.UserFpFlagResponse
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * v2 API client. Lives alongside the v1 PhoenixApi during the transition.
 * Both interfaces are constructed from the same Retrofit builder so they
 * share the OkHttp client + base URL.
 *
 * Endpoint contract documented in:
 *   C:\Users\markl\phoenix_citizen_android\cf_edge\src\index.ts
 *   docs/PHOENIX_VPS_MIGRATION.md
 */
interface PhoenixApiV2 {

    // ────────────────────────── reads ──────────────────────────

    @GET("api/system_status")
    suspend fun systemStatus(): Response<SystemStatus>

    @GET("api/citizen_reports_public")
    suspend fun citizenReportsPublic(
        @Query("since") since: String? = null,
        @Query("limit") limit: Int = 200,
    ): Response<List<CitizenReportPublic>>

    @GET("api/all_signals")
    suspend fun allSignals(
        @Query("since") since: String? = null,
        @Query("types") types: String? = null,
        @Query("limit") limit: Int = 2000,
    ): Response<List<SignalRow>>

    @GET("api/citizen_report_status")
    suspend fun reportStatusV2(
        @Query("token") clientToken: String,
    ): Response<CitizenReportStatusV2>

    /**
     * Fires inside / approaching a personal watch area. Polled per enabled area
     * by the area-alert checker to drive on-device range-ring notifications.
     */
    @GET("api/fires_near")
    suspend fun firesNear(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("radius_km") radiusKm: Double,
        @Query("approach_km") approachKm: Double,
        @Query("since_hours") sinceHours: Int = 24,
        @Query("limit") limit: Int = 500,
    ): Response<FiresNearResponse>

    // ────────────────────────── writes ──────────────────────────

    @POST("api/citizen_report")
    suspend fun submitReportV2(
        @Header("X-Integrity-Token") integrityToken: String?,
        @Body body: CitizenReportV2Post,
    ): Response<CitizenReportV2Response>

    @POST("api/citizen_upload_intent")
    suspend fun uploadIntent(
        @Body body: CitizenUploadIntentRequest,
    ): Response<CitizenUploadIntentResponse>

    /**
     * R2 direct-upload (via Worker relay). The presigned URL returned by
     * uploadIntent is a fully-qualified URL; we use @Url so Retrofit doesn't
     * append it to the base URL.
     */
    @PUT
    suspend fun uploadMediaBytes(
        @Url presignedUrl: String,
        @Header("Content-Type") contentType: String,
        @Body bytes: RequestBody,
    ): Response<Unit>

    @POST("api/citizen_confirm")
    suspend fun citizenConfirm(
        @Body body: CitizenConfirmRequest,
    ): Response<CitizenConfirmResponse>

    @POST("api/user_fp_flag")
    suspend fun userFpFlag(
        @Body body: UserFpFlagRequest,
    ): Response<UserFpFlagResponse>
}
