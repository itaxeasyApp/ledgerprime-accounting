package com.example.accounting.application.gstreturn

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.taxation.gstreturn.GstFilingMode
import com.example.accounting.domain.taxation.gstreturn.GstOnlineFilingGateway
import com.example.accounting.domain.taxation.gstreturn.GstQuarter
import com.example.accounting.domain.taxation.gstreturn.GstReturn
import com.example.accounting.domain.taxation.gstreturn.GstReturnArtifact
import com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity
import com.example.accounting.domain.taxation.gstreturn.GstReturnSection
import com.example.accounting.domain.taxation.gstreturn.GstReturnSubmission
import com.example.accounting.domain.taxation.gstreturn.GstReturnType
import com.example.accounting.domain.taxation.gstreturn.GstScheme
import com.example.accounting.domain.taxation.gstreturn.UnconfiguredGstOnlineFilingGateway
import kotlinx.coroutines.flow.Flow

/**
 * Application-service facade for the GST Return Dashboard & Filing Foundation (Rule 33) - every
 * method is a direct, pure delegation to the corresponding `AccountingRepository` function, exactly
 * matching [com.example.accounting.application.reports.ReportManagementService]'s own "never
 * recalculate, just orchestrate" shape. Holds no state of its own beyond the (defaulted,
 * unconfigured) [GstOnlineFilingGateway].
 */
class GstReturnManagementService(
    private val repository: AccountingRepository,
    private val onlineFilingGateway: GstOnlineFilingGateway = UnconfiguredGstOnlineFilingGateway()
) {
    fun listReturns(companyId: String): Flow<List<GstReturn>> = repository.getGstReturns(companyId)

    suspend fun getReturn(companyId: String, gstReturnId: String): GstReturn? =
        repository.getGstReturn(companyId, gstReturnId)

    suspend fun getArtifacts(gstReturnId: String): List<GstReturnArtifact> =
        repository.getGstReturnArtifacts(gstReturnId)

    suspend fun getSections(gstReturnId: String): List<GstReturnSection> =
        repository.getGstReturnSections(gstReturnId)

    suspend fun getSubmissions(gstReturnId: String): List<GstReturnSubmission> =
        repository.getGstReturnSubmissions(gstReturnId)

    suspend fun getOrCreateReturn(
        companyId: String,
        fy: FinancialYear,
        quarter: GstQuarter,
        month: Int?,
        scheme: GstScheme,
        returnType: GstReturnType,
        periodicity: GstReturnPeriodicity,
        filingMode: GstFilingMode
    ): GstReturn = repository.getOrCreateGstReturn(companyId, fy, quarter, month, scheme, returnType, periodicity, filingMode)

    suspend fun prepare(companyId: String, gstReturnId: String, fy: FinancialYear): AccountingResult<GstReturn> =
        repository.prepareGstReturn(companyId, gstReturnId, fy)

    suspend fun validate(companyId: String, gstReturnId: String, fy: FinancialYear): AccountingResult<GstReturn> =
        repository.validateGstReturn(companyId, gstReturnId, fy)

    suspend fun generateOfflineJson(companyId: String, gstReturnId: String, fy: FinancialYear): AccountingResult<GstReturnArtifact> =
        repository.generateGstReturnOfflineJson(companyId, gstReturnId, fy)

    suspend fun importOfflineResponse(companyId: String, gstReturnId: String, responseJson: String): AccountingResult<GstReturnArtifact> =
        repository.importGstReturnOfflineResponse(companyId, gstReturnId, responseJson)

    suspend fun markFiled(companyId: String, gstReturnId: String, acknowledgementNumber: String): AccountingResult<GstReturn> =
        repository.markGstReturnFiled(companyId, gstReturnId, acknowledgementNumber)

    /** Phase 8A, Part 2 - see [com.example.accounting.data.repository.AccountingRepository.setGstReturnNilFlag]'s own KDoc. */
    suspend fun setNilReturn(companyId: String, gstReturnId: String, isNil: Boolean): AccountingResult<GstReturn> =
        repository.setGstReturnNilFlag(companyId, gstReturnId, isNil)

    suspend fun submitOnline(companyId: String, gstReturnId: String, fy: FinancialYear): AccountingResult<GstReturn> =
        repository.submitGstReturnOnline(companyId, gstReturnId, fy, onlineFilingGateway)

    /** Phase 8A, Part 1 - JSON/CSV/GSTR_JSON export of the prepared GSTR-1 draft (see
     * [com.example.accounting.data.repository.AccountingRepository.exportGstReturnAs]). */
    suspend fun exportDraft(companyId: String, gstReturnId: String, fy: FinancialYear, format: com.example.accounting.domain.export.ExportFormat): AccountingResult<com.example.accounting.domain.export.ExportResult> =
        repository.exportGstReturnAs(companyId, gstReturnId, fy, format)

    /** Phase 8A, Part 1 - restores a DRAFT return's sections from a previously-exported JSON of the
     * same shape [exportDraft] produces (portability/backup, never a GST-portal-native importer -
     * see [com.example.accounting.data.repository.AccountingRepository.importGstReturnDraftJson]'s
     * own KDoc for why Tally/Marg/portal-native import formats are explicitly out of scope here). */
    suspend fun importDraft(companyId: String, gstReturnId: String, jsonContent: String): AccountingResult<GstReturn> =
        repository.importGstReturnDraftJson(companyId, gstReturnId, jsonContent)
}
