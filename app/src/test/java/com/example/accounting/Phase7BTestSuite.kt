package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.InvoiceEntity
import com.example.accounting.data.local.entity.InvoiceLineEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.PartyEntity
import com.example.accounting.data.local.entity.TradeDocumentEntity
import com.example.accounting.data.local.entity.TradeDocumentLineEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.document.DocumentStatus
import com.example.accounting.domain.document.DocumentType
import com.example.accounting.domain.document.TradeDocument
import com.example.accounting.domain.document.TradeDocumentLine
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.invoice.Invoice
import com.example.accounting.domain.invoice.InvoiceLine
import com.example.accounting.domain.invoice.InvoiceType
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyEntityType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Phase 7B (Document/Voucher Lifecycle Architecture) - pure JVM tests. [Phase7BAwareDao] follows
 * the exact Phase 4/5 decorator pattern - real in-memory backing for Party/Invoice/TradeDocument
 * data (which [FakeAccountingDao] stubs as no-ops, harmless for Phase 0-7A tests that never
 * exercise it) layered over the base fake. `db == null` throughout - these tests never call
 * `postVoucher`/`postInvoice` (which need a real Room `AppDatabase`), only the pre-posting
 * TradeDocument/draft-Invoice paths, exactly like the Phase 7A test suite's own scope.
 */
class Phase7BTestSuite {

    // Not private - reused as-is by Phase7CTestSuite (report generation needs the same real
    // Party/Invoice backing this class already provides).
    class Phase7BAwareDao(delegate: AccountingDao) : AccountingDao by delegate {
        private val parties = LinkedHashMap<String, PartyEntity>()
        private val invoices = LinkedHashMap<String, InvoiceEntity>()
        private val invoiceLines = mutableListOf<InvoiceLineEntity>()
        private val tradeDocuments = LinkedHashMap<String, TradeDocumentEntity>()
        private val tradeDocumentLines = mutableListOf<TradeDocumentLineEntity>()

        override fun getPartiesByCompany(companyId: String) = flowOf(parties.values.filter { it.companyId == companyId && it.isActive })
        override fun getPartiesByRole(companyId: String, role: PartyRole) = flowOf(parties.values.filter { it.companyId == companyId && it.role == role && it.isActive })
        override suspend fun getPartyById(companyId: String, partyId: String) = parties.values.firstOrNull { it.companyId == companyId && it.partyId == partyId }
        override suspend fun getPartyByLedgerId(companyId: String, ledgerId: String) = parties.values.firstOrNull { it.companyId == companyId && it.ledgerId == ledgerId }
        override suspend fun insertParty(party: PartyEntity) { parties[party.partyId] = party }
        override suspend fun updateParty(party: PartyEntity) { parties[party.partyId] = party }

        override fun getInvoicesByCompany(companyId: String) = flowOf(invoices.values.filter { it.companyId == companyId })
        override fun getInvoicesByParty(companyId: String, partyId: String) = flowOf(invoices.values.filter { it.companyId == companyId && it.partyId == partyId })
        override suspend fun getInvoiceById(companyId: String, invoiceId: String) = invoices.values.firstOrNull { it.companyId == companyId && it.invoiceId == invoiceId }
        override suspend fun getInvoiceByVoucherId(voucherId: String) = invoices.values.firstOrNull { it.voucherId == voucherId }
        override suspend fun getInvoiceCountByType(companyId: String, fyId: String, invoiceType: InvoiceType) =
            invoices.values.count { it.companyId == companyId && it.financialYearId == fyId && it.invoiceType == invoiceType }
        override suspend fun insertInvoice(invoice: InvoiceEntity) { invoices[invoice.invoiceId] = invoice }
        override suspend fun updateInvoice(invoice: InvoiceEntity) { invoices[invoice.invoiceId] = invoice }
        override suspend fun linkInvoiceToVoucher(companyId: String, invoiceId: String, voucherId: String, updatedAt: Long) {
            invoices[invoiceId]?.let { if (it.companyId == companyId) invoices[invoiceId] = it.copy(voucherId = voucherId, updatedAt = updatedAt) }
        }
        override suspend fun deleteDraftInvoice(companyId: String, invoiceId: String): Int {
            val existing = invoices[invoiceId]
            return if (existing != null && existing.companyId == companyId && existing.voucherId == null) { invoices.remove(invoiceId); 1 } else 0
        }
        override suspend fun getLinesForInvoice(invoiceId: String) = invoiceLines.filter { it.invoiceId == invoiceId }.sortedBy { it.lineOrder }
        override suspend fun insertInvoiceLines(lines: List<InvoiceLineEntity>) { invoiceLines += lines }
        override suspend fun deleteLinesForInvoice(invoiceId: String) { invoiceLines.removeAll { it.invoiceId == invoiceId } }

