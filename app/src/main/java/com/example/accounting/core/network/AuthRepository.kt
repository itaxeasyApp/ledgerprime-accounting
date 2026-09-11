package com.example.accounting.core.network

import android.content.Context
import com.example.accounting.core.security.SecureStorage

/**
 * Real [IAuthService] implementation (Phase 6, Priority 6.7/6.10) - the first concrete class for
 * an interface that previously had zero implementations anywhere in the codebase (confirmed by the
 * 6.0 audit). Login is optional and only gates cloud sync, never local accounting: the app works
 * fully offline whether or not [isLoggedIn] is true.
 */
class AuthRepository(
    private val context: Context,
    private val apiClient: ApiClient = ApiClient.getInstance(context),
    private val secureStorage: SecureStorage = SecureStorage.getInstance(context)
) : IAuthService {

    override suspend fun login(email: String, password: String): Result<AuthTokenResponse> {
        return try {
            val response = apiClient.apiService.login(LoginRequestDto(email, password))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                secureStorage.setAuthToken(body.accessToken)
                secureStorage.setRefreshToken(body.refreshToken)
                Result.success(
                    AuthTokenResponse(body.accessToken, body.refreshToken, body.expiresInSeconds, body.tokenType)
                )
            } else {
                Result.failure(Exception("Login failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requestOtp(phone: String): Result<OtpRequestResult> {
        return try {
            val response = apiClient.apiService.requestOtp(OtpRequestRequestDto(phone))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Result.success(OtpRequestResult(body.expiresInSeconds))
            } else {
                Result.failure(Exception("Could not send code: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun verifyOtp(phone: String, code: String): Result<AuthTokenResponse> {
        return try {
            val response = apiClient.apiService.verifyOtp(OtpVerifyRequestDto(phone, code))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                secureStorage.setAuthToken(body.accessToken)
                secureStorage.setRefreshToken(body.refreshToken)
                Result.success(
                    AuthTokenResponse(body.accessToken, body.refreshToken, body.expiresInSeconds, body.tokenType)
                )
            } else {
                Result.failure(Exception("That code is invalid or has expired: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponse> {
        return try {
            val response = apiClient.apiService.refreshToken(RefreshRequestDto(refreshToken))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                secureStorage.setAuthToken(body.accessToken)
                secureStorage.setRefreshToken(body.refreshToken)
                Result.success(
                    AuthTokenResponse(body.accessToken, body.refreshToken, body.expiresInSeconds, body.tokenType)
                )
            } else {
                // Refresh rejected (expired/revoked) - clear the stale session rather than retry forever.
                secureStorage.clearSession()
                Result.failure(Exception("Refresh failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun logout(): Result<Unit> {
        val refreshToken = secureStorage.getRefreshToken()
        return try {
            if (refreshToken != null) {
                apiClient.apiService.logout(RefreshRequestDto(refreshToken))
            }
            secureStorage.clearSession()
            Result.success(Unit)
        } catch (e: Exception) {
            // Best-effort server-side revocation - clear the local session regardless, since the
            // user's intent to log out of THIS device must always succeed even if offline.
            secureStorage.clearSession()
            Result.success(Unit)
        }
    }

    override fun isLoggedIn(): Boolean = secureStorage.getAuthToken() != null
}
