package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.BusinessProfileEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.DocumentAssetEntity
import com.example.accounting.data.local.entity.DocumentTemplateEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GstTransactionEntity
import com.example.accounting.data.local.entity.IndividualProfileEntity
import com.example.accounting.data.local.entity.InvoiceEntity
import com.example.accounting.data.local.entity.InvoiceLineEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.RenderedDocumentRecordEntity
import com.example.accounting.data.local.entity.TradeDocumentEntity
import com.example.accounting.data.local.entity.TradeDocumentLineEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.document.DocumentStatus
import com.example.accounting.domain.document.DocumentType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.invoice.InvoiceType
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyEntityType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.rendering.BusinessProfile
import com.example.accounting.domain.rendering.CsvExporter
import com.example.accounting.domain.rendering.DocumentAssetType
import com.example.accounting.domain.rendering.IndividualProfile
import com.example.accounting.domain.rendering.JsonDocumentRenderer
import com.example.accounting.domain.rendering.TemplateColors
import com.example.accounting.domain.rendering.TemplateLayout
import com.example.accounting.domain.rendering.TemplateStatus
import com.example.accounting.domain.rendering.TemplateVisualConfig
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.SupplyType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7D (Document Template & Rendering Architecture) - pure JVM tests. [Phase7DAwareDao] layers
 * real backing for the 5 new entity types on top of [Phase7BTestSuite.Phase7BAwareDao] (already
 * real for Party/Invoice/TradeDocument). Vouchers are posted directly via
 * [VoucherPostingEngine.post] (Room-independent), matching the established `Phase7CTestSuite`
 * pattern; a GST-bearing posted invoice is set up by inserting a [GstTransactionEntity] directly
 * (the exact shape `TradingWorkflowEngine` would have produced) rather than re-running the full
 * Sales workflow engine, since this suite is testing document ASSEMBLY, not GST posting itself.
 */
class Phase7DTestSuite {

    private class Phase7DAwareDao(delegate: AccountingDao) : AccountingDao by delegate {
        private val templates = mutableListOf<DocumentTemplateEntity>()
        override fun getActiveTemplatesByType(companyId: String, documentType: DocumentType) =
            kotlinx.coroutines.flow.flowOf(templates.filter { it.companyId == companyId && it.documentType == documentType && it.status == TemplateStatus.ACTIVE })
        override suspend fun getActiveTemplateVersion(companyId: String, templateId: String) =
            templates.firstOrNull { it.companyId == companyId && it.templateId == templateId && it.status == TemplateStatus.ACTIVE }
        override suspend fun getTemplateVersion(companyId: String, templateId: String, version: Int) =
            templates.firstOrNull { it.companyId == companyId && it.templateId == templateId && it.version == version }
        override suspend fun getDefaultTemplate(companyId: String, documentType: DocumentType) =
            templates.firstOrNull { it.companyId == companyId && it.documentType == documentType && it.isDefault && it.status == TemplateStatus.ACTIVE }
        override suspend fun getMaxTemplateVersion(companyId: String, templateId: String) =
            templates.filter { it.companyId == companyId && it.templateId == templateId }.maxOfOrNull { it.version }
        override suspend fun insertDocumentTemplate(template: DocumentTemplateEntity) {
            templates.removeAll { it.id == template.id }
            templates += template
        }
        override suspend fun setTemplateStatus(companyId: String, templateId: String, version: Int, status: TemplateStatus) {
            val idx = templates.indexOfFirst { it.companyId == companyId && it.templateId == templateId && it.version == version }
            if (idx >= 0) templates[idx] = templates[idx].copy(status = status)
        }
        override suspend fun clearDefaultTemplateFlag(companyId: String, documentType: DocumentType) {
            for (i in templates.indices) {
                if (templates[i].companyId == companyId && templates[i].documentType == documentType && templates[i].isDefault) {
                    templates[i] = templates[i].copy(isDefault = false)
                }
            }
        }

