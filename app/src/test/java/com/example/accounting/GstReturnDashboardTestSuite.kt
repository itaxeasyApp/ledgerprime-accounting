package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.GstReturnArtifactEntity
import com.example.accounting.data.local.entity.GstReturnEntity
import com.example.accounting.data.local.entity.GstReturnSectionEntity
import com.example.accounting.data.local.entity.GstReturnSubmissionEntity
import com.example.accounting.data.local.entity.GstTransactionEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.StockItemEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.local.entity.VoucherStockLineEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.JournalItem
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.inventory.VoucherStockLine
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstLedgerIds
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gstreturn.GstFilingMode
import com.example.accounting.domain.taxation.gstreturn.GstOnlineFilingGateway
import com.example.accounting.domain.taxation.gstreturn.GstOnlineFilingResult
import com.example.accounting.domain.taxation.gstreturn.GstPeriod
import com.example.accounting.domain.taxation.gstreturn.GstQuarter
import com.example.accounting.domain.taxation.gstreturn.GstReturnApplicability
import com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity
import com.example.accounting.domain.taxation.gstreturn.GstReturnStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnStatusTransitions
import com.example.accounting.domain.taxation.gstreturn.GstReturnType
import com.example.accounting.domain.taxation.gstreturn.GstScheme
import com.example.accounting.domain.trading.LedgerRef
import com.example.accounting.domain.trading.TradingGstLedgers
import com.example.accounting.domain.trading.TradingLineInput
import com.example.accounting.domain.trading.TradingWorkflowEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Rule 33 - GST Return Dashboard & Filing Foundation test suite. Follows the exact
 * [Phase5TestSuite] pattern (real [VoucherPostingEngine]/[AccountingRepository] logic against a
 * fake-but-real-backed DAO, never mocking the class under test) - a separate file since this is a
 * genuinely different subsystem (the return lifecycle, not GST calculation/posting itself), the
 * same way [Phase7JBReportManagementTestSuite] and other feature areas already get their own file.
 */
class GstReturnDashboardTestSuite {

    private class GstReturnAwareDao(delegate: AccountingDao) : AccountingDao by delegate {
        private val gstTransactions = mutableListOf<GstTransactionEntity>()
        private val gstReturns = LinkedHashMap<String, GstReturnEntity>()
        private val artifacts = mutableListOf<GstReturnArtifactEntity>()
        private val sections = LinkedHashMap<String, GstReturnSectionEntity>() // key: gstReturnId|sectionKey
        private val submissions = mutableListOf<GstReturnSubmissionEntity>()

        override suspend fun getGstTransactionsForVoucher(voucherId: String) = gstTransactions.filter { it.voucherId == voucherId }
        override suspend fun getGstTransactionsForCompanyFY(companyId: String, fyId: String) =
            gstTransactions.filter { it.companyId == companyId && it.financialYearId == fyId }
        override suspend fun insertGstTransactions(transactions: List<GstTransactionEntity>) { gstTransactions += transactions }

        override fun getGstReturnsForCompany(companyId: String) =
            kotlinx.coroutines.flow.flowOf(gstReturns.values.filter { it.companyId == companyId }.sortedByDescending { it.createdAt })
        override suspend fun getGstReturnById(companyId: String, gstReturnId: String) =
            gstReturns[gstReturnId]?.takeIf { it.companyId == companyId }
        override suspend fun findGstReturn(companyId: String, periodKey: String, returnType: String, scheme: String) =
            gstReturns.values.firstOrNull { it.companyId == companyId && it.periodKey == periodKey && it.returnType.name == returnType && it.scheme.name == scheme }
        override suspend fun insertGstReturn(gstReturn: GstReturnEntity) { gstReturns[gstReturn.gstReturnId] = gstReturn }
        override suspend fun updateGstReturn(gstReturn: GstReturnEntity) { gstReturns[gstReturn.gstReturnId] = gstReturn }

        override suspend fun getArtifactsForGstReturn(gstReturnId: String) = artifacts.filter { it.gstReturnId == gstReturnId }.sortedBy { it.createdAt }
        override suspend fun getGstReturnArtifactById(artifactId: String) = artifacts.firstOrNull { it.artifactId == artifactId }
        override suspend fun insertGstReturnArtifact(artifact: GstReturnArtifactEntity) { artifacts += artifact }

        override suspend fun getSectionsForGstReturn(gstReturnId: String) = sections.values.filter { it.gstReturnId == gstReturnId }.sortedBy { it.sectionKey }
        override suspend fun upsertGstReturnSection(section: GstReturnSectionEntity) { sections["${section.gstReturnId}|${section.sectionKey}"] = section }
        override suspend fun deleteGstReturnSectionsNotIn(gstReturnId: String, keepKeys: List<String>) {
            sections.keys.filter { it.startsWith("$gstReturnId|") && sections[it]?.sectionKey !in keepKeys }.forEach { sections.remove(it) }
        }

        override suspend fun getSubmissionsForGstReturn(gstReturnId: String) = submissions.filter { it.gstReturnId == gstReturnId }.sortedBy { it.attemptNumber }
        override suspend fun insertGstReturnSubmission(submission: GstReturnSubmissionEntity) { submissions += submission }
    }