        override fun getTradeDocumentsByCompany(companyId: String) = flowOf(tradeDocuments.values.filter { it.companyId == companyId })
        override fun getTradeDocumentsByType(companyId: String, documentType: DocumentType) = flowOf(tradeDocuments.values.filter { it.companyId == companyId && it.documentType == documentType })
        override suspend fun getTradeDocumentById(companyId: String, tradeDocumentId: String) = tradeDocuments.values.firstOrNull { it.companyId == companyId && it.tradeDocumentId == tradeDocumentId }
        override suspend fun getTradeDocumentCountByType(companyId: String, fyId: String, documentType: DocumentType) =
            tradeDocuments.values.count { it.companyId == companyId && it.financialYearId == fyId && it.documentType == documentType }
        override suspend fun insertTradeDocument(document: TradeDocumentEntity) { tradeDocuments[document.tradeDocumentId] = document }
        override suspend fun updateTradeDocument(document: TradeDocumentEntity) { tradeDocuments[document.tradeDocumentId] = document }
        override suspend fun deleteTradeDocument(companyId: String, tradeDocumentId: String): Int {
            val existing = tradeDocuments[tradeDocumentId]
            return if (existing != null && existing.companyId == companyId) { tradeDocuments.remove(tradeDocumentId); 1 } else 0
        }
        override suspend fun getLinesForTradeDocument(tradeDocumentId: String) = tradeDocumentLines.filter { it.tradeDocumentId == tradeDocumentId }.sortedBy { it.lineOrder }
        override suspend fun insertTradeDocumentLines(lines: List<TradeDocumentLineEntity>) { tradeDocumentLines += lines }
        override suspend fun deleteLinesForTradeDocument(tradeDocumentId: String) { tradeDocumentLines.removeAll { it.tradeDocumentId == tradeDocumentId } }
    }

    private val companyId = "COMP_P7B"
    private val fyId = "FY_P7B_2026_27"

    private fun freshDao() = Phase7BAwareDao(FakeAccountingDao())

