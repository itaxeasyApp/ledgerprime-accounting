package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GstTransactionEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.export.ExportFormat
import com.example.accounting.domain.export.GstrJsonSerializer
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyEntityType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.SupplyType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7E (Export Architecture & Data Interchange) - pure JVM tests. [Phase7EAwareDao] layers
 * real `GstTransaction` backing on top of [Phase7BTestSuite.Phase7BAwareDao] (already real for
 * Party/Invoice) - the base `FakeAccountingDao`'s GST-transaction methods are permanent no-op/
 * empty-list stubs, so without this override, GST-transaction-dependent export tests would
 * silently exercise nothing.
 */
class Phase7ETestSuite {

    private class Phase7EAwareDao(delegate: AccountingDao) : AccountingDao by delegate {
        private val gstTransactions = mutableListOf<GstTransactionEntity>()
        override suspend fun getGstTransactionsForVoucher(voucherId: String) = gstTransactions.filter { it.voucherId == voucherId }.sortedBy { it.lineOrder }
        override suspend fun getGstTransactionsForCompanyFY(companyId: String, fyId: String) = gstTransactions.filter { it.companyId == companyId && it.financialYearId == fyId }
        override suspend fun insertGstTransactions(transactions: List<GstTransactionEntity>) { gstTransactions += transactions }
    }

    private val companyId = "COMP_P7E"
    private val otherCompanyId = "COMP_P7E_OTHER"
    private val fyId = "FY_P7E_2026_27"

    private fun freshDao() = Phase7EAwareDao(Phase7BTestSuite.Phase7BAwareDao(FakeAccountingDao()))

    private suspend fun AccountingDao.seed(targetCompanyId: String = companyId): Map<String, String> {
        insertCompany(CompanyEntity(
            companyId = targetCompanyId, name = "Apex Traders", tradeName = "Apex Traders", gstin = "27AAAAA0000A1Z5",
            pan = "AAAAA0000A", stateCode = "27", stateName = "Maharashtra", email = "apex@example.com", phone = "9990001111",
            address = "1 Market Street, Mumbai", currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
            accountingMode = AccountingMode.ACCOUNT_ONLY, businessType = BusinessType.TRADING
        ))
        insertFinancialYear(FinancialYearEntity(fyId, targetCompanyId, "FY 2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_$targetCompanyId", targetCompanyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))

        val debtorsLedgerId = "LED_DEBTOR_$targetCompanyId"
        val salesLedgerId = "LED_SALES_$targetCompanyId"
        insertLedger(ledger(debtorsLedgerId, targetCompanyId, StandardSystemGroups.DEBTORS_GROUP_ID))
        insertLedger(ledger(salesLedgerId, targetCompanyId, StandardSystemGroups.SALES_GROUP_ID, DrCr.CREDIT))
        return mapOf("debtors" to debtorsLedgerId, "sales" to salesLedgerId)
    }

    private fun ledger(id: String, targetCompanyId: String, bareGroup: String, openingType: DrCr = DrCr.DEBIT) = LedgerEntity(
        id, targetCompanyId, "${bareGroup}_$targetCompanyId", id, "", 0L, openingType, 0L, openingType,
        "27AAAAA0000A1Z5", "AAAAA0000A", "27", "", "", "1 Buyer Lane, Ünicode Städt", "", "", "", "", false, true, "", 0.0
    )

