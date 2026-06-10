package com.phoenix.citizen.data.api

import com.phoenix.citizen.data.model.CitizenReportPost
import com.phoenix.citizen.data.model.CitizenReportResponse
import com.phoenix.citizen.data.model.CitizenReportStatus
import com.phoenix.citizen.data.model.DetectionsResponse
import com.phoenix.citizen.data.model.SourceHealth
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface PhoenixApi {

    @POST("api/citizen_report")
    suspend fun submitReport(
        @Header("X-Integrity-Token") integrityToken: String?,
        @Body body: CitizenReportPost
    ): Response<CitizenReportResponse>

    @GET("api/citizen_report_status")
    suspend fun reportStatus(
        @Query("report_id") reportId: String
    ): Response<CitizenReportStatus>

    @GET("api/detections")
    suspend fun detections(
        @Query("period") period: String = "24h",
        @Query("bbox") bbox: String
    ): Response<DetectionsResponse>

    @GET("api/source_health")
    suspend fun sourceHealth(): Response<SourceHealth>
}
