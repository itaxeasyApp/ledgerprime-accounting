package com.example.accounting

import com.example.accounting.Phase7JBFixtures.seedCompanyAndFy
import com.example.accounting.application.imports.DataImportManagementService
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.data.dataimport.CsvJsonDataImportAdapter
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.dataimport.ImportRowSuggestion
import com.example.accounting.domain.dataimport.ImportSuggestionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for the group-id existence/ownership check added to
 * [DataImportManagementService.reviewAndCreate]'s `LEDGER` branch - the one concrete
 * silent-corruption risk the read-only Import/Export/Migration/Reconciliation audit found
 * (`AccountingRepository.createLedger` never validated `groupId` on its own). Verifies the fix
 * closes that gap without touching `createLedger`, `Ledger`, or any report calculation - the check
 * lives entirely in this service, before `createLedger` is ever called.
 */
class LedgerImportGroupValidationTestSuite {

    private val companyId = Phase7JBFixtures.COMPANY_ID
    private val otherCompanyId = "COMP_OTHER"

    private fun freshDao() = Phase7JBAwareDao(Phase7BTestSuite.Phase7BAwareDao(FakeAccountingDao()))

    private fun ledgerSuggestion(groupIdColumnValue: String, name: String = "Imported Ledger") = ImportRowSuggestion(
        rowNumber = 1, suggestionType = ImportSuggestionType.LEDGER,
        fieldValues = mapOf("name" to name, "groupid" to groupIdColumnValue), confidenceScore = 1.0
    )

    @Test
    fun testReviewAndCreate_ledgerWithValidGroupIdForThisCompany_succeedsAndCreatesLedger() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        dao.insertGroup(GroupEntity("GRP_VALID", companyId, "Valid Group", PrimaryGroup.ASSETS, null, false, false, 1))
        val repository = AccountingRepository(dao, db = null)
        val service = DataImportManagementService(CsvJsonDataImportAdapter(dao), repository)

        val result = service.reviewAndCreate(companyId, ledgerSuggestion("GRP_VALID"), ImportSuggestionType.LEDGER)

        assertTrue(result is AccountingResult.Success)
        val ledgers = repository.getLedgers(companyId).first()
        assertEquals(1, ledgers.size)
        assertEquals("GRP_VALID", ledgers.first().groupId)
    }

    @Test
    fun testReviewAndCreate_nonexistentGroupId_rejectedAndNoLedgerCreated() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)
        val service = DataImportManagementService(CsvJsonDataImportAdapter(dao), repository)

        val result = service.reviewAndCreate(companyId, ledgerSuggestion("GRP_DOES_NOT_EXIST"), ImportSuggestionType.LEDGER)

        assertTrue(result is AccountingResult.Failure)
        val message = (result as AccountingResult.Failure).error.message
        assertTrue("expected the rejection message to name the missing group, was: $message", message?.contains("GRP_DOES_NOT_EXIST") == true)
        assertTrue(repository.getLedgers(companyId).first().isEmpty())
    }

    @Test
    fun testReviewAndCreate_groupIdBelongsToAnotherCompany_rejectedAndNoLedgerCreated() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        dao.seedCompanyAndFy(companyId = otherCompanyId, fyId = "FY_OTHER")
        // The group is real, but only for otherCompanyId - never for companyId.
        dao.insertGroup(GroupEntity("GRP_OTHER_CO", otherCompanyId, "Other Co Group", PrimaryGroup.ASSETS, null, false, false, 1))
        val repository = AccountingRepository(dao, db = null)
        val service = DataImportManagementService(CsvJsonDataImportAdapter(dao), repository)

        val result = service.reviewAndCreate(companyId, ledgerSuggestion("GRP_OTHER_CO"), ImportSuggestionType.LEDGER)

        assertTrue(result is AccountingResult.Failure)
        assertTrue(repository.getLedgers(companyId).first().isEmpty())
        // And it must not have leaked into the other company's ledgers either - this call was
        // scoped to companyId throughout, never otherCompanyId.
        assertTrue(repository.getLedgers(otherCompanyId).first().isEmpty())
    }

    @Test
    fun testReviewAndCreate_missingGroupIdColumn_stillRejectedBeforeTheNewCheckEvenRuns() = runBlocking {
        // Pre-existing behavior (missing-required-column validation) must keep working unchanged -
        // this suggestion has no "groupid"/"group" column at all, so the existing firstNonBlank
        // check must still reject it, never reaching the new group-existence lookup.
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)
        val service = DataImportManagementService(CsvJsonDataImportAdapter(dao), repository)
        val suggestion = ImportRowSuggestion(
            rowNumber = 1, suggestionType = ImportSuggestionType.LEDGER,
            fieldValues = mapOf("name" to "Imported Ledger"), confidenceScore = 1.0
        )

        val result = service.reviewAndCreate(companyId, suggestion, ImportSuggestionType.LEDGER)

        assertTrue(result is AccountingResult.Failure)
        val message = (result as AccountingResult.Failure).error.message
        assertTrue("expected the pre-existing 'missing group id column' message, was: $message", message?.contains("group id column") == true)
        assertTrue(repository.getLedgers(companyId).first().isEmpty())
    }

    /**
     * Import-review group-picker fix - [groupIdOverride] (the reviewer's own dropdown choice) must
     * win even when the file's own "groupid" column exists and is different, since the file column
     * is never something a real external Balance Sheet export could reliably supply as this app's
     * internal groupId string.
     */
    @Test
    fun testReviewAndCreate_groupIdOverride_winsOverFileColumn() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        dao.insertGroup(GroupEntity("GRP_FROM_PICKER", companyId, "Bank OD A/c", PrimaryGroup.LIABILITIES, null, true, false, 1))
        val repository = AccountingRepository(dao, db = null)
        val service = DataImportManagementService(CsvJsonDataImportAdapter(dao), repository)

        // The file column names a group that does not even exist - proving it is never consulted
        // once an override is supplied.
        val result = service.reviewAndCreate(companyId, ledgerSuggestion("GRP_DOES_NOT_EXIST"), ImportSuggestionType.LEDGER, groupIdOverride = "GRP_FROM_PICKER")

        assertTrue(result is AccountingResult.Success)
        val ledgers = repository.getLedgers(companyId).first()
        assertEquals(1, ledgers.size)
        assertEquals("GRP_FROM_PICKER", ledgers.first().groupId)
    }

    /** [groupIdOverride] alone must be sufficient - a file with no group column at all (the normal
     * case for a real external Balance Sheet export) must still succeed once the reviewer picks a
     * Group in the UI. */
    @Test
    fun testReviewAndCreate_groupIdOverride_worksWithNoFileGroupColumnAtAll() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        dao.insertGroup(GroupEntity("GRP_FROM_PICKER", companyId, "Secured Loans", PrimaryGroup.LIABILITIES, null, true, false, 1))
        val repository = AccountingRepository(dao, db = null)
        val service = DataImportManagementService(CsvJsonDataImportAdapter(dao), repository)
        val suggestion = ImportRowSuggestion(
            rowNumber = 1, suggestionType = ImportSuggestionType.LEDGER,
            fieldValues = mapOf("name" to "Imported Ledger"), confidenceScore = 1.0
        )

        val result = service.reviewAndCreate(companyId, suggestion, ImportSuggestionType.LEDGER, groupIdOverride = "GRP_FROM_PICKER")

        assertTrue(result is AccountingResult.Success)
        val ledgers = repository.getLedgers(companyId).first()
        assertEquals(1, ledgers.size)
        assertEquals("GRP_FROM_PICKER", ledgers.first().groupId)
    }
}