        private val businessProfiles = mutableMapOf<String, BusinessProfileEntity>()
        override suspend fun getBusinessProfile(companyId: String) = businessProfiles[companyId]
        override suspend fun insertBusinessProfile(profile: BusinessProfileEntity) { businessProfiles[profile.companyId] = profile }
        override suspend fun updateBusinessProfile(
            companyId: String, businessProfileId: String, businessName: String, legalName: String,
            constitutionType: com.example.accounting.domain.rendering.ConstitutionType, address: String,
            pinCode: String, city: String, state: String, country: String,
            phone: String, email: String, website: String, gstin: String, pan: String, tan: String, udyam: String, logoAssetId: String?,
            bankName: String, bankAccountNumber: String, bankIfsc: String, bankBranch: String, upiId: String,
            qrCodeAssetId: String?, signatureAssetId: String?, termsAndConditions: String, updatedAt: Long
        ) {
            val existing = businessProfiles[companyId] ?: return
            if (existing.businessProfileId != businessProfileId) return
            businessProfiles[companyId] = existing.copy(
                businessName = businessName, legalName = legalName, constitutionType = constitutionType, address = address,
                pinCode = pinCode, city = city, state = state, country = country,
                phone = phone, email = email, website = website, gstin = gstin, pan = pan, tan = tan, udyam = udyam,
                logoAssetId = logoAssetId, bankName = bankName,
                bankAccountNumber = bankAccountNumber, bankIfsc = bankIfsc, bankBranch = bankBranch, upiId = upiId,
                qrCodeAssetId = qrCodeAssetId, signatureAssetId = signatureAssetId, termsAndConditions = termsAndConditions,
                updatedAt = updatedAt
            )
        }

        private val individualProfiles = mutableMapOf<String, IndividualProfileEntity>()
        override suspend fun getIndividualProfile(companyId: String) = individualProfiles[companyId]
        override suspend fun insertIndividualProfile(profile: IndividualProfileEntity) { individualProfiles[profile.companyId] = profile }
        override suspend fun updateIndividualProfile(
            companyId: String, individualProfileId: String, name: String, address: String,
            pinCode: String, city: String, state: String, country: String, pan: String,
            phone: String, email: String, signatureAssetId: String?, termsAndConditions: String, updatedAt: Long
        ) {
            val existing = individualProfiles[companyId] ?: return
            if (existing.individualProfileId != individualProfileId) return
            individualProfiles[companyId] = existing.copy(
                name = name, address = address, pinCode = pinCode, city = city, state = state, country = country,
                pan = pan, phone = phone, email = email,
                signatureAssetId = signatureAssetId, termsAndConditions = termsAndConditions, updatedAt = updatedAt
            )
        }

        private val assets = mutableListOf<DocumentAssetEntity>()
        override fun getDocumentAssetsByCompany(companyId: String) = kotlinx.coroutines.flow.flowOf(assets.filter { it.companyId == companyId })
        override suspend fun getDocumentAssetById(companyId: String, assetId: String) = assets.firstOrNull { it.companyId == companyId && it.assetId == assetId }
        override suspend fun insertDocumentAsset(asset: DocumentAssetEntity) { assets += asset }

        // Real GstTransaction backing - the base FakeAccountingDao's own methods are permanent
        // no-op/empty-list stubs (same pattern as Groups pre-Phase-7C), so without this override
        // postSalesInvoiceWithGst's dao.insertGstTransactions(...) call would silently vanish and
        // testAssembleDocumentData_postedSalesInvoice_... would never actually exercise the
        // posted-invoice/GstTransaction code path it claims to.
        private val gstTransactions = mutableListOf<GstTransactionEntity>()
        override suspend fun getGstTransactionsForVoucher(voucherId: String) = gstTransactions.filter { it.voucherId == voucherId }.sortedBy { it.lineOrder }
        override suspend fun getGstTransactionsForCompanyFY(companyId: String, fyId: String) = gstTransactions.filter { it.companyId == companyId && it.financialYearId == fyId }
        override suspend fun insertGstTransactions(transactions: List<GstTransactionEntity>) { gstTransactions += transactions }

        private val renderRecords = mutableListOf<RenderedDocumentRecordEntity>()
        override fun getRenderedDocumentRecords(companyId: String, documentId: String) =
            kotlinx.coroutines.flow.flowOf(renderRecords.filter { it.companyId == companyId && it.documentId == documentId })
        override suspend fun insertRenderedDocumentRecord(record: RenderedDocumentRecordEntity) { renderRecords += record }
    }

    private val companyId = "COMP_P7D"
    private val otherCompanyId = "COMP_P7D_OTHER"
    private val fyId = "FY_P7D_2026_27"

