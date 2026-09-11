package com.example.accounting.core.network

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

@JsonClass(generateAdapter = true)
data class OutboxSyncItemDto(
    val syncId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payloadJson: String,
    val idempotencyKey: String,
    val version: Long = 1L,
    val clientTimestamp: Long
)

@JsonClass(generateAdapter = true)
data class SyncBatchRequest(
    val companyId: String,
    val deviceId: String,
    val items: List<OutboxSyncItemDto>
)

@JsonClass(generateAdapter = true)
data class SyncRejectionDto(
    val syncId: String,
    val idempotencyKey: String,
    val reason: String,
    val conflictCode: String? = null,
    val serverVersion: Long? = null
)

@JsonClass(generateAdapter = true)
data class SyncBatchResponse(
    val success: Boolean,
    val processedCount: Int,
    val processedSyncIds: List<String>,
    val rejections: List<SyncRejectionDto> = emptyList(),
    val serverTimestamp: Long
)

@JsonClass(generateAdapter = true)
data class ServerHealthDto(
    val status: String,
    val version: String,
    val timestamp: Long
)

@JsonClass(generateAdapter = true)
data class LoginRequestDto(val email: String, val password: String)

@JsonClass(generateAdapter = true)
data class RefreshRequestDto(val refreshToken: String)

@JsonClass(generateAdapter = true)
data class AuthTokenResponseDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val tokenType: String = "Bearer"
)

@JsonClass(generateAdapter = true)
data class ApiErrorDto(val code: String, val message: String)

/** Phone/OTP login (Week 1, Play Store update plan) - mirrors the server's OtpRequestRequest/
 * OtpVerifyRequest/OtpRequestResponse schemas field-for-field. */
@JsonClass(generateAdapter = true)
data class OtpRequestRequestDto(val phone: String)

@JsonClass(generateAdapter = true)
data class OtpRequestResponseDto(val expiresInSeconds: Long)

@JsonClass(generateAdapter = true)
data class OtpVerifyRequestDto(val phone: String, val code: String)

/**
 * Retrofit interface for the server-side Python Accounting REST API (Phase 6). Deliberately
 * minimal on the mutation side: the ONLY way this app creates/cancels accounting data on the
 * server is `syncOutboxBatch` - Room commits locally first, the Outbox enqueues a complete
 * [com.example.accounting.domain.sync.SyncEvent], and only that queued event reaches the server.
 * There is no direct "create a Sales Invoice" HTTP call from the UI/ViewModel (Phase 6, Priority
 * 6.2/6.3 - "Never: UI -> API -> wait -> accounting entry"). The server's own API surface is wider
 * (named business-resource endpoints for future non-Android clients, per 6.13/6.17) but this app
 * never calls those directly for writes.
 */
interface AccountingApiService {

    @GET("health")
    suspend fun checkHealth(): Response<ServerHealthDto>

    @POST("auth/token")
    suspend fun login(@Body request: LoginRequestDto): Response<AuthTokenResponseDto>

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshRequestDto): Response<AuthTokenResponseDto>

    @POST("auth/logout")
    suspend fun logout(@Body request: RefreshRequestDto): Response<Unit>

    @POST("auth/otp/request")
    suspend fun requestOtp(@Body request: OtpRequestRequestDto): Response<OtpRequestResponseDto>

    @POST("auth/otp/verify")
    suspend fun verifyOtp(@Body request: OtpVerifyRequestDto): Response<AuthTokenResponseDto>

    @POST("sync/outbox/batch")
    suspend fun syncOutboxBatch(
        @Header("Idempotency-Key") batchIdempotencyKey: String,
        @Body request: SyncBatchRequest
    ): Response<SyncBatchResponse>
}