    private val companyId = "COMP_R33"
    private val fyId = "FY_R33_2026_27"
    private val fy = FinancialYear.createIndianFY(fyId, companyId, 2026, isCurrent = true)

    private fun freshDao() = GstReturnAwareDao(Phase4TestSuite.InventoryAwareDao(FakeAccountingDao()))

    private suspend fun AccountingDao.seedCompany(scheme: GstScheme = GstScheme.REGULAR) {
        insertCompany(
            CompanyEntity(
                companyId = companyId, name = "Company $companyId", tradeName = "Company $companyId", gstin = "27AAAAA0000A1Z5",
                pan = "AAAAA0000A", stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
                currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
                accountingMode = AccountingMode.ACCOUNT_WITH_INVENTORY, businessType = BusinessType.TRADING, gstScheme = scheme
            )
        )
        insertFinancialYear(FinancialYearEntity(fyId, companyId, "2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_$companyId", companyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
        insertGroups(StandardSystemGroups.getStandardGroupsForCompany(companyId).map {
            GroupEntity(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        })
    }

    private fun ledger(id: String, groupBare: String, openingType: DrCr = DrCr.DEBIT, stateCode: String = "27") =
        LedgerEntity(id, companyId, "${groupBare}_$companyId", id, id, 0L, openingType, 0L, openingType, "", "", stateCode, "", "", "", "", "", "", "", false, true, "", 0.0)

    private suspend fun AccountingDao.seedTradingLedgers() {
        insertLedger(ledger("LED_DEBTOR", StandardSystemGroups.DEBTORS_GROUP_ID))
        insertLedger(ledger("LED_DEBTOR_INTERSTATE", StandardSystemGroups.DEBTORS_GROUP_ID, stateCode = "09"))
        insertLedger(ledger("LED_CREDITOR", StandardSystemGroups.CREDITORS_GROUP_ID, openingType = DrCr.CREDIT))
        insertLedger(ledger("LED_SALES", StandardSystemGroups.SALES_GROUP_ID, openingType = DrCr.CREDIT))
        insertLedger(ledger("LED_PURCHASE", StandardSystemGroups.PURCHASE_GROUP_ID))
        insertLedger(ledger(StandardSystemGroups.ROUND_OFF_LEDGER_ID + "_$companyId", StandardSystemGroups.ROUND_OFF_GROUP_ID))
    }

    private fun stockItem(itemId: String, openingQty: Long = 100_000L) = StockItemEntity(
        itemId = itemId, companyId = companyId, name = itemId, sku = itemId, hsnCode = "8471", unit = "Pcs", gstRatePercent = 18.0,
        openingQuantity = openingQty, openingRatePaise = 100_00L, currentQuantity = openingQty, standardCostPaise = 100_00L,
        standardSellingPricePaise = 100_00L, currentAvgCostPaise = 100_00L
    )

    private fun gstLedgerRefs() = TradingGstLedgers(
        outputCgst = LedgerRef("${GstLedgerIds.OUTPUT_CGST_LEDGER_ID}_$companyId", "Output CGST A/c"),
        outputSgst = LedgerRef("${GstLedgerIds.OUTPUT_SGST_LEDGER_ID}_$companyId", "Output SGST A/c"),
        outputIgst = LedgerRef("${GstLedgerIds.OUTPUT_IGST_LEDGER_ID}_$companyId", "Output IGST A/c"),
        inputCgst = LedgerRef("${GstLedgerIds.INPUT_CGST_LEDGER_ID}_$companyId", "Input CGST A/c"),
        inputSgst = LedgerRef("${GstLedgerIds.INPUT_SGST_LEDGER_ID}_$companyId", "Input SGST A/c"),
        inputIgst = LedgerRef("${GstLedgerIds.INPUT_IGST_LEDGER_ID}_$companyId", "Input IGST A/c"),
        cess = LedgerRef("${GstLedgerIds.CESS_LEDGER_ID}_$companyId", "CESS A/c"),
        rcmLiabilityCgst = LedgerRef("${GstLedgerIds.RCM_LIABILITY_CGST_LEDGER_ID}_$companyId", "RCM Liability CGST A/c"),
        rcmLiabilitySgst = LedgerRef("${GstLedgerIds.RCM_LIABILITY_SGST_LEDGER_ID}_$companyId", "RCM Liability SGST A/c"),
        rcmLiabilityIgst = LedgerRef("${GstLedgerIds.RCM_LIABILITY_IGST_LEDGER_ID}_$companyId", "RCM Liability IGST A/c"),
        rcmInputCgst = LedgerRef("${GstLedgerIds.RCM_INPUT_CGST_LEDGER_ID}_$companyId", "RCM Input CGST A/c"),
        rcmInputSgst = LedgerRef("${GstLedgerIds.RCM_INPUT_SGST_LEDGER_ID}_$companyId", "RCM Input SGST A/c"),
        rcmInputIgst = LedgerRef("${GstLedgerIds.RCM_INPUT_IGST_LEDGER_ID}_$companyId", "RCM Input IGST A/c")
    )

    private fun JournalItem.toEntity() = JournalItemEntity(itemId, voucherId, companyId, financialYearId, ledgerId, type, amount.paise, narration, lineOrder)
    private fun VoucherStockLine.toEntity() = VoucherStockLineEntity(lineId, voucherId, companyId, financialYearId, itemId, direction, quantity.rawValue, rate.paise, amount.paise, lineOrder)
    private fun GstTransaction.toEntity() = GstTransactionEntity(
        gstTransactionId, companyId, financialYearId, voucherId, voucherType, partyLedgerId, partyGstin, placeOfSupply,
        supplyType, itemId, hsnSacCode, quantity?.rawValue, taxableAmount.paise, gstRatePercent, cgst.paise, sgst.paise,
        igst.paise, cess.paise, direction, lineOrder, createdAt = 0L, chargeType = chargeType
    )

    private fun line(itemId: String, qty: Long, ratePaise: Long, gstRate: Double, chargeType: GstChargeType = GstChargeType.FORWARD_CHARGE) =
        TradingLineInput(itemId = itemId, itemName = itemId, hsnSacCode = "8471", quantity = Quantity.fromLong(qty), rate = Money.fromPaise(ratePaise), gstRatePercent = gstRate, chargeType = chargeType)

    private suspend fun postResult(
        dao: AccountingDao, voucherId: String, voucherType: VoucherType, result: com.example.accounting.domain.trading.TradingWorkflowResult, date: String
    ) {
        val voucherEntity = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = voucherId, voucherType = voucherType,
            date = date, referenceNumber = "", narration = "", totalAmountPaise = result.totalAmount.paise,
            isPosted = true, isCancelled = false, syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L,
            createdBy = "TESTER", partyGstin = "", isGstApplicable = true, referenceVoucherId = null, paymentMode = ""
        )
        VoucherPostingEngine.post(
            dao, voucherEntity, result.journalItems.map { it.toEntity() }, "IK_$voucherId", "TESTER",
            result.stockLines.map { it.toEntity() }, result.gstTransactions.map { it.toEntity() }
        )
    }

    /** Posts one intra-state (companyState=27) forward-charge Sale on [date], for [amountPaise]
     * taxable @18%, against LED_DEBTOR. */
    private suspend fun postSale(dao: AccountingDao, voucherId: String, date: String, amountPaise: Long = 1000_00L, chargeType: GstChargeType = GstChargeType.FORWARD_CHARGE) {
        val result = TradingWorkflowEngine.buildSale(
            voucherId, companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 1, amountPaise, 18.0, chargeType)), gstLedgerRefs(),
            "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId", "Round Off"
        )
        postResult(dao, voucherId, VoucherType.SALES, result, date)
    }