    private suspend fun AccountingDao.seedCompanyAndParty(): String {
        insertCompany(CompanyEntity(
            companyId = companyId, name = "Company", tradeName = "Company", gstin = "27AAAAA0000A1Z5",
            pan = "AAAAA0000A", stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
            currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
            accountingMode = AccountingMode.ACCOUNT_WITH_INVENTORY, businessType = BusinessType.TRADING
        ))
        insertFinancialYear(FinancialYearEntity(fyId, companyId, "FY 2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_1", companyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
        val debtorsGroupId = "${StandardSystemGroups.DEBTORS_GROUP_ID}_$companyId"
        insertGroup(GroupEntity(debtorsGroupId, companyId, "Sundry Debtors", PrimaryGroup.ASSETS, null, true, false, 1))
        val ledgerId = "LED_CUSTOMER_1"
        insertLedger(LedgerEntity(ledgerId, companyId, debtorsGroupId, "Acme Corp", "", 0L, DrCr.DEBIT, 0L, DrCr.DEBIT, "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0))
        return ledgerId
    }

    private fun line() = TradeDocumentLine(
        lineId = "", itemId = "ITEM_1", itemName = "Widget", hsnSacCode = "8471",
        quantity = Quantity.fromLong(1), rate = Money.fromRupees(500L), gstRatePercent = 18.0
    )

    private fun invoiceLine() = InvoiceLine(
        lineId = "", itemId = "ITEM_1", itemName = "Widget", hsnSacCode = "8471",
        quantity = Quantity.fromLong(1), rate = Money.fromRupees(500L), gstRatePercent = 18.0
    )

    // ==========================================
    // DocumentType
    // ==========================================
    @Test
    fun testDocumentType_prefixesAreDistinctFromVoucherType_forPostingTypes() {
        assertEquals("SI-", DocumentType.SALES_INVOICE.prefix)
        assertEquals("PB-", DocumentType.PURCHASE_BILL.prefix)
        assertEquals("CN-", DocumentType.CREDIT_NOTE.prefix)
        assertEquals("DN-", DocumentType.DEBIT_NOTE.prefix)
        assertTrue(DocumentType.SALES_INVOICE.isPostingDocument)
        assertTrue(DocumentType.PURCHASE_BILL.isPostingDocument)
        assertTrue(DocumentType.CREDIT_NOTE.isPostingDocument)
        assertTrue(DocumentType.DEBIT_NOTE.isPostingDocument)
    }

    @Test
    fun testDocumentType_nonPostingPrefixesMatchExistingVoucherTypePrefixes() {
        assertEquals("EST-", DocumentType.QUOTATION.prefix)
        assertEquals("PI-", DocumentType.PROFORMA_INVOICE.prefix)
        assertEquals("SO-", DocumentType.SALES_ORDER.prefix)
        assertEquals("PO-", DocumentType.PURCHASE_ORDER.prefix)
        assertEquals("DC-", DocumentType.DELIVERY_NOTE.prefix)
        assertEquals("GRN-", DocumentType.RECEIPT_NOTE.prefix)
        assertTrue(DocumentType.entries.filterNot { it.isPostingDocument }
            .containsAll(listOf(DocumentType.QUOTATION, DocumentType.PROFORMA_INVOICE, DocumentType.SALES_ORDER, DocumentType.PURCHASE_ORDER, DocumentType.DELIVERY_NOTE, DocumentType.RECEIPT_NOTE)))
    }

    // ==========================================
    // Numbering
    // ==========================================
    @Test
    fun testGenerateNextDocumentNumber_incrementsIndependentlyPerType() = runBlocking {
        val dao = freshDao()
        val ledgerId = dao.seedCompanyAndParty()
        val repo = AccountingRepository(dao)

        val first = repo.generateNextDocumentNumber(companyId, fyId, DocumentType.QUOTATION)
        assertEquals("EST-2026-0001", first)

        val partyResult = repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgerId, role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp"))
        val partyId = (partyResult as AccountingResult.Success).data.partyId

        repo.createTradeDocument(
            TradeDocument(tradeDocumentId = "", companyId = companyId, financialYearId = fyId, documentType = DocumentType.QUOTATION, partyId = partyId, date = LocalDate.of(2026, 5, 1)),
            listOf(line())
        )

        val second = repo.generateNextDocumentNumber(companyId, fyId, DocumentType.QUOTATION)
        assertEquals("EST-2026-0002", second)
    }

    // ==========================================
    // Invoice numbering retrofit (Phase 7B changes 7A's createDraftInvoice/postInvoice)
    // ==========================================
    @Test
    fun testCreateDraftInvoice_assignsInvoiceNumberAtCreationTime_neverNull() = runBlocking {
        val dao = freshDao()
        val ledgerId = dao.seedCompanyAndParty()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgerId, role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data

        val result = repo.createDraftInvoice(
            Invoice(invoiceId = "", companyId = companyId, financialYearId = fyId, invoiceType = InvoiceType.SALES_INVOICE, partyId = party.partyId, date = LocalDate.of(2026, 5, 1)),
            listOf(invoiceLine())
        )
        val invoice = (result as AccountingResult.Success).data
        assertNotNull("Phase 7B: invoiceNumber must be assigned at draft-creation time, not left null until posting", invoice.invoiceNumber)
        assertEquals("SI-2026-0001", invoice.invoiceNumber)
        assertNull(invoice.voucherId)
    }

    // ==========================================
    // TradeDocument creation
    // ==========================================
    @Test
    fun testCreateTradeDocument_rejectsPostingDocumentTypes() = runBlocking {
        val dao = freshDao()
        val ledgerId = dao.seedCompanyAndParty()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgerId, role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data

        val result = repo.createTradeDocument(
            TradeDocument(tradeDocumentId = "", companyId = companyId, financialYearId = fyId, documentType = DocumentType.SALES_INVOICE, partyId = party.partyId, date = LocalDate.of(2026, 5, 1)),
            listOf(line())
        )
        assertTrue(result is AccountingResult.Failure)
    }

    @Test
    fun testCreateTradeDocument_rejectsEmptyLines() = runBlocking {
        val dao = freshDao()
        val ledgerId = dao.seedCompanyAndParty()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgerId, role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data

        val result = repo.createTradeDocument(
            TradeDocument(tradeDocumentId = "", companyId = companyId, financialYearId = fyId, documentType = DocumentType.QUOTATION, partyId = party.partyId, date = LocalDate.of(2026, 5, 1)),
            emptyList()
        )
        assertTrue(result is AccountingResult.Failure)
    }

    // ==========================================
    // Issue lifecycle
    // ==========================================
    @Test
    fun testIssueTradeDocument_transitionsDraftToIssued_andRejectsNonDraft() = runBlocking {
        val dao = freshDao()
        val ledgerId = dao.seedCompanyAndParty()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgerId, role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data
        val doc = (repo.createTradeDocument(
            TradeDocument(tradeDocumentId = "", companyId = companyId, financialYearId = fyId, documentType = DocumentType.QUOTATION, partyId = party.partyId, date = LocalDate.of(2026, 5, 1)),
            listOf(line())
        ) as AccountingResult.Success).data

        val issued = (repo.issueTradeDocument(companyId, doc.tradeDocumentId) as AccountingResult.Success).data
        assertEquals(DocumentStatus.ISSUED, issued.status)

        val reissue = repo.issueTradeDocument(companyId, doc.tradeDocumentId)
        assertTrue("Issuing an already-ISSUED document must be rejected", reissue is AccountingResult.Failure)
    }

    // ==========================================
    // Conversion
    // ==========================================
    @Test
    fun testConvertTradeDocument_setsSourceLink_andMarksOriginalConverted() = runBlocking {
        val dao = freshDao()
        val ledgerId = dao.seedCompanyAndParty()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgerId, role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data
        val quotation = (repo.createTradeDocument(
            TradeDocument(tradeDocumentId = "", companyId = companyId, financialYearId = fyId, documentType = DocumentType.QUOTATION, partyId = party.partyId, date = LocalDate.of(2026, 5, 1)),
            listOf(line())
        ) as AccountingResult.Success).data

        val salesOrder = (repo.convertTradeDocument(companyId, quotation.tradeDocumentId, DocumentType.SALES_ORDER) as AccountingResult.Success).data
        assertEquals(quotation.tradeDocumentId, salesOrder.sourceTradeDocumentId)
        assertEquals(DocumentType.SALES_ORDER, salesOrder.documentType)

        val originalAfter = dao.getTradeDocumentById(companyId, quotation.tradeDocumentId)
        assertEquals(DocumentStatus.CONVERTED, originalAfter?.status)
    }

    @Test
    fun testConvertTradeDocument_rejectsAlreadyConvertedSource() = runBlocking {
        val dao = freshDao()
        val ledgerId = dao.seedCompanyAndParty()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgerId, role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data
        val quotation = (repo.createTradeDocument(
            TradeDocument(tradeDocumentId = "", companyId = companyId, financialYearId = fyId, documentType = DocumentType.QUOTATION, partyId = party.partyId, date = LocalDate.of(2026, 5, 1)),
            listOf(line())
        ) as AccountingResult.Success).data
        repo.convertTradeDocument(companyId, quotation.tradeDocumentId, DocumentType.SALES_ORDER)

        val secondConversion = repo.convertTradeDocument(companyId, quotation.tradeDocumentId, DocumentType.SALES_ORDER)
        assertTrue(secondConversion is AccountingResult.Failure)
    }

    @Test
    fun testConvertTradeDocumentToInvoice_createsDraftInvoiceWithSourceLink_andMarksSourceConverted() = runBlocking {
        val dao = freshDao()
        val ledgerId = dao.seedCompanyAndParty()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgerId, role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data
        val salesOrder = (repo.createTradeDocument(
            TradeDocument(tradeDocumentId = "", companyId = companyId, financialYearId = fyId, documentType = DocumentType.SALES_ORDER, partyId = party.partyId, date = LocalDate.of(2026, 5, 1)),
            listOf(line())
        ) as AccountingResult.Success).data

        val invoice = (repo.convertTradeDocumentToInvoice(companyId, salesOrder.tradeDocumentId, InvoiceType.SALES_INVOICE) as AccountingResult.Success).data
        assertEquals(salesOrder.tradeDocumentId, invoice.sourceTradeDocumentId)
        assertNotNull(invoice.invoiceNumber)
        assertNull(invoice.voucherId)

        val originalAfter = dao.getTradeDocumentById(companyId, salesOrder.tradeDocumentId)
        assertEquals(DocumentStatus.CONVERTED, originalAfter?.status)
    }

    // ==========================================
    // Cancellation
    // ==========================================
    @Test
    fun testCancelTradeDocument_draftIsHardDeleted() = runBlocking {
        val dao = freshDao()
        val ledgerId = dao.seedCompanyAndParty()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgerId, role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data
        val doc = (repo.createTradeDocument(
            TradeDocument(tradeDocumentId = "", companyId = companyId, financialYearId = fyId, documentType = DocumentType.QUOTATION, partyId = party.partyId, date = LocalDate.of(2026, 5, 1)),
            listOf(line())
        ) as AccountingResult.Success).data

        repo.cancelTradeDocument(companyId, doc.tradeDocumentId)
        assertNull(dao.getTradeDocumentById(companyId, doc.tradeDocumentId))
    }

    @Test
    fun testCancelTradeDocument_issuedIsMarkedCancelled_notDeleted() = runBlocking {
        val dao = freshDao()
        val ledgerId = dao.seedCompanyAndParty()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgerId, role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data
        val doc = (repo.createTradeDocument(
            TradeDocument(tradeDocumentId = "", companyId = companyId, financialYearId = fyId, documentType = DocumentType.QUOTATION, partyId = party.partyId, date = LocalDate.of(2026, 5, 1)),
            listOf(line())
        ) as AccountingResult.Success).data
        repo.issueTradeDocument(companyId, doc.tradeDocumentId)

        repo.cancelTradeDocument(companyId, doc.tradeDocumentId)
        val after = dao.getTradeDocumentById(companyId, doc.tradeDocumentId)
        assertNotNull("An ISSUED (not DRAFT) document must be preserved, not deleted", after)
        assertEquals(DocumentStatus.CANCELLED, after?.status)
    }

    @Test
    fun testCancelTradeDocument_rejectsConvertedDocument() = runBlocking {
        val dao = freshDao()
        val ledgerId = dao.seedCompanyAndParty()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgerId, role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data
        val quotation = (repo.createTradeDocument(
            TradeDocument(tradeDocumentId = "", companyId = companyId, financialYearId = fyId, documentType = DocumentType.QUOTATION, partyId = party.partyId, date = LocalDate.of(2026, 5, 1)),
            listOf(line())
        ) as AccountingResult.Success).data
        repo.convertTradeDocument(companyId, quotation.tradeDocumentId, DocumentType.SALES_ORDER)

        val cancelResult = repo.cancelTradeDocument(companyId, quotation.tradeDocumentId)
        assertTrue("A CONVERTED document must not be cancellable directly", cancelResult is AccountingResult.Failure)
    }
}