    private fun freshDao() = Phase7DAwareDao(Phase7BTestSuite.Phase7BAwareDao(FakeAccountingDao()))

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
        val creditorsLedgerId = "LED_CREDITOR_$targetCompanyId"
        val salesLedgerId = "LED_SALES_$targetCompanyId"
        val purchaseLedgerId = "LED_PURCHASE_$targetCompanyId"
        insertLedger(ledger(debtorsLedgerId, targetCompanyId, StandardSystemGroups.DEBTORS_GROUP_ID))
        insertLedger(ledger(creditorsLedgerId, targetCompanyId, StandardSystemGroups.CREDITORS_GROUP_ID, DrCr.CREDIT))
        insertLedger(ledger(salesLedgerId, targetCompanyId, StandardSystemGroups.SALES_GROUP_ID, DrCr.CREDIT))
        insertLedger(ledger(purchaseLedgerId, targetCompanyId, StandardSystemGroups.PURCHASE_GROUP_ID))
        return mapOf("debtors" to debtorsLedgerId, "creditors" to creditorsLedgerId, "sales" to salesLedgerId, "purchase" to purchaseLedgerId)
    }

    private fun ledger(id: String, targetCompanyId: String, bareGroup: String, openingType: DrCr = DrCr.DEBIT) = LedgerEntity(
        id, targetCompanyId, "${bareGroup}_$targetCompanyId", id, "", 0L, openingType, 0L, openingType,
        "27AAAAA0000A1Z5", "AAAAA0000A", "27", "", "", "1 Buyer Lane", "", "", "", "", false, true, "", 0.0
    )

    private suspend fun postVoucher(
        dao: AccountingDao, targetCompanyId: String, voucherId: String, voucherType: VoucherType, date: String,
        debitLedgerId: String, creditLedgerId: String, amountPaise: Long
    ) {
        val entity = VoucherEntity(
            voucherId = voucherId, companyId = targetCompanyId, financialYearId = fyId, voucherNumber = voucherId,
            voucherType = voucherType, date = date, referenceNumber = "", narration = "Test $voucherType",
            totalAmountPaise = amountPaise, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "TESTER", partyGstin = "", isGstApplicable = true
        )
        val items = listOf(
            JournalItemEntity("$voucherId-1", voucherId, targetCompanyId, fyId, debitLedgerId, DrCr.DEBIT, amountPaise, "", 1),
            JournalItemEntity("$voucherId-2", voucherId, targetCompanyId, fyId, creditLedgerId, DrCr.CREDIT, amountPaise, "", 2)
        )
        VoucherPostingEngine.post(dao, entity, items, "IK_$voucherId", "TESTER")
    }

    /** Posts a Sales voucher AND inserts the matching [GstTransactionEntity]/[InvoiceEntity] rows
     * directly - the exact shapes `TradingWorkflowEngine`/`postInvoice` would have produced -
     * without re-running the full trading workflow engine (out of scope for this suite, which
     * tests document assembly/rendering, not GST posting). */
    private suspend fun postSalesInvoiceWithGst(
        dao: AccountingDao, targetCompanyId: String, voucherId: String, invoiceId: String, partyId: String,
        debtorsLedgerId: String, salesLedgerId: String, taxableAmountPaise: Long, cgstPaise: Long, sgstPaise: Long
    ): InvoiceEntity {
        val totalPaise = taxableAmountPaise + cgstPaise + sgstPaise
        postVoucher(dao, targetCompanyId, voucherId, VoucherType.SALES, "2026-05-10", debtorsLedgerId, salesLedgerId, totalPaise)
        dao.insertGstTransactions(listOf(
            GstTransactionEntity(
                gstTransactionId = "GST_$voucherId", companyId = targetCompanyId, financialYearId = fyId, voucherId = voucherId,
                voucherType = VoucherType.SALES, partyLedgerId = debtorsLedgerId, partyGstin = "27BBBBB1111B1Z5", placeOfSupply = "27",
                supplyType = SupplyType.INTRA_STATE, itemId = "ITEM_1", hsnSacCode = "8471", quantityRaw = 1000L,
                taxableAmountPaise = taxableAmountPaise, gstRatePercent = 18.0, cgstPaise = cgstPaise, sgstPaise = sgstPaise,
                igstPaise = 0L, cessPaise = 0L, direction = GstDirection.OUTPUT, lineOrder = 1, createdAt = 0L
            )
        ))
        val invoice = InvoiceEntity(
            invoiceId = invoiceId, companyId = targetCompanyId, financialYearId = fyId, invoiceType = InvoiceType.SALES_INVOICE,
            invoiceNumber = "SI-2026-0001", partyId = partyId, date = "2026-05-10", dueDate = "2026-06-09",
            voucherId = voucherId, referenceInvoiceId = null, sourceTradeDocumentId = null, narration = "", createdAt = 0L, updatedAt = 0L
        )
        dao.insertInvoice(invoice)
        dao.insertInvoiceLines(listOf(
            InvoiceLineEntity("$invoiceId-1", invoiceId, "ITEM_1", "Widget", "8471", 1000L, taxableAmountPaise, 18.0, 0.0, 1)
        ))
        return invoice
    }

    private suspend fun createDraftPurchaseBill(dao: AccountingDao, targetCompanyId: String, invoiceId: String, partyId: String): InvoiceEntity {
        val invoice = InvoiceEntity(
            invoiceId = invoiceId, companyId = targetCompanyId, financialYearId = fyId, invoiceType = InvoiceType.PURCHASE_BILL,
            invoiceNumber = "PB-2026-0001", partyId = partyId, date = "2026-05-11", dueDate = null,
            voucherId = null, referenceInvoiceId = null, sourceTradeDocumentId = null, narration = "", createdAt = 0L, updatedAt = 0L
        )
        dao.insertInvoice(invoice)
        dao.insertInvoiceLines(listOf(
            InvoiceLineEntity("$invoiceId-1", invoiceId, "ITEM_2", "Raw Material", "7208", 1000L, 50_000_00L, 18.0, 0.0, 1)
        ))
        return invoice
    }

    private suspend fun createTradeDocument(dao: AccountingDao, targetCompanyId: String, docId: String, partyId: String, documentType: DocumentType): TradeDocumentEntity {
        val doc = TradeDocumentEntity(
            tradeDocumentId = docId, companyId = targetCompanyId, financialYearId = fyId, documentType = documentType,
            documentNumber = "${documentType.prefix}2026-0001", partyId = partyId, date = "2026-05-01",
            status = DocumentStatus.DRAFT, sourceTradeDocumentId = null, narration = "", createdAt = 0L, updatedAt = 0L
        )
        dao.insertTradeDocument(doc)
        dao.insertTradeDocumentLines(listOf(
            TradeDocumentLineEntity("$docId-1", docId, "ITEM_1", "Widget", "8471", 5000L, 100_00L, 18.0, 0.0, 1)
        ))
        return doc
    }

    // ==========================================
    // Document assembly
    // ==========================================
    @Test
    fun testAssembleDocumentData_postedSalesInvoice_usesGstTransactionValuesNotRecomputed() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Buyers")) as AccountingResult.Success).data
        postSalesInvoiceWithGst(dao, companyId, "V_SI1", "INV_SI1", customer.partyId, ledgers.getValue("debtors"), ledgers.getValue("sales"), 100_000_00L, 9_000_00L, 9_000_00L)

        val result = repo.assembleDocumentData(companyId, DocumentType.SALES_INVOICE, "INV_SI1")
        val data = (result as AccountingResult.Success).data

        assertTrue(data.isPosted)
        assertEquals("V_SI1", data.accountingVoucherNumber)
        assertEquals("SI-2026-0001", data.documentNumber)
        assertEquals("Beta Buyers", data.buyer.name)
        assertEquals("Apex Traders", data.seller.name)
        assertEquals(1, data.items.size)
        // Values must come straight from the already-persisted GstTransaction row, not a fresh
        // GstCalculationEngine call - confirmed by using CGST/SGST figures that a NORMAL
        // intra-state 18% split would not itself produce for this taxable amount split evenly.
        assertEquals(100_000_00L, data.items[0].taxableAmount.paise)
        assertEquals(9_000_00L, data.items[0].cgst.paise)
        assertEquals(9_000_00L, data.items[0].sgst.paise)
        assertEquals(118_000_00L, data.totals.grandTotal.paise)
        assertEquals(0L, data.totals.roundOff.paise)
    }

    @Test
    fun testAssembleDocumentData_draftPurchaseBill_computesTaxViaGstEngine() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val supplier = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("creditors"), role = PartyRole.SUPPLIER, entityType = PartyEntityType.BUSINESS, displayName = "Gamma Suppliers")) as AccountingResult.Success).data
        createDraftPurchaseBill(dao, companyId, "INV_PB1", supplier.partyId)

        val result = repo.assembleDocumentData(companyId, DocumentType.PURCHASE_BILL, "INV_PB1")
        val data = (result as AccountingResult.Success).data

        assertFalse(data.isPosted)
        assertNull(data.accountingVoucherNumber)
        // Purchase direction: seller is the Party (supplier), buyer is our own company.
        assertEquals("Gamma Suppliers", data.seller.name)
        assertEquals("Apex Traders", data.buyer.name)
        assertEquals(50_000_00L, data.items[0].taxableAmount.paise)
        assertEquals(18.0, data.items[0].gstRatePercent, 0.0001)
        // Intra-state (both stateCode "27") -> CGST 9% + SGST 9%, no IGST.
        assertEquals(4_500_00L, data.items[0].cgst.paise)
        assertEquals(4_500_00L, data.items[0].sgst.paise)
        assertEquals(0L, data.items[0].igst.paise)
        assertEquals(59_000_00L, data.totals.grandTotal.paise)
    }

    @Test
    fun testAssembleDocumentData_creditNote_referencesOriginalSalesInvoice() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Buyers")) as AccountingResult.Success).data
        val original = postSalesInvoiceWithGst(dao, companyId, "V_SI2", "INV_SI2", customer.partyId, ledgers.getValue("debtors"), ledgers.getValue("sales"), 20_000_00L, 1_800_00L, 1_800_00L)

        val creditNote = InvoiceEntity(
            invoiceId = "INV_CN1", companyId = companyId, financialYearId = fyId, invoiceType = InvoiceType.CREDIT_NOTE,
            invoiceNumber = "CN-2026-0001", partyId = customer.partyId, date = "2026-05-15", dueDate = null,
            voucherId = null, referenceInvoiceId = original.invoiceId, sourceTradeDocumentId = null, narration = "", createdAt = 0L, updatedAt = 0L
        )
        dao.insertInvoice(creditNote)
        dao.insertInvoiceLines(listOf(InvoiceLineEntity("INV_CN1-1", "INV_CN1", "ITEM_1", "Widget", "8471", 1000L, 5_000_00L, 18.0, 0.0, 1)))

        val result = repo.assembleDocumentData(companyId, DocumentType.CREDIT_NOTE, "INV_CN1")
        val data = (result as AccountingResult.Success).data
        assertEquals(original.invoiceId, data.references.referenceDocumentId)
        assertEquals("SI-2026-0001", data.references.referenceDocumentNumber)
    }

    @Test
    fun testAssembleDocumentData_debitNote_isPurchaseDirection() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val supplier = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("creditors"), role = PartyRole.SUPPLIER, entityType = PartyEntityType.BUSINESS, displayName = "Gamma Suppliers")) as AccountingResult.Success).data
        val debitNote = InvoiceEntity(
            invoiceId = "INV_DN1", companyId = companyId, financialYearId = fyId, invoiceType = InvoiceType.DEBIT_NOTE,
            invoiceNumber = "DN-2026-0001", partyId = supplier.partyId, date = "2026-05-16", dueDate = null,
            voucherId = null, referenceInvoiceId = null, sourceTradeDocumentId = null, narration = "", createdAt = 0L, updatedAt = 0L
        )
        dao.insertInvoice(debitNote)
        dao.insertInvoiceLines(listOf(InvoiceLineEntity("INV_DN1-1", "INV_DN1", "ITEM_2", "Raw Material", "7208", 1000L, 10_000_00L, 18.0, 0.0, 1)))

        val result = repo.assembleDocumentData(companyId, DocumentType.DEBIT_NOTE, "INV_DN1")
        val data = (result as AccountingResult.Success).data
        assertEquals("Gamma Suppliers", data.seller.name)
        assertEquals("Apex Traders", data.buyer.name)
    }

    @Test
    fun testAssembleDocumentData_nonPostingTradeDocument_neverHasVoucherNumber() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Buyers")) as AccountingResult.Success).data
        createTradeDocument(dao, companyId, "TRD_Q1", customer.partyId, DocumentType.QUOTATION)

        val result = repo.assembleDocumentData(companyId, DocumentType.QUOTATION, "TRD_Q1")
        val data = (result as AccountingResult.Success).data
        assertFalse(data.isPosted)
        assertNull(data.accountingVoucherNumber)
        assertEquals("EST-2026-0001", data.documentNumber)
        assertEquals(1, data.items.size)
        assertTrue(data.totals.grandTotal.paise > 0L)
    }

    // ==========================================
    // Templates - versioning, default, isolation, invalid config
    // ==========================================
    @Test
    fun testCreateAndUpdateDocumentTemplate_versioningArchivesPreviousVersion() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val v1 = (repo.createDocumentTemplate(companyId, DocumentType.SALES_INVOICE, "Modern Blue", TemplateVisualConfig(colors = TemplateColors(primary = "#000000")), isDefault = true) as AccountingResult.Success).data
        assertEquals(1, v1.version)
        assertEquals(TemplateStatus.ACTIVE, v1.status)

        val v2 = (repo.updateDocumentTemplate(companyId, v1.templateId, visualConfig = TemplateVisualConfig(colors = TemplateColors(primary = "#FFFFFF"))) as AccountingResult.Success).data
        assertEquals(2, v2.version)
        assertEquals(TemplateStatus.ACTIVE, v2.status)
        assertEquals("#FFFFFF", v2.visualConfig.colors.primary)

        // The original version 1 row must still exist, unaltered, just archived - this is what
        // makes a document rendered under v1 stay reproducible after v2 supersedes it.
        val archivedV1 = repo.getDocumentTemplateVersion(companyId, v1.templateId, 1)
        assertNotNull(archivedV1)
        assertEquals(TemplateStatus.ARCHIVED, archivedV1!!.status)
        assertEquals("#000000", archivedV1.visualConfig.colors.primary)
    }

    @Test
    fun testDefaultTemplate_onlyOnePerCompanyAndDocumentType() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val first = (repo.createDocumentTemplate(companyId, DocumentType.SALES_INVOICE, "Classic", isDefault = true) as AccountingResult.Success).data
        val second = (repo.createDocumentTemplate(companyId, DocumentType.SALES_INVOICE, "Modern", isDefault = true) as AccountingResult.Success).data

        val resolvedDefault = repo.resolveTemplateForRender(companyId, DocumentType.SALES_INVOICE)
        assertEquals(second.templateId, resolvedDefault.templateId)

        val firstReloaded = repo.getDocumentTemplateVersion(companyId, first.templateId, 1)
        assertFalse("Creating a second default must clear the first template's isDefault flag", firstReloaded!!.isDefault)
    }

    @Test
    fun testResolveTemplateForRender_fallsBackToBuiltinDefault_whenCompanyHasNoTemplate() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val resolved = repo.resolveTemplateForRender(companyId, DocumentType.SALES_INVOICE)
        assertEquals("BUILTIN_DEFAULT", resolved.templateId)
    }

    @Test
    fun testDocumentTemplates_areCompanyScoped_crossCompanyTemplateAccessRejected() = runBlocking {
        val dao = freshDao()
        dao.seed()
        dao.seed(otherCompanyId)
        val repo = AccountingRepository(dao)
        val template = (repo.createDocumentTemplate(companyId, DocumentType.SALES_INVOICE, "Classic") as AccountingResult.Success).data

        assertTrue(repo.getDocumentTemplatesByType(otherCompanyId, DocumentType.SALES_INVOICE).first().isEmpty())
        val crossCompanyUpdate = repo.updateDocumentTemplate(otherCompanyId, template.templateId, templateName = "Hijacked")
        assertTrue("A company must never be able to update another company's template", crossCompanyUpdate is AccountingResult.Failure)
    }

    @Test
    fun testCreateDocumentTemplate_rejectsBlankName() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val result = repo.createDocumentTemplate(companyId, DocumentType.SALES_INVOICE, "   ")
        assertTrue(result is AccountingResult.Failure)
    }

    // ==========================================
    // Branding - Business/Individual profile, assets
    // ==========================================
    @Test
    fun testBusinessProfile_upsertAndBrandingSnapshotResolvesLogoAndBankDetails() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val logo = (repo.createDocumentAsset(companyId, DocumentAssetType.LOGO, "documents/logo.png", "chk123", "image/png", 2048L) as AccountingResult.Success).data
        val qr = (repo.createDocumentAsset(companyId, DocumentAssetType.QR_CODE, "documents/qr.png", "chk456", "image/png", 1024L) as AccountingResult.Success).data

        val saved = (repo.upsertBusinessProfile(
            BusinessProfile(
                businessProfileId = "", companyId = companyId, businessName = "Apex Traders Pvt Ltd",
                gstin = "27AAAAA0000A1Z5", logoAssetId = logo.assetId, qrCodeAssetId = qr.assetId,
                bankName = "HDFC Bank", bankAccountNumber = "12345678901", bankIfsc = "HDFC0000123",
                upiId = "apex@hdfcbank", termsAndConditions = "Goods once sold will not be taken back."
            )
        ) as AccountingResult.Success).data
        assertEquals("Apex Traders Pvt Ltd", saved.businessName)

        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Buyers")) as AccountingResult.Success).data
        postSalesInvoiceWithGst(dao, companyId, "V_SI3", "INV_SI3", customer.partyId, ledgers.getValue("debtors"), ledgers.getValue("sales"), 10_000_00L, 900_00L, 900_00L)

        val data = (repo.assembleDocumentData(companyId, DocumentType.SALES_INVOICE, "INV_SI3") as AccountingResult.Success).data
        assertEquals("Apex Traders Pvt Ltd", data.seller.name)
        assertEquals("documents/logo.png", data.branding.logoStorageReference)
        assertEquals("documents/qr.png", data.branding.qrCodeStorageReference)
        assertEquals("HDFC Bank", data.paymentInformation.bankName)
        assertEquals("apex@hdfcbank", data.paymentInformation.upiId)
        assertEquals("Goods once sold will not be taken back.", data.terms)
    }

    @Test
    fun testIndividualProfile_separateFromBusinessProfile_bothCoexistPerCompany() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        repo.upsertBusinessProfile(BusinessProfile(businessProfileId = "", companyId = companyId, businessName = "Apex Traders Pvt Ltd"))
        val individual = (repo.upsertIndividualProfile(IndividualProfile(individualProfileId = "", companyId = companyId, name = "Ramesh Kumar", pan = "AAAAA1234A")) as AccountingResult.Success).data

        assertEquals("Ramesh Kumar", individual.name)
        assertNotNull(repo.getBusinessProfile(companyId))
        assertNotNull(repo.getIndividualProfile(companyId))
        assertEquals("Apex Traders Pvt Ltd", repo.getBusinessProfile(companyId)!!.businessName)
    }

    @Test
    fun testDocumentAssets_areCompanyScoped_brandingIsolation() = runBlocking {
        val dao = freshDao()
        dao.seed()
        dao.seed(otherCompanyId)
        val repo = AccountingRepository(dao)
        val asset = (repo.createDocumentAsset(companyId, DocumentAssetType.LOGO, "documents/logo.png", "chk", "image/png", 100L) as AccountingResult.Success).data

        assertNull("Another company must never be able to read this company's asset", repo.getDocumentAsset(otherCompanyId, asset.assetId))
        assertNotNull(repo.getDocumentAsset(companyId, asset.assetId))
    }

    // ==========================================
    // Rendering - deterministic JSON, no accounting mutation
    // ==========================================
    @Test
    fun testJsonDocumentRenderer_sameDocumentDataProducesDeterministicOutput() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Buyers")) as AccountingResult.Success).data
        postSalesInvoiceWithGst(dao, companyId, "V_SI4", "INV_SI4", customer.partyId, ledgers.getValue("debtors"), ledgers.getValue("sales"), 15_000_00L, 1_350_00L, 1_350_00L)

        val data = (repo.assembleDocumentData(companyId, DocumentType.SALES_INVOICE, "INV_SI4") as AccountingResult.Success).data
        val template = repo.resolveTemplateForRender(companyId, DocumentType.SALES_INVOICE)
        val json1 = JsonDocumentRenderer.render(data, template)
        val json2 = JsonDocumentRenderer.render(data, template)
        assertEquals("Rendering the same DocumentData+Template twice must produce byte-identical JSON", json1, json2)
        assertTrue(json1.contains("\"documentNumber\":\"SI-2026-0001\""))
        assertTrue(json1.contains("\"schemaVersion\":1"))
    }

    @Test
    fun testRenderDocumentAsJson_neverMutatesLedgerBalancesOrRecalculatesGst() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Buyers")) as AccountingResult.Success).data
        postSalesInvoiceWithGst(dao, companyId, "V_SI5", "INV_SI5", customer.partyId, ledgers.getValue("debtors"), ledgers.getValue("sales"), 8_000_00L, 720_00L, 720_00L)

        val ledgersBefore = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        val gstBefore = dao.getGstTransactionsForVoucher("V_SI5")

        val jsonResult = repo.renderDocumentAsJson(companyId, DocumentType.SALES_INVOICE, "INV_SI5")
        assertTrue(jsonResult is AccountingResult.Success)

        val ledgersAfter = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        for ((id, before) in ledgersBefore) {
            assertEquals("Rendering must never mutate a ledger's balance", before.currentBalancePaise, ledgersAfter.getValue(id).currentBalancePaise)
        }
        val gstAfter = dao.getGstTransactionsForVoucher("V_SI5")
        assertEquals("Rendering must never touch the persisted GstTransaction rows", gstBefore, gstAfter)
    }

    @Test
    fun testCsvExporter_consumesDocumentDataOnly_neverRecalculatesTotals() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Buyers")) as AccountingResult.Success).data
        postSalesInvoiceWithGst(dao, companyId, "V_SI6", "INV_SI6", customer.partyId, ledgers.getValue("debtors"), ledgers.getValue("sales"), 5_000_00L, 450_00L, 450_00L)
        val data = (repo.assembleDocumentData(companyId, DocumentType.SALES_INVOICE, "INV_SI6") as AccountingResult.Success).data

        val csv = CsvExporter.exportDocumentLines(data)
        assertTrue(csv.contains("Grand Total"))
        assertTrue(csv.contains("5900.0")) // 5000 + 450 + 450 = 5900.00 grand total
    }

    // ==========================================
    // Security - cross-company document access rejected
    // ==========================================
    @Test
    fun testAssembleDocumentData_crossCompanyInvoiceAccessRejected() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        dao.seed(otherCompanyId)
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Buyers")) as AccountingResult.Success).data
        postSalesInvoiceWithGst(dao, companyId, "V_SI7", "INV_SI7", customer.partyId, ledgers.getValue("debtors"), ledgers.getValue("sales"), 1_000_00L, 90_00L, 90_00L)

        val result = repo.assembleDocumentData(otherCompanyId, DocumentType.SALES_INVOICE, "INV_SI7")
        assertTrue("A company must never be able to assemble another company's invoice", result is AccountingResult.Failure)
    }

    // ==========================================
    // Historical integrity - template edit does not change accounting or old renders
    // ==========================================
    @Test
    fun testHistoricalImmutability_editingTemplateDoesNotChangeAccountingOrPastRenderRecord() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Buyers")) as AccountingResult.Success).data
        postSalesInvoiceWithGst(dao, companyId, "V_SI8", "INV_SI8", customer.partyId, ledgers.getValue("debtors"), ledgers.getValue("sales"), 12_000_00L, 1_080_00L, 1_080_00L)

        val v1 = (repo.createDocumentTemplate(companyId, DocumentType.SALES_INVOICE, "Classic", TemplateVisualConfig(layout = TemplateLayout(showLogo = true)), isDefault = true) as AccountingResult.Success).data
        val ledgersBefore = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }

        repo.renderDocumentAsJson(companyId, DocumentType.SALES_INVOICE, "INV_SI8", v1.templateId)
        val recordsAfterFirstRender = dao.getRenderedDocumentRecords(companyId, "INV_SI8").first()
        assertEquals(1, recordsAfterFirstRender.size)
        assertEquals(1, recordsAfterFirstRender.first().templateVersion)

        // Edit the template (creates version 2) - the FIRST render's logged record must still
        // point at version 1, and the underlying accounting data must be completely unchanged.
        repo.updateDocumentTemplate(companyId, v1.templateId, visualConfig = TemplateVisualConfig(layout = TemplateLayout(showLogo = false)))

        val recordsStillLogged = dao.getRenderedDocumentRecords(companyId, "INV_SI8").first()
        assertEquals(1, recordsStillLogged.size)
        assertEquals("The already-logged render must still reference the exact version it used", 1, recordsStillLogged.first().templateVersion)

        val v1StillIntact = repo.getDocumentTemplateVersion(companyId, v1.templateId, 1)
        assertTrue("Version 1's own content must never be mutated by editing to version 2", v1StillIntact!!.visualConfig.layout.showLogo)

        val ledgersAfter = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        for ((id, before) in ledgersBefore) {
            assertEquals("Editing a template must never change any ledger balance", before.currentBalancePaise, ledgersAfter.getValue(id).currentBalancePaise)
        }

        // Regenerating now (no explicit templateId) picks up the NEW default (v2), while the old
        // record from before the edit is untouched - both are simultaneously true and correct.
        repo.renderDocumentAsJson(companyId, DocumentType.SALES_INVOICE, "INV_SI8")
        val recordsAfterSecondRender = dao.getRenderedDocumentRecords(companyId, "INV_SI8").first()
        assertEquals(2, recordsAfterSecondRender.size)
        assertTrue(recordsAfterSecondRender.any { it.templateVersion == 1 })
        assertTrue(recordsAfterSecondRender.any { it.templateVersion == 2 })
    }
}