    private suspend fun postInterStateSale(dao: AccountingDao, voucherId: String, date: String, amountPaise: Long = 1000_00L) {
        val result = TradingWorkflowEngine.buildSale(
            voucherId, companyId, fyId, "LED_DEBTOR_INTERSTATE", "Cust", "", "LED_SALES", "Sales", "27", "09",
            listOf(line("ITEM_A", 1, amountPaise, 18.0)), gstLedgerRefs(),
            "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId", "Round Off"
        )
        postResult(dao, voucherId, VoucherType.SALES, result, date)
    }

    private suspend fun postPurchase(dao: AccountingDao, voucherId: String, date: String, amountPaise: Long = 1000_00L, chargeType: GstChargeType = GstChargeType.FORWARD_CHARGE, placeOfSupply: String = "27") {
        val result = TradingWorkflowEngine.buildPurchase(
            voucherId, companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase", "27", placeOfSupply,
            listOf(line("ITEM_A", 1, amountPaise, 18.0, chargeType)), gstLedgerRefs(),
            "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId", "Round Off"
        )
        postResult(dao, voucherId, VoucherType.PURCHASE, result, date)
    }

    private suspend fun setup(scheme: GstScheme = GstScheme.REGULAR): Pair<AccountingDao, AccountingRepository> {
        val dao = freshDao()
        dao.seedCompany(scheme)
        dao.seedTradingLedgers()
        dao.insertStockItems(listOf(stockItem("ITEM_A")))
        val repo = AccountingRepository(dao)
        repo.ensureGstLedgersExist(companyId)
        return dao to repo
    }

    // ==========================================
    // 1-6. FY / Quarter / Month mapping
    // ==========================================
    @Test
    fun t1_FinancialYear_CreationAndSelection_ResolvesRealDates() {
        assertEquals("2026-27", fy.fyCode)
        assertEquals(LocalDate.of(2026, 4, 1), fy.startDate)
        assertEquals(LocalDate.of(2027, 3, 31), fy.endDate)
    }

    @Test
    fun t2_Q1_MapsToAprMayJun_AndCorrectDateRange() {
        val period = GstPeriod.of(fy, GstQuarter.Q1)
        assertEquals(listOf(4, 5, 6), GstQuarter.Q1.months)
        assertEquals(LocalDate.of(2026, 4, 1)..LocalDate.of(2026, 6, 30), period.dateRange())
        assertEquals("2026-27-Q1", period.periodKey)
    }

