package com.example.accounting.core.network

import com.example.accounting.domain.company.Company
import com.example.accounting.domain.financialyear.AccountingPeriod
import com.example.accounting.domain.financialyear.FinancialYear

/**
 * Clean interfaces for remote Python API communication, ensuring decoupling between
 * local persistence layers and remote transport details.
 */

/** Sync-gated auth (Phase 6, Priority 6.10/6.22) - logging in only enables optional cloud sync;
 * the app keeps working fully offline with zero login either way. */
interface IAuthService {
    suspend fun login(email: String, password: String): Result<AuthTokenResponse>
    /** Phone/OTP login (Week 1, Play Store update plan) - requests a one-time code be sent to
     * [phone]; returns how long it's valid for. Never returns tokens itself - [verifyOtp] does,
     * once the user (or SMS Retriever auto-fill) supplies the code. */
    suspend fun requestOtp(phone: String): Result<OtpRequestResult>
    suspend fun verifyOtp(phone: String, code: String): Result<AuthTokenResponse>
    suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponse>
    suspend fun logout(): Result<Unit>
    fun isLoggedIn(): Boolean
}

data class OtpRequestResult(val expiresInSeconds: Long)

data class AuthTokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val tokenType: String = "Bearer"
)

interface ICompanySyncService {
    suspend fun pullCompany(companyId: String): Result<Company>
    suspend fun pushCompany(company: Company): Result<Company>
    suspend fun listCompanies(): Result<List<Company>>
}

interface IFinancialYearSyncService {
    suspend fun pullFinancialYears(companyId: String): Result<List<FinancialYear>>
    suspend fun pushFinancialYear(financialYear: FinancialYear): Result<FinancialYear>
}

interface IAccountingPeriodSyncService {
    suspend fun pullAccountingPeriods(companyId: String, financialYearId: String): Result<List<AccountingPeriod>>
    suspend fun pushAccountingPeriod(period: AccountingPeriod): Result<AccountingPeriod>
}

interface ISyncStatusService {
    suspend fun getSyncStatus(companyId: String): Result<RemoteSyncStatus>
}

data class RemoteSyncStatus(
    val companyId: String,
    val lastSyncedServerTimestamp: Long,
    val pendingServerMutationsCount: Int,
    val isHealthy: Boolean
)