    private suspend fun postVoucher(
        dao: AccountingDao, voucherId: String, date: String, narration: String,
        debitLedgerId: String, creditLedgerId: String, amountPaise: Long
    ) {
        val entity = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = voucherId,
            voucherType = VoucherType.SALES, date = date, referenceNumber = "", narration = narration,
            totalAmountPaise = amountPaise, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "TESTER", partyGstin = "", isGstApplicable = true
        )
        val items = listOf(
            JournalItemEntity("$voucherId-1", voucherId, companyId, fyId, debitLedgerId, DrCr.DEBIT, amountPaise, "", 1),
            JournalItemEntity("$voucherId-2", voucherId, companyId, fyId, creditLedgerId, DrCr.CREDIT, amountPaise, "", 2)
        )
        VoucherPostingEngine.post(dao, entity, items, "IK_$voucherId", "TESTER")
    }

    private suspend fun insertGst(dao: AccountingDao, voucherId: String, direction: GstDirection, taxable: Long, cgst: Long, sgst: Long, hsn: String = "8471") {
        dao.insertGstTransactions(listOf(
            GstTransactionEntity(
                gstTransactionId = "GST_$voucherId", companyId = companyId, financialYearId = fyId, voucherId = voucherId,
                voucherType = VoucherType.SALES, partyLedgerId = "LED_DEBTOR_$companyId", partyGstin = "27BBBBB1111B1Z5",
                placeOfSupply = "27", supplyType = SupplyType.INTRA_STATE, itemId = "ITEM_1", hsnSacCode = hsn, quantityRaw = 1000L,
                taxableAmountPaise = taxable, gstRatePercent = 18.0, cgstPaise = cgst, sgstPaise = sgst, igstPaise = 0L, cessPaise = 0L,
                direction = direction, lineOrder = 1, createdAt = 0L
            )
        ))
    }

    // ==========================================
    // Voucher export - JSON/CSV, exact paise, security, integrity
    // ==========================================
    @Test
    fun testExportVoucher_includesJournalLinesWithLedgerNamesAndExactPaiseAmounts() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        postVoucher(dao, "V1", "2026-05-10", "Cash sale with ₹ symbol", ledgers.getValue("debtors"), ledgers.getValue("sales"), 12_345_67L)

        val result = repo.exportVoucher(companyId, "V1")
        val dto = (result as AccountingResult.Success).data
        assertEquals(12_345_67L, dto.totalAmountPaise)
        assertEquals(2, dto.journalLines.size)
        assertEquals(12_345_67L, dto.journalLines[0].amountPaise)
        assertEquals("LED_DEBTOR_$companyId", dto.journalLines[0].ledgerId)
    }

    @Test
    fun testExportVoucherAs_json_wrapsVersionedEnvelope() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        postVoucher(dao, "V2", "2026-05-11", "Test", ledgers.getValue("debtors"), ledgers.getValue("sales"), 5_000_00L)

        val result = (repo.exportVoucherAs(companyId, "V2", ExportFormat.JSON) as AccountingResult.Success).data
        assertEquals("1.0", result.metadata.schemaVersion)
        assertEquals(companyId, result.metadata.companyId)
        assertTrue(result.content.contains("\"schemaVersion\":\"1.0\""))
        assertTrue(result.content.contains("\"exportType\":\"VOUCHER\""))
        assertTrue(result.content.contains("\"totalAmountPaise\":500000"))
        // Never a floating-point representation of the authoritative amount.
        assertFalse(result.content.contains("5000.0"))
    }

    @Test
    fun testExportVoucherAs_csv_properlyEscapesCommaQuoteNewlineAndUnicode() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        postVoucher(dao, "V3", "2026-05-12", "Sale, with \"quotes\"\nand a newline, éèç", ledgers.getValue("debtors"), ledgers.getValue("sales"), 1_000_00L)

        val result = (repo.exportVoucherAs(companyId, "V3", ExportFormat.CSV) as AccountingResult.Success).data
        // Week 2 (Play Store update plan) - CsvEngine now leads with a UTF-8 BOM (0xFEFF) so Excel
        // correctly detects the encoding for Unicode content when opened by double-click; this
        // very test's own subject matter is Unicode escaping ("...and a newline, éèç"), so the BOM
        // is exactly the right fix for what this test is about.
        assertTrue(result.content.startsWith(0xFEFF.toChar() + "voucherId,voucherNumber"))
        assertTrue(result.content.contains("\"Sale, with \"\"quotes\"\"\nand a newline, éèç\""))
        assertEquals(3, result.content.trim().split("\r\n").size) // header + 2 journal lines
    }

    @Test
    fun testExportVoucher_crossCompanyAccessReturnsResourceNotFound() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        dao.seed(otherCompanyId)
        val repo = AccountingRepository(dao)
        postVoucher(dao, "V4", "2026-05-13", "Test", ledgers.getValue("debtors"), ledgers.getValue("sales"), 1_00L)

        val result = repo.exportVoucher(otherCompanyId, "V4")
        assertTrue(result is AccountingResult.Failure)
        assertTrue((result as AccountingResult.Failure).error is AppError.ResourceNotFound)
    }

    @Test
    fun testExportVoucher_missingVoucherReturnsResourceNotFound() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val result = repo.exportVoucher(companyId, "DOES_NOT_EXIST")
        assertTrue(result is AccountingResult.Failure)
    }

    @Test
    fun testExportVoucherAs_neverMutatesLedgerBalancesOrJournalItems() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        postVoucher(dao, "V5", "2026-05-14", "Test", ledgers.getValue("debtors"), ledgers.getValue("sales"), 7_000_00L)

        val ledgersBefore = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        repo.exportVoucherAs(companyId, "V5", ExportFormat.JSON)
        repo.exportVoucherAs(companyId, "V5", ExportFormat.CSV)
        val ledgersAfter = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        for ((id, before) in ledgersBefore) {
            assertEquals("Export must never mutate a ledger's balance", before.currentBalancePaise, ledgersAfter.getValue(id).currentBalancePaise)
        }
    }

    // ==========================================
    // Party / Ledger export
    // ==========================================
    @Test
    fun testExportParty_andExportLedger_roundTripFields() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Buyers")) as AccountingResult.Success).data

        val partyDto = (repo.exportParty(companyId, party.partyId) as AccountingResult.Success).data
        assertEquals("Beta Buyers", partyDto.displayName)
        assertEquals("CUSTOMER", partyDto.role)

        val ledgerDto = (repo.exportLedger(companyId, ledgers.getValue("debtors")) as AccountingResult.Success).data
        assertEquals("27AAAAA0000A1Z5", ledgerDto.gstin)
        assertEquals("27", ledgerDto.stateCode)

        val csvResult = (repo.exportLedgerAs(companyId, ledgers.getValue("debtors"), ExportFormat.CSV) as AccountingResult.Success).data
        assertTrue(csvResult.content.contains(ledgers.getValue("debtors")))
    }

    // ==========================================
    // Invoice export - thin wrapper over DocumentData
    // ==========================================
    @Test
    fun testExportInvoiceAs_matchesAssembledDocumentData() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Buyers")) as AccountingResult.Success).data
        postVoucher(dao, "V6", "2026-05-15", "Sale", ledgers.getValue("debtors"), ledgers.getValue("sales"), 118_000_00L)
        insertGst(dao, "V6", GstDirection.OUTPUT, 100_000_00L, 9_000_00L, 9_000_00L)
        dao.insertInvoice(com.example.accounting.data.local.entity.InvoiceEntity(
            invoiceId = "INV6", companyId = companyId, financialYearId = fyId,
            invoiceType = com.example.accounting.domain.invoice.InvoiceType.SALES_INVOICE, invoiceNumber = "SI-2026-0001",
            partyId = customer.partyId, date = "2026-05-15", dueDate = null, voucherId = "V6",
            referenceInvoiceId = null, sourceTradeDocumentId = null, narration = "", createdAt = 0L, updatedAt = 0L
        ))
        dao.insertInvoiceLines(listOf(com.example.accounting.data.local.entity.InvoiceLineEntity("INV6-1", "INV6", "ITEM_1", "Widget", "8471", 1000L, 100_000_00L, 18.0, 0.0, 1)))

        val result = repo.exportInvoiceAs(companyId, com.example.accounting.domain.document.DocumentType.SALES_INVOICE, "INV6", ExportFormat.JSON)
        val exportResult = (result as AccountingResult.Success).data
        assertTrue(exportResult.content.contains("\"cgstPaise\":900000"))
        assertTrue(exportResult.content.contains("\"grandTotalPaise\":11800000"))
        assertTrue(exportResult.content.contains("\"isPosted\":true"))
    }

    // ==========================================
    // Report exports
    // ==========================================
    @Test
    fun testExportTrialBalanceAs_deterministicAcrossRepeatedCalls() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        postVoucher(dao, "V7", "2026-05-16", "Test", ledgers.getValue("debtors"), ledgers.getValue("sales"), 3_000_00L)

        val first = (repo.exportTrialBalanceAs(companyId, fyId, ExportFormat.JSON) as AccountingResult.Success).data
        val second = (repo.exportTrialBalanceAs(companyId, fyId, ExportFormat.JSON) as AccountingResult.Success).data
        // generatedAt legitimately differs run-to-run; compare the data payload only.
        val firstData = first.content.substringAfter("\"data\":")
        val secondData = second.content.substringAfter("\"data\":")
        assertEquals(firstData, secondData)
    }

    @Test
    fun testExportOutstandingAs_csv_emptyDatasetProducesHeaderOnly() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val result = (repo.exportOutstandingAs(companyId, ExportFormat.CSV) as AccountingResult.Success).data
        // Week 2 (Play Store update plan) - CsvEngine now leads with a UTF-8 BOM (0xFEFF).
        assertEquals(listOf(0xFEFF.toChar() + "invoiceId,invoiceNumber,invoiceType,partyId,partyName,voucherNumber,date,dueDate,totalAmountPaise,outstandingAmountPaise,status,daysOutstanding,agingBucket\r\n"), listOf(result.content))
    }

    @Test
    fun testExportFormat_unsupportedCombinationRejectedNotSilentlyWrong() = runBlocking {
        // Rule 32-follow-up (report PDF/CSV parity): Profit & Loss CSV is now supported (was the
        // combination this test used to prove got rejected) - GSTR_JSON remains genuinely
        // unsupported for Profit & Loss (only GST_TRANSACTIONS supports it), so that's the
        // combination this test now proves is still correctly rejected, not silently produced wrong.
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val result = repo.exportProfitAndLossAs(companyId, fyId, ExportFormat.GSTR_JSON)
        assertTrue(result is AccountingResult.Failure)
        assertTrue((result as AccountingResult.Failure).error is AppError.ExportFormatUnsupported)
    }

    @Test
    fun testExportProfitAndLossAs_csv_isNowSupported() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val result = repo.exportProfitAndLossAs(companyId, fyId, ExportFormat.CSV)
        assertTrue("Profit & Loss CSV export must now be supported (report PDF/CSV parity)", result is AccountingResult.Success)
    }

    @Test
    fun testExportBalanceSheetAs_csv_isNowSupported() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val result = repo.exportBalanceSheetAs(companyId, fyId, ExportFormat.CSV)
        assertTrue("Balance Sheet CSV export must now be supported (report PDF/CSV parity)", result is AccountingResult.Success)
    }

    // ==========================================
    // GST transaction export / GSTR JSON
    // ==========================================
    @Test
    fun testExportGstTransactionsAs_gstrJson_groupsByDirectionAndPreservesFacts() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        postVoucher(dao, "V8", "2026-05-17", "Sale", ledgers.getValue("debtors"), ledgers.getValue("sales"), 118_000_00L)
        insertGst(dao, "V8", GstDirection.OUTPUT, 100_000_00L, 9_000_00L, 9_000_00L, hsn = "8471")

        val result = (repo.exportGstTransactionsAs(companyId, fyId, ExportFormat.GSTR_JSON) as AccountingResult.Success).data
        assertTrue(result.content.contains("\"placeOfSupply\":\"27\""))
        assertTrue(result.content.contains("\"hsnSacCode\":\"8471\""))
        assertTrue(result.content.contains("\"partyGstin\":\"27BBBBB1111B1Z5\""))
        // isService is an explicit, never-fabricated extension point - always null today.
        assertTrue(result.content.contains("\"isService\":null"))
        assertTrue(result.content.contains("\"totalTaxableOutwardPaise\":10000000"))
    }

    @Test
    fun testExportGstTransactionsAs_csv_deterministicColumnOrder() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        postVoucher(dao, "V9", "2026-05-18", "Sale", ledgers.getValue("debtors"), ledgers.getValue("sales"), 118_00L)
        insertGst(dao, "V9", GstDirection.OUTPUT, 100_00L, 9_00L, 9_00L)

        val result = (repo.exportGstTransactionsAs(companyId, fyId, ExportFormat.CSV) as AccountingResult.Success).data
        val headerLine = result.content.lines().first()
        // Week 2 (Play Store update plan) - CsvEngine now leads with a UTF-8 BOM (0xFEFF), which
        // lands on the first line since there's no newline before it.
        assertEquals(0xFEFF.toChar() + "gstTransactionId,voucherId,voucherType,partyGstin,placeOfSupply,supplyType,hsnSacCode,isService,taxableAmountPaise,gstRatePercent,cgstPaise,sgstPaise,igstPaise,cessPaise,direction,lineOrder", headerLine)
    }

    @Test
    fun testExportVoucherAs_rejectsGstrJsonFormat_neverSilentlyWrongSerialization() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        postVoucher(dao, "V10", "2026-05-19", "Test", ledgers.getValue("debtors"), ledgers.getValue("sales"), 1_00L)
        val result = repo.exportVoucherAs(companyId, "V10", ExportFormat.GSTR_JSON)
        assertTrue(result is AccountingResult.Failure)
    }

    @Test
    fun testGstrJsonSerializer_isPureFunctionOfItsInputs() {
        val metadata = com.example.accounting.domain.export.ExportMetadata(exportType = com.example.accounting.domain.export.ExportType.GST_TRANSACTIONS, companyId = companyId, generatedAt = 42L)
        val txn = com.example.accounting.domain.export.GSTTransactionExportDto(
            gstTransactionId = "G1", voucherId = "V1", voucherType = VoucherType.SALES, partyGstin = "27X", placeOfSupply = "27",
            supplyType = "INTRA_STATE", hsnSacCode = "8471", taxableAmountPaise = 100L, gstRatePercent = 18.0,
            cgstPaise = 9L, sgstPaise = 9L, igstPaise = 0L, cessPaise = 0L, direction = "OUTPUT", lineOrder = 1
        )
        val json1 = GstrJsonSerializer.serialize(metadata, listOf(txn))
        val json2 = GstrJsonSerializer.serialize(metadata, listOf(txn))
        assertEquals(json1, json2)
    }

    @Test
    fun testExportGstTransactions_nullPartyGstinAndOptionalFieldsHandledSafely() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        postVoucher(dao, "V11", "2026-05-20", "Test", ledgers.getValue("debtors"), ledgers.getValue("sales"), 1_00L)
        dao.insertGstTransactions(listOf(
            GstTransactionEntity(
                gstTransactionId = "GST_V11", companyId = companyId, financialYearId = fyId, voucherId = "V11",
                voucherType = VoucherType.SALES, partyLedgerId = ledgers.getValue("debtors"), partyGstin = "",
                placeOfSupply = "", supplyType = SupplyType.INTRA_STATE, itemId = null, hsnSacCode = "", quantityRaw = null,
                taxableAmountPaise = 100L, gstRatePercent = 0.0, cgstPaise = 0L, sgstPaise = 0L, igstPaise = 0L, cessPaise = 0L,
                direction = GstDirection.OUTPUT, lineOrder = 1, createdAt = 0L
            )
        ))
        val dtos = repo.exportGstTransactions(companyId, fyId)
        assertEquals(1, dtos.size)
        assertNull(dtos[0].isService)
        assertEquals("", dtos[0].partyGstin)
    }
}