    @Test
    fun t3_Q2_MapsToJulAugSep_AndCorrectDateRange() {
        val period = GstPeriod.of(fy, GstQuarter.Q2)
        assertEquals(listOf(7, 8, 9), GstQuarter.Q2.months)
        assertEquals(LocalDate.of(2026, 7, 1)..LocalDate.of(2026, 9, 30), period.dateRange())
    }

    @Test
    fun t4_Q3_MapsToOctNovDec_AndCorrectDateRange() {
        val period = GstPeriod.of(fy, GstQuarter.Q3)
        assertEquals(listOf(10, 11, 12), GstQuarter.Q3.months)
        assertEquals(LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 12, 31), period.dateRange())
    }

    @Test
    fun t5_Q4_MapsToJanFebMar_CrossesIntoNextCalendarYear() {
        val period = GstPeriod.of(fy, GstQuarter.Q4)
        assertEquals(listOf(1, 2, 3), GstQuarter.Q4.months)
        // FY 2026-27's Q4 falls in calendar year 2027, not 2026 - the whole point of tracking
        // fyStartCalendarYear separately from the raw month number.
        assertEquals(LocalDate.of(2027, 1, 1)..LocalDate.of(2027, 3, 31), period.dateRange())
    }

    @Test
    fun t6_MonthToQuarter_MappingIsCorrectForEveryMonth() {
        assertEquals(GstQuarter.Q1, GstQuarter.ofMonth(4))
        assertEquals(GstQuarter.Q1, GstQuarter.ofMonth(6))
        assertEquals(GstQuarter.Q2, GstQuarter.ofMonth(9))
        assertEquals(GstQuarter.Q3, GstQuarter.ofMonth(12))
        assertEquals(GstQuarter.Q4, GstQuarter.ofMonth(1))
        assertEquals(GstQuarter.Q4, GstQuarter.ofMonth(3))
        // Machine-readable periodKey for a specific month (Section 2's own example): April 2026 -> "202604".
        assertEquals("202604", GstPeriod.of(fy, GstQuarter.Q1, 4).periodKey)
        assertEquals("202701", GstPeriod.of(fy, GstQuarter.Q4, 1).periodKey)
    }

    // ==========================================
    // 7-9. Scheme
    // ==========================================
    @Test
    fun t7_RegularScheme_AppliesGstr1AndGstr3bMonthly() {
        val rules = GstReturnApplicability.availableReturns(GstScheme.REGULAR)
        assertTrue(rules.any { it.returnType == GstReturnType.GSTR1 && it.periodicity == GstReturnPeriodicity.MONTHLY })
        assertTrue(rules.any { it.returnType == GstReturnType.GSTR3B && it.periodicity == GstReturnPeriodicity.MONTHLY })
    }

    @Test
    fun t8_CompositionScheme_AppliesGstr4Only() {
        val rules = GstReturnApplicability.availableReturns(GstScheme.COMPOSITION)
        assertEquals(listOf(GstReturnType.GSTR4), rules.map { it.returnType })
    }

    @Test
    fun t9_QrmpFilingFrequency_AppliesGstr1AndGstr3bQuarterly_UnderRegularScheme() {
        // QRMP is not a separate GstScheme - it is REGULAR filed at QUARTERLY frequency (see
        // Company.gstFilingFrequency's own doc comment).
        val rules = GstReturnApplicability.availableReturns(GstScheme.REGULAR, GstReturnPeriodicity.QUARTERLY)
        assertTrue(rules.all { it.periodicity == GstReturnPeriodicity.QUARTERLY })
        assertTrue(rules.any { it.returnType == GstReturnType.GSTR1 })
        assertTrue(rules.any { it.returnType == GstReturnType.GSTR3B })
    }

    @Test
    fun t10_ReturnApplicability_IsNotTheSameListForEveryScheme() {
        val regular = GstReturnApplicability.availableReturns(GstScheme.REGULAR)
        val composition = GstReturnApplicability.availableReturns(GstScheme.COMPOSITION)
        assertFalse("Composition must not offer the same returns as Regular", regular == composition)
    }

    // ==========================================
    // 11-12. Filing mode
    // ==========================================
    @Test
    fun t11_OfflineMode_NeverClaimsFiledMerelyFromImport() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        val importResult = repo.importGstReturnOfflineResponse(companyId, gr.gstReturnId, """{"ack":"AB123"}""")
        assertTrue(importResult is AccountingResult.Success)
        val afterImport = repo.getGstReturn(companyId, gr.gstReturnId)!!
        assertEquals("Importing a response must move to PROCESSING, never straight to FILED", GstReturnStatus.PROCESSING, afterImport.status)
    }

    @Test
    fun t12_OnlineMode_ReportsUnconfiguredIntegration_NeverFakeSuccess() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR3B, GstReturnPeriodicity.MONTHLY, GstFilingMode.ONLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        val result = repo.submitGstReturnOnline(companyId, gr.gstReturnId, fy)
        val data = (result as AccountingResult.Success).data
        assertEquals(GstReturnStatus.FAILED, data.status)
        assertEquals("GST_INTEGRATION_NOT_CONFIGURED", data.errorCode)
    }

    // ==========================================
    // 13-14. Status transitions
    // ==========================================
    @Test
    fun t13_StatusTransitions_FollowTheDefinedLifecycle() {
        assertTrue(GstReturnStatusTransitions.isAllowed(GstReturnStatus.DRAFT, GstReturnStatus.READY))
        assertTrue(GstReturnStatusTransitions.isAllowed(GstReturnStatus.READY, GstReturnStatus.SUBMITTING))
        assertTrue(GstReturnStatusTransitions.isAllowed(GstReturnStatus.SUBMITTING, GstReturnStatus.SUBMITTED))
        assertTrue(GstReturnStatusTransitions.isAllowed(GstReturnStatus.PROCESSING, GstReturnStatus.FILED))
        assertTrue(GstReturnStatusTransitions.isAllowed(GstReturnStatus.FAILED, GstReturnStatus.DRAFT))
    }

    @Test
    fun t14_InvalidStatusTransition_IsRejected() = runBlocking {
        assertFalse("DRAFT must never jump straight to FILED", GstReturnStatusTransitions.isAllowed(GstReturnStatus.DRAFT, GstReturnStatus.FILED))
        assertTrue("FILED is terminal", GstReturnStatusTransitions.isAllowed(GstReturnStatus.FILED, GstReturnStatus.FILED).not())

        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        // Still DRAFT (never prepared/validated) - marking Filed must be rejected outright.
        val result = repo.markGstReturnFiled(companyId, gr.gstReturnId, "ACK1")
        assertTrue(result is AccountingResult.Failure)
    }

    // ==========================================
    // 15-16. Save / reopen
    // ==========================================
    @Test
    fun t15_SaveReturn_PersistsTheRecord() = runBlocking {
        val (_, repo) = setup()
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        assertNotNull(repo.getGstReturn(companyId, gr.gstReturnId))
    }

    @Test
    fun t16_ReopenSavedReturn_ResolvesTheSameRecord_Idempotently() = runBlocking {
        val (_, repo) = setup()
        val first = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        val second = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        assertEquals("Reopening the same period/type/scheme must resolve the SAME return, never a duplicate", first.gstReturnId, second.gstReturnId)
    }

    // ==========================================
    // 17-19. JSON artifacts
    // ==========================================
    @Test
    fun t17_JsonArtifact_PersistsAndIsNeverOverwritten() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        val first = (repo.generateGstReturnOfflineJson(companyId, gr.gstReturnId, fy) as AccountingResult.Success).data
        // Re-validate is a no-op transition-wise since already READY->READY isn't needed; generate again directly.
        val second = (repo.generateGstReturnOfflineJson(companyId, gr.gstReturnId, fy) as AccountingResult.Success).data
        assertFalse("A second generate must insert a NEW artifact, never overwrite", first.artifactId == second.artifactId)
        assertEquals(2, repo.getGstReturnArtifacts(gr.gstReturnId).size)
    }

    @Test
    fun t18_ResponseJsonImport_RejectsMalformedJson() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        val result = repo.importGstReturnOfflineResponse(companyId, gr.gstReturnId, "{not valid json")
        assertTrue(result is AccountingResult.Failure)
    }

    @Test
    fun t19_RequestAndResponseArtifacts_AreAssociatedWithTheSameReturn() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        val requestArtifact = (repo.generateGstReturnOfflineJson(companyId, gr.gstReturnId, fy) as AccountingResult.Success).data
        val responseArtifact = (repo.importGstReturnOfflineResponse(companyId, gr.gstReturnId, """{"ok":true}""") as AccountingResult.Success).data
        val all = repo.getGstReturnArtifacts(gr.gstReturnId)
        assertTrue(all.any { it.artifactId == requestArtifact.artifactId })
        assertTrue(all.any { it.artifactId == responseArtifact.artifactId })
        assertTrue(all.all { it.gstReturnId == gr.gstReturnId })
    }

    // ==========================================
    // 20. Section-result persistence
    // ==========================================
    @Test
    fun t20_SectionResult_PersistsAfterPrepare() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        val sections = repo.getGstReturnSections(gr.gstReturnId)
        // GSTR-1's real, statutorily-named section set (Phase 8A, Part 1 - B2B/B2CL/B2CS/CDNR/
        // CDNUR/EXP/NIL/HSN/DOC_ISSUED via Gstr1ReturnBuilder - see t39 for the fuller check of
        // each table's actual content), not one generic "SUMMARY" bucket.
        assertEquals(9, sections.size)
        assertTrue(sections.all { it.resultDataJson != null })
        assertTrue(sections.any { it.sectionKey == "B2CS" })
    }

    // ==========================================
    // 21. Multiple submission history
    // ==========================================
    @Test
    fun t21_MultipleOnlineSubmissions_PreserveFullHistory() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR3B, GstReturnPeriodicity.MONTHLY, GstFilingMode.ONLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        repo.submitGstReturnOnline(companyId, gr.gstReturnId, fy) // attempt 1: FAILED (unconfigured gateway)
        // FAILED -> READY is an allowed retry transition; validate re-runs the same (already
        // PREPARED) section's checks and moves the return back to READY for a second attempt.
        repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        repo.submitGstReturnOnline(companyId, gr.gstReturnId, fy) // attempt 2: FAILED again
        val submissions = repo.getGstReturnSubmissions(gr.gstReturnId)
        assertEquals("Every attempt must be preserved, never overwritten", 2, submissions.size)
        assertEquals(listOf(1, 2), submissions.map { it.attemptNumber })
    }

    // ==========================================
    // 22. Cancelled voucher exclusion
    // ==========================================
    @Test
    fun t22_CancelledVoucher_ExcludedFromActiveReturnData() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10", amountPaise = 1000_00L)
        postSale(dao, "V2", "2026-04-15", amountPaise = 500_00L)
        VoucherPostingEngine.cancel(dao, companyId, fyId, "V2", "IK_CANCEL_V2", "TESTER")

        val period = GstPeriod.of(fy, GstQuarter.Q1, 4)
        val active = repo.getActiveGstTransactionsForPeriod(companyId, fyId, period.dateRange())
        assertEquals("Only V1's taxable value should remain active", 1000_00L, active.sumOf { it.taxableAmount.paise })
    }

    // ==========================================
    // 23. RCM separately identifiable
    // ==========================================
    @Test
    fun t23_RcmPurchase_RemainsSeparatelyIdentifiable_NeverDoubleCounted() = runBlocking {
        val (dao, repo) = setup()
        postPurchase(dao, "V1", "2026-04-10", amountPaise = 1000_00L, chargeType = GstChargeType.REVERSE_CHARGE)
        val period = GstPeriod.of(fy, GstQuarter.Q1, 4)
        val active = repo.getActiveGstTransactionsForPeriod(companyId, fyId, period.dateRange())
        // One GstTransaction row per input LINE, never one per ledger posting - the RCM
        // Input/Liability self-balancing pair must not appear as two (or more) supply rows.
        assertEquals(1, active.size)
        assertEquals(GstChargeType.REVERSE_CHARGE, active.first().chargeType)
        assertEquals(180_00L, active.first().cgst.paise + active.first().sgst.paise + active.first().igst.paise)
    }

    // ==========================================
    // 24. Rule 29 place-of-supply preserved
    // ==========================================
    /**
     * Rule 29 is enforced UPSTREAM of persistence (posting-time guards in
     * [AccountingRepository.postGstOnlySale] and the accounting Sale/Purchase ViewModel flow reject
     * a blank party state before any [GstTransaction] row is written - the `postGstOnlySale` guard
     * itself is exercised end-to-end against a real database in `ExampleRobolectricTest`, since it
     * needs a real Room `AppDatabase` transaction this fake-DAO suite deliberately doesn't use).
     * What this reporting-layer test proves is the other half of Rule 29: the foundation never has
     * to guess a place of supply, because by construction no persisted [GstTransaction] this suite
     * produces ever has a blank one, and [AccountingRepository.validateGstReturn]'s own defensive
     * re-check (Section 13) correctly finds nothing unresolved and reaches READY.
     */
    @Test
    fun t24_Rule29_ReportingNeverFabricatesPlaceOfSupply() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val period = GstPeriod.of(fy, GstQuarter.Q1, 4)
        val active = repo.getActiveGstTransactionsForPeriod(companyId, fyId, period.dateRange())
        assertTrue(active.all { it.placeOfSupply.isNotBlank() })

        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        val result = repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        assertEquals(GstReturnStatus.READY, (result as AccountingResult.Success).data.status)
    }

    // ==========================================
    // 25. Rule 30 GST registration status preserved
    // ==========================================
    @Test
    fun t25_Rule30_LedgerGstRegistrationStatus_UnaffectedByReturnPreparation() = runBlocking {
        val (dao, repo) = setup()
        val before = dao.getLedgerById(companyId, "LED_CREDITOR")?.gstRegistrationStatus
        postPurchase(dao, "V1", "2026-04-10", chargeType = GstChargeType.REVERSE_CHARGE)
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR3B, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        val after = dao.getLedgerById(companyId, "LED_CREDITOR")?.gstRegistrationStatus
        assertEquals(before, after)
    }

    // ==========================================
    // 26. Rule 31 RCM behavior preserved
    // ==========================================
    @Test
    fun t26_Rule31_UnregisteredSupplier_DoesNotAutoBecomeRcm() = runBlocking {
        val (dao, repo) = setup()
        // Forward-charge purchase (default chargeType) against a supplier with no GSTIN on file -
        // must NOT be silently treated as RCM.
        postPurchase(dao, "V1", "2026-04-10", chargeType = GstChargeType.FORWARD_CHARGE)
        val period = GstPeriod.of(fy, GstQuarter.Q1, 4)
        val active = repo.getActiveGstTransactionsForPeriod(companyId, fyId, period.dateRange())
        assertEquals(GstChargeType.FORWARD_CHARGE, active.first().chargeType)
    }

    // ==========================================
    // 27. Rule 32 accounting posting preserved
    // ==========================================
    @Test
    fun t27_Rule32_PostedPurchaseRemainsBalanced_AfterReturnPreparation() = runBlocking {
        val (dao, repo) = setup()
        postPurchase(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR3B, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        val items = dao.getJournalItemsForVoucherSync("V1")
        val debit = items.filter { it.type == DrCr.DEBIT }.sumOf { it.amountPaise }
        val credit = items.filter { it.type == DrCr.CREDIT }.sumOf { it.amountPaise }
        assertEquals("Preparing a return must never touch the underlying posted voucher", debit, credit)
    }

    // ==========================================
    // Additional foundation tests: date-range inclusion/exclusion, inter-state, prepare/validate flow
    // ==========================================
    @Test
    fun t28_DateRange_IncludesTransactionsInsidePeriod() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-15", amountPaise = 1000_00L) // inside Q1/April
        val period = GstPeriod.of(fy, GstQuarter.Q1, 4)
        val active = repo.getActiveGstTransactionsForPeriod(companyId, fyId, period.dateRange())
        assertEquals(1, active.size)
    }

    @Test
    fun t29_DateRange_ExcludesTransactionsOutsidePeriod() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-05-15", amountPaise = 1000_00L) // May, outside the April-only period below
        val aprilOnly = GstPeriod.of(fy, GstQuarter.Q1, 4)
        val active = repo.getActiveGstTransactionsForPeriod(companyId, fyId, aprilOnly.dateRange())
        assertTrue("A May transaction must not appear in an April-only period", active.isEmpty())
    }

    @Test
    fun t30_InterStateSale_UsesIgst_ForwardChargeTotalsCorrect() = runBlocking {
        val (dao, repo) = setup()
        postInterStateSale(dao, "V1", "2026-04-10", amountPaise = 1000_00L)
        val period = GstPeriod.of(fy, GstQuarter.Q1, 4)
        val active = repo.getActiveGstTransactionsForPeriod(companyId, fyId, period.dateRange())
        assertEquals(180_00L, active.first().igst.paise)
        assertEquals(0L, active.first().cgst.paise + active.first().sgst.paise)
    }

    @Test
    fun t31_ValidateWithoutPrepare_FailsWithClearReason() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        val result = repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        val data = (result as AccountingResult.Success).data
        assertEquals(GstReturnStatus.VALIDATION_FAILED, data.status)
    }

    @Test
    fun t32_PrepareThenValidate_MovesToReady() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        val result = repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        assertEquals(GstReturnStatus.READY, (result as AccountingResult.Success).data.status)
    }

    @Test
    fun t33_MarkFiled_RequiresAcknowledgementNumber() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        repo.importGstReturnOfflineResponse(companyId, gr.gstReturnId, """{"ack":"AB123"}""")
        val blankAck = repo.markGstReturnFiled(companyId, gr.gstReturnId, "")
        assertTrue(blankAck is AccountingResult.Failure)
        val realAck = repo.markGstReturnFiled(companyId, gr.gstReturnId, "AB123")
        assertEquals(GstReturnStatus.FILED, (realAck as AccountingResult.Success).data.status)
    }

    @Test
    fun t34_UnconfiguredGateway_AlwaysReportsFailure_NeverFakesAcknowledgement() = runBlocking {
        val gateway: GstOnlineFilingGateway = com.example.accounting.domain.taxation.gstreturn.UnconfiguredGstOnlineFilingGateway()
        val result = gateway.submitReturn("{}")
        assertFalse(result.success)
        assertNull(result.acknowledgementNumber)
        assertEquals("GST_INTEGRATION_NOT_CONFIGURED", result.errorCode)
    }

    /** A stub gateway used only to prove [AccountingRepository.submitGstReturnOnline] would
     * correctly reach SUBMITTED if a real integration ever existed - never used to fake behavior in
     * any other test. */
    private class SucceedingGateway : GstOnlineFilingGateway {
        override suspend fun submitReturn(requestJson: String) = GstOnlineFilingResult(success = true, acknowledgementNumber = "REAL-ACK-1", responseJson = """{"status":"ok"}""")
    }

    @Test
    fun t35_SuccessfulGateway_MovesToSubmitted_ProvingTheIntegrationBoundaryWorks() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR3B, GstReturnPeriodicity.MONTHLY, GstFilingMode.ONLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        val result = repo.submitGstReturnOnline(companyId, gr.gstReturnId, fy, SucceedingGateway())
        val data = (result as AccountingResult.Success).data
        assertEquals(GstReturnStatus.SUBMITTED, data.status)
        assertEquals("REAL-ACK-1", data.acknowledgementNumber)
    }

    // ==========================================
    // Follow-up: Registered/Unregistered + filing frequency settings, section-wise GSTR-1 buckets
    // ==========================================
    @Test
    fun t36_CompanyGstFilingFrequency_DefaultsMonthly_AndCanBeUpdated() = runBlocking {
        val (dao, repo) = setup()
        assertEquals(GstReturnPeriodicity.MONTHLY, dao.getCompanyById(companyId)?.gstFilingFrequency)
        repo.updateAccountingConfiguration(companyId, gstFilingFrequency = GstReturnPeriodicity.QUARTERLY)
        assertEquals(GstReturnPeriodicity.QUARTERLY, dao.getCompanyById(companyId)?.gstFilingFrequency)
    }

    @Test
    fun t37_UnregisteredCompany_GstEnabledToggle_RoundTrips() = runBlocking {
        val (dao, repo) = setup()
        assertTrue(dao.getCompanyById(companyId)?.gstEnabled ?: false)
        repo.updateAccountingConfiguration(companyId, gstEnabled = false)
        assertFalse(dao.getCompanyById(companyId)?.gstEnabled ?: true)
    }

    @Test
    fun t38_GstScheme_HasOnlyRegularAndComposition_QrmpIsAFilingFrequencyNotAScheme() {
        assertEquals(setOf(GstScheme.REGULAR, GstScheme.COMPOSITION), GstScheme.entries.toSet())
    }

    /** Posts one B2B (registered customer) and one B2C (unregistered customer) intra-state Sale in
     * the same period, then confirms Prepare produces real, separately-bucketed B2B/B2C/HSN
     * sections (the actual GST Network GSTR-1 table names) - never one lumped "SUMMARY" total. */
    @Test
    fun t39_GstReturn1_PrepareProducesRealB2bB2cHsnSections() = runBlocking {
        val (dao, repo) = setup()
        val b2bResult = TradingWorkflowEngine.buildSale(
            "V_B2B", companyId, fyId, "LED_DEBTOR", "Registered Customer", "27AAAAA0000A1Z5", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgerRefs(), "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId", "Round Off"
        )
        postResult(dao, "V_B2B", VoucherType.SALES, b2bResult, "2026-04-10")
        postSale(dao, "V_B2C", "2026-04-11", amountPaise = 500_00L) // blank GSTIN by default

        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        val sections = repo.getGstReturnSections(gr.gstReturnId).associateBy { it.sectionKey }

        // Phase 8A, Part 1 - real statutory GSTR-1 tables (Gstr1ReturnBuilder), not the old generic
        // B2C/NIL_EXEMPT buckets: the unregistered intra-state sale is B2CS (never invoice-level),
        // and NIL_EXEMPT was renamed to NIL to match the real Table 8 name.
        assertTrue("Real GSTR-1 section keys must exist", sections.keys.containsAll(setOf("B2B", "B2CL", "B2CS", "EXP", "NIL", "HSN", "DOC_ISSUED")))
        assertTrue(sections.getValue("B2B").resultDataJson!!.contains("\"count\":1"))
        assertTrue(sections.getValue("B2CS").resultDataJson!!.contains("\"count\":1"))
        assertTrue("B2CL/EXP must be empty for this data", sections.getValue("B2CL").resultDataJson!!.contains("\"count\":0"))
        assertTrue("No export in this data", sections.getValue("EXP").resultDataJson!!.contains("\"count\":0"))
        // Both lines share the same HSN+rate (8471 @ 18%), so HSN has exactly one aggregated row
        // whose taxable value is the SUM of both invoices (₹1000 + ₹500 = ₹1500 = 150000 paise).
        assertTrue("HSN summary must have one aggregated row for the shared HSN/rate", sections.getValue("HSN").resultDataJson!!.contains("\"count\":1"))
        assertTrue("HSN aggregate must sum both outward lines' taxable value", sections.getValue("HSN").resultDataJson!!.contains("\"taxableValuePaise\":150000"))
    }

    /** Same idea for GSTR-3B: forward-charge ITC and RCM must land in separate real sections
     * (4A ITC-Forward vs 4A ITC-RCM / 3.1(d) RCM Liability), never merged into one inward figure. */
    @Test
    fun t40_GstReturn3B_PrepareSeparatesForwardChargeAndRcmSections() = runBlocking {
        val (dao, repo) = setup()
        postPurchase(dao, "V_FWD", "2026-04-10", amountPaise = 1000_00L, chargeType = GstChargeType.FORWARD_CHARGE)
        postPurchase(dao, "V_RCM", "2026-04-12", amountPaise = 500_00L, chargeType = GstChargeType.REVERSE_CHARGE)

        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR3B, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        val sections = repo.getGstReturnSections(gr.gstReturnId).associateBy { it.sectionKey }

        assertTrue(sections.keys.containsAll(setOf("OUTWARD_TAXABLE", "OUTWARD_ZERO_RATED", "OUTWARD_NIL_EXEMPT", "RCM_LIABILITY", "ITC_FORWARD", "ITC_RCM")))
        assertTrue(sections.getValue("ITC_FORWARD").resultDataJson!!.contains("\"count\":1"))
        assertTrue(sections.getValue("ITC_RCM").resultDataJson!!.contains("\"count\":1"))
        assertTrue(sections.getValue("RCM_LIABILITY").resultDataJson!!.contains("\"count\":1"))
        assertTrue("Forward-charge purchase must never appear as RCM liability", sections.getValue("RCM_LIABILITY").resultDataJson!!.contains("\"taxableValuePaise\":50000"))
    }
}
