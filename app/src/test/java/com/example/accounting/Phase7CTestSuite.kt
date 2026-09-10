package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.InvoiceEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.SettlementAllocationEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.invoice.InvoiceType
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyEntityType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.reports.AgingBucket
import com.example.accounting.domain.reports.BalanceSheetReport
import com.example.accounting.domain.reports.DayBookEntryStatus
import com.example.accounting.domain.reports.ProfitAndLossReport
import com.example.accounting.domain.reports.RatioAnalysisEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Phase 7C (Report Management) - pure JVM tests. [SettlementAwareDao] layers real
 * `settlement_allocations` backing (needed for Outstanding-report "fully paid" scenarios) on top
 * of [Phase7BTestSuite.Phase7BAwareDao] (already real for Party/Invoice). Vouchers are posted
 * directly via [VoucherPostingEngine.post] (Room-independent, same pattern
 * `Phase5TestSuite.postResult` uses) - `db == null` throughout, these tests never touch
 * `AccountingRepository.postVoucher`/`postInvoice` (which need a real Room `AppDatabase`).
 */
class Phase7CTestSuite {

    /**
     * Adds real `settlement_allocations` backing on top of [Phase7BTestSuite.Phase7BAwareDao], AND
     * real Group backing - the base [FakeAccountingDao]'s `getGroupsByCompany`/`insertGroups` are
     * permanent no-op stubs (always-empty/no-op), so without this override [seed]'s `insertGroups`
     * call would silently vanish and every group-hierarchy-dependent report (P&L, Balance Sheet,
     * Cash Flow, Ratio Analysis) would see zero groups. Mirrors `Phase3TestSuite.GroupAwareDao`.
     */
    private class SettlementAwareDao(delegate: AccountingDao) : AccountingDao by delegate {
        private val allocations = mutableListOf<SettlementAllocationEntity>()
        override suspend fun getAllocationsForInvoice(invoiceVoucherId: String) = allocations.filter { it.invoiceVoucherId == invoiceVoucherId }
        override suspend fun getAllocationsForSettlement(settlementVoucherId: String) = allocations.filter { it.settlementVoucherId == settlementVoucherId }
        override suspend fun insertSettlementAllocations(rows: List<SettlementAllocationEntity>) { allocations += rows }

        private val groups = LinkedHashMap<String, GroupEntity>()
        override fun getGroupsByCompany(companyId: String) = kotlinx.coroutines.flow.flowOf(groups.values.filter { it.companyId == companyId })
        override suspend fun getGroupById(companyId: String, groupId: String) = groups[groupId]?.takeIf { it.companyId == companyId }
        override suspend fun insertGroup(group: GroupEntity) { groups[group.groupId] = group }
        override suspend fun insertGroups(groups: List<GroupEntity>) { groups.forEach { this.groups[it.groupId] = it } }
        override suspend fun updateGroup(group: GroupEntity) { groups[group.groupId] = group }
        override suspend fun deleteGroup(companyId: String, groupId: String): Int = if (groups.remove(groupId) != null) 1 else 0
    }

    private val companyId = "COMP_P7C"
    private val fyId = "FY_P7C_2026_27"

    private fun freshDao() = SettlementAwareDao(Phase7BTestSuite.Phase7BAwareDao(FakeAccountingDao()))

    private suspend fun AccountingDao.seed(): Map<String, String> {
        insertCompany(CompanyEntity(
            companyId = companyId, name = "Company", tradeName = "Company", gstin = "27AAAAA0000A1Z5",
            pan = "AAAAA0000A", stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
            currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
            accountingMode = AccountingMode.ACCOUNT_ONLY, businessType = BusinessType.TRADING
        ))
        insertFinancialYear(FinancialYearEntity(fyId, companyId, "FY 2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_1", companyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
        insertGroups(StandardSystemGroups.getStandardGroupsForCompany(companyId).map {
            GroupEntity(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        })

        val cashId = "LED_CASH"
        val debtorsLedgerId = "LED_DEBTOR"
        val creditorsLedgerId = "LED_CREDITOR"
        val salesId = "LED_SALES"
        val purchaseId = "LED_PURCHASE"
        insertLedger(ledger(cashId, StandardSystemGroups.CASH_GROUP_ID))
        insertLedger(ledger(debtorsLedgerId, StandardSystemGroups.DEBTORS_GROUP_ID))
        insertLedger(ledger(creditorsLedgerId, StandardSystemGroups.CREDITORS_GROUP_ID, DrCr.CREDIT))
        insertLedger(ledger(salesId, StandardSystemGroups.SALES_GROUP_ID, DrCr.CREDIT))
        insertLedger(ledger(purchaseId, StandardSystemGroups.PURCHASE_GROUP_ID))
        return mapOf("cash" to cashId, "debtors" to debtorsLedgerId, "creditors" to creditorsLedgerId, "sales" to salesId, "purchase" to purchaseId)
    }

    private fun ledger(id: String, bareGroup: String, openingType: DrCr = DrCr.DEBIT) = LedgerEntity(
        id, companyId, "${bareGroup}_$companyId", id, "", 0L, openingType, 0L, openingType,
        "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0
    )

    private suspend fun postVoucher(
        dao: AccountingDao, voucherId: String, voucherType: VoucherType, date: String,
        debitLedgerId: String, creditLedgerId: String, amountPaise: Long
    ): VoucherEntity {
        val entity = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = voucherId,
            voucherType = voucherType, date = date, referenceNumber = "", narration = "Test $voucherType",
            totalAmountPaise = amountPaise, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "TESTER", partyGstin = "", isGstApplicable = false
        )
        val items = listOf(
            JournalItemEntity("$voucherId-1", voucherId, companyId, fyId, debitLedgerId, DrCr.DEBIT, amountPaise, "", 1),
            JournalItemEntity("$voucherId-2", voucherId, companyId, fyId, creditLedgerId, DrCr.CREDIT, amountPaise, "", 2)
        )
        VoucherPostingEngine.post(dao, entity, items, "IK_$voucherId", "TESTER")
        return entity
    }

    private fun invoiceEntity(invoiceId: String, invoiceType: InvoiceType, partyId: String, voucherId: String, date: String, dueDate: String?) = InvoiceEntity(
        invoiceId = invoiceId, companyId = companyId, financialYearId = fyId, invoiceType = invoiceType,
        invoiceNumber = "$invoiceId-NUM", partyId = partyId, date = date, dueDate = dueDate, voucherId = voucherId,
        referenceInvoiceId = null, sourceTradeDocumentId = null, narration = "", createdAt = 0L, updatedAt = 0L
    )

    // ==========================================
    // Day Book
    // ==========================================
    @Test
    fun testGenerateDayBook_ordersChronologically_showsCancelledVoucherButExcludesItsAmount() = runBlocking {
        // Auditable soft-cancel (Step 3 live-device fix): V3's row persists (isCancelled = true,
        // never deleted), so it correctly still appears in the Day Book - the CANCELLED
        // DayBookEntryStatus branch below was actually unreachable before this fix, since a
        // cancelled voucher's row never existed to be found under the old hard-delete design. Its
        // amount is still correctly excluded from the report total (generateDayBook's own
        // status == POSTED filter, unchanged).
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)

        postVoucher(dao, "V2", VoucherType.JOURNAL, "2026-05-10", ledgers.getValue("cash"), ledgers.getValue("sales"), 5_000_00L)
        postVoucher(dao, "V1", VoucherType.JOURNAL, "2026-05-05", ledgers.getValue("cash"), ledgers.getValue("sales"), 3_000_00L)
        postVoucher(dao, "V3", VoucherType.JOURNAL, "2026-05-15", ledgers.getValue("cash"), ledgers.getValue("sales"), 2_000_00L)
        VoucherPostingEngine.cancel(dao, companyId, fyId, "V3", "IK_CANCEL_V3", "TESTER")

        val report = repo.generateDayBook(companyId, LocalDate.of(2026, 5, 1)..LocalDate.of(2026, 5, 31))
        assertEquals(listOf("V1", "V2", "V3"), report.rows.map { it.voucherId })
        assertEquals(DayBookEntryStatus.POSTED, report.rows[0].status)
        assertEquals(DayBookEntryStatus.POSTED, report.rows[1].status)
        assertEquals(DayBookEntryStatus.CANCELLED, report.rows[2].status)
        // Cancelled voucher's total must not count toward the report total.
        assertEquals(8_000_00L, report.totalAmount.paise)
    }

    @Test
    fun testGenerateDayBook_resolvesPartyNameForInvoiceLinkedVoucher_nullOtherwise() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val party = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data

        postVoucher(dao, "V_SALE", VoucherType.SALES, "2026-05-10", ledgers.getValue("debtors"), ledgers.getValue("sales"), 10_000_00L)
        dao.insertInvoice(invoiceEntity("INV_1", InvoiceType.SALES_INVOICE, party.partyId, "V_SALE", "2026-05-10", null))
        postVoucher(dao, "V_JRN", VoucherType.JOURNAL, "2026-05-11", ledgers.getValue("cash"), ledgers.getValue("sales"), 1_000_00L)

        val report = repo.generateDayBook(companyId, LocalDate.of(2026, 5, 1)..LocalDate.of(2026, 5, 31))
        val saleRow = report.rows.first { it.voucherId == "V_SALE" }
        val journalRow = report.rows.first { it.voucherId == "V_JRN" }
        assertEquals("Acme Corp", saleRow.partyName)
        assertNull(journalRow.partyName)
    }

    // ==========================================
    // Outstanding / Receivables / Payables
    // ==========================================
    @Test
    fun testGenerateOutstandingReport_excludesFullyPaidAndCancelled() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data

        // Unpaid - should appear.
        postVoucher(dao, "V_UNPAID", VoucherType.SALES, "2026-05-01", ledgers.getValue("debtors"), ledgers.getValue("sales"), 10_000_00L)
        dao.insertInvoice(invoiceEntity("INV_UNPAID", InvoiceType.SALES_INVOICE, customer.partyId, "V_UNPAID", "2026-05-01", "2026-05-31"))

        // Fully paid via settlement allocation - should be excluded. The settlement voucher must be
        // a real, posted voucher (real Indian accounting/GST practice, and now also the real
        // production behavior - see computeOutstandingPaise's own doc: a settlementVoucherId that
        // resolves to no voucher is treated as "this settlement isn't real", same as a cancelled one).
        postVoucher(dao, "V_PAID", VoucherType.SALES, "2026-05-02", ledgers.getValue("debtors"), ledgers.getValue("sales"), 5_000_00L)
        dao.insertInvoice(invoiceEntity("INV_PAID", InvoiceType.SALES_INVOICE, customer.partyId, "V_PAID", "2026-05-02", "2026-06-01"))
        postVoucher(dao, "V_RECEIPT", VoucherType.RECEIPT, "2026-05-02", ledgers.getValue("cash"), ledgers.getValue("debtors"), 5_000_00L)
        dao.insertSettlementAllocations(listOf(SettlementAllocationEntity("ALLOC_1", companyId, fyId, "V_RECEIPT", "V_PAID", 5_000_00L, 0L)))

        // Cancelled - should be excluded.
        postVoucher(dao, "V_CANCELLED", VoucherType.SALES, "2026-05-03", ledgers.getValue("debtors"), ledgers.getValue("sales"), 2_000_00L)
        dao.insertInvoice(invoiceEntity("INV_CANCELLED", InvoiceType.SALES_INVOICE, customer.partyId, "V_CANCELLED", "2026-05-03", null))
        VoucherPostingEngine.cancel(dao, companyId, fyId, "V_CANCELLED", "IK_CANCEL", "TESTER")

        val report = repo.generateOutstandingReport(companyId, today = LocalDate.of(2026, 5, 20))
        assertEquals(listOf("INV_UNPAID"), report.rows.map { it.invoiceId })
        assertEquals(10_000_00L, report.totalOutstanding.paise)
    }

    @Test
    fun testGenerateOutstandingReport_agingBucketsCorrect() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data
        val today = LocalDate.of(2026, 8, 21)

        postVoucher(dao, "V_CURRENT", VoucherType.SALES, "2026-08-01", ledgers.getValue("debtors"), ledgers.getValue("sales"), 100_00L)
        dao.insertInvoice(invoiceEntity("INV_CURRENT", InvoiceType.SALES_INVOICE, customer.partyId, "V_CURRENT", "2026-08-01", today.plusDays(5).toString()))

        postVoucher(dao, "V_45D", VoucherType.SALES, "2026-07-01", ledgers.getValue("debtors"), ledgers.getValue("sales"), 200_00L)
        dao.insertInvoice(invoiceEntity("INV_45D", InvoiceType.SALES_INVOICE, customer.partyId, "V_45D", "2026-07-01", today.minusDays(45).toString()))

        postVoucher(dao, "V_120D", VoucherType.SALES, "2026-04-01", ledgers.getValue("debtors"), ledgers.getValue("sales"), 300_00L)
        dao.insertInvoice(invoiceEntity("INV_120D", InvoiceType.SALES_INVOICE, customer.partyId, "V_120D", "2026-04-01", today.minusDays(120).toString()))

        val report = repo.generateOutstandingReport(companyId, today = today)
        val byInvoice = report.rows.associateBy { it.invoiceId }
        assertEquals(AgingBucket.CURRENT, byInvoice.getValue("INV_CURRENT").agingBucket)
        assertEquals(AgingBucket.DAYS_31_60, byInvoice.getValue("INV_45D").agingBucket)
        assertEquals(AgingBucket.DAYS_90_PLUS, byInvoice.getValue("INV_120D").agingBucket)

        val bucketTotal = report.agingSummary.first { it.bucket == AgingBucket.DAYS_90_PLUS }
        assertEquals(300_00L, bucketTotal.totalOutstanding.paise)
        assertEquals(1, bucketTotal.invoiceCount)
    }

    @Test
    fun testGenerateReceivablesReport_onlyReturnsCustomerSalesInvoices() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data
        val supplier = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("creditors"), role = PartyRole.SUPPLIER, entityType = PartyEntityType.BUSINESS, displayName = "Beta Supplies")) as AccountingResult.Success).data

        postVoucher(dao, "V_SALE", VoucherType.SALES, "2026-05-01", ledgers.getValue("debtors"), ledgers.getValue("sales"), 10_000_00L)
        dao.insertInvoice(invoiceEntity("INV_SALE", InvoiceType.SALES_INVOICE, customer.partyId, "V_SALE", "2026-05-01", null))

        postVoucher(dao, "V_PURCHASE", VoucherType.PURCHASE, "2026-05-01", ledgers.getValue("purchase"), ledgers.getValue("creditors"), 8_000_00L)
        dao.insertInvoice(invoiceEntity("INV_PURCHASE", InvoiceType.PURCHASE_BILL, supplier.partyId, "V_PURCHASE", "2026-05-01", null))

        val receivables = repo.generateReceivablesReport(companyId, today = LocalDate.of(2026, 5, 20))
        assertEquals(listOf("INV_SALE"), receivables.rows.map { it.invoiceId })

        val payables = repo.generatePayablesReport(companyId, today = LocalDate.of(2026, 5, 20))
        assertEquals(listOf("INV_PURCHASE"), payables.rows.map { it.invoiceId })
    }

    /**
     * Root-cause regression (business-model audit): Receivable/Payable must reflect every real
     * outstanding posting, never only ones with a registered Customer/Supplier [Party] or a linked
     * [com.example.accounting.data.local.entity.InvoiceEntity] - most Sale/Purchase vouchers posted
     * through the real UI ([com.example.accounting.domain.trading.TradingWorkflowEngine] ->
     * `postVoucher`) never create either. A plain ledger with neither must still appear while
     * outstanding, and disappear once fully settled or cancelled.
     */
    @Test
    fun testGenerateReceivablesReport_includesPlainLedgerWithNoPartyOrInvoice() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val walkInLedgerId = "LED_WALKIN"
        dao.insertLedger(ledger(walkInLedgerId, StandardSystemGroups.DEBTORS_GROUP_ID).copy(name = "Walk-in Counter Sale"))

        postVoucher(dao, "V_WALKIN", VoucherType.SALES, "2026-05-01", walkInLedgerId, ledgers.getValue("sales"), 4_000_00L)

        val receivables = repo.generateReceivablesReport(companyId, today = LocalDate.of(2026, 5, 20))
        val row = receivables.rows.single { it.voucherId == "V_WALKIN" }
        assertEquals(walkInLedgerId, row.partyId)
        assertEquals("Walk-in Counter Sale", row.partyName)
        assertEquals(4_000_00L, row.outstandingAmount.paise)

        // Fully settled -> no longer outstanding. The settlement voucher must be a real, posted
        // voucher (see computeOutstandingPaise's own doc: a settlementVoucherId that resolves to no
        // voucher is treated as "this settlement isn't real", same as a cancelled one).
        postVoucher(dao, "V_WALKIN_RCT", VoucherType.RECEIPT, "2026-05-01", ledgers.getValue("cash"), walkInLedgerId, 4_000_00L)
        dao.insertSettlementAllocations(listOf(SettlementAllocationEntity("ALLOC_WALKIN", companyId, fyId, "V_WALKIN_RCT", "V_WALKIN", 4_000_00L, 0L)))
        val afterSettlement = repo.generateReceivablesReport(companyId, today = LocalDate.of(2026, 5, 20))
        assertTrue(afterSettlement.rows.none { it.voucherId == "V_WALKIN" })

        // A second, cancelled walk-in sale must not appear either.
        postVoucher(dao, "V_WALKIN_CANCELLED", VoucherType.SALES, "2026-05-02", walkInLedgerId, ledgers.getValue("sales"), 1_500_00L)
        VoucherPostingEngine.cancel(dao, companyId, fyId, "V_WALKIN_CANCELLED", "IK_CANCEL_WALKIN", "TESTER")
        val afterCancel = repo.generateReceivablesReport(companyId, today = LocalDate.of(2026, 5, 20))
        assertTrue(afterCancel.rows.none { it.voucherId == "V_WALKIN_CANCELLED" })
    }

    // ==========================================
    // Cash Flow
    // ==========================================
    @Test
    fun testGenerateCashFlow_operatingActivitiesReflectsNetProfitAndWorkingCapitalChange() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)

        // A cash sale: increases Sales (net profit) and Cash - no working-capital change since it
        // never touches Debtors/Creditors.
        postVoucher(dao, "V_CASH_SALE", VoucherType.SALES, "2026-05-10", ledgers.getValue("cash"), ledgers.getValue("sales"), 50_000_00L)

        val ledgersBefore = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }

        val report = repo.generateCashFlow(companyId, fyId, LocalDate.of(2026, 4, 1)..LocalDate.of(2026, 5, 31))

        assertEquals(50_000_00L, report.netProfit.paise)
        // No Debtors/Creditors movement in this scenario -> operating cash flow == net profit.
        assertEquals(0L, report.changeInCurrentAssetsExcludingCash.paise)
        assertEquals(0L, report.changeInCurrentLiabilities.paise)
        assertEquals(50_000_00L, report.netCashFromOperatingActivities.paise)
        assertNull("Investing is an explicit, undocumented-as-computed extension point", report.netCashFromInvestingActivities)
        assertNull("Financing is an explicit, undocumented-as-computed extension point", report.netCashFromFinancingActivities)

        // Report generation must be read-only - no ledger balance may change as a side effect.
        val ledgersAfter = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        for ((id, before) in ledgersBefore) {
            assertEquals(before.currentBalancePaise, ledgersAfter.getValue(id).currentBalancePaise)
        }
    }

    // ==========================================
    // Ratio Analysis (pure function - no DAO)
    // ==========================================
    private fun balanceSheet(currentAssets: Long, currentLiabilities: Long, stockInHand: Long, loans: Long, capital: Long, reserves: Long, netProfit: Long) = BalanceSheetReport(
        companyName = "Test Co", financialYearCode = "FY 2026-27", asOfDate = LocalDate.of(2026, 5, 31),
        capitalAccounts = Money.fromPaise(capital), reservesAndSurplus = Money.fromPaise(reserves), netProfitForYear = Money.fromPaise(netProfit),
        loansLiabilities = Money.fromPaise(loans), currentLiabilities = Money.fromPaise(currentLiabilities), dutiesAndTaxesLiability = Money.ZERO,
        totalLiabilities = Money.ZERO, fixedAssets = Money.ZERO, currentAssets = Money.fromPaise(currentAssets),
        sundryDebtors = Money.ZERO, bankAccounts = Money.ZERO, cashInHand = Money.ZERO, stockInHand = Money.fromPaise(stockInHand), totalAssets = Money.ZERO
    )

    private fun profitAndLoss(sales: Long, directIncomes: Long, grossProfit: Long, netProfit: Long, purchases: Long, directExpenses: Long, indirectExpenses: Long) = ProfitAndLossReport(
        companyName = "Test Co", financialYearCode = "FY 2026-27", dateRange = "2026-04-01 to 2026-05-31",
        salesRevenue = Money.fromPaise(sales), directIncomes = Money.fromPaise(directIncomes), purchases = Money.fromPaise(purchases),
        directExpenses = Money.fromPaise(directExpenses), grossProfit = Money.fromPaise(grossProfit),
        indirectIncomes = Money.ZERO, indirectExpenses = Money.fromPaise(indirectExpenses), netProfit = Money.fromPaise(netProfit)
    )

    @Test
    fun testRatioAnalysisEngine_exactFormulas() {
        val bs = balanceSheet(currentAssets = 200_000_00L, currentLiabilities = 100_000_00L, stockInHand = 50_000_00L, loans = 100_000_00L, capital = 300_000_00L, reserves = 0L, netProfit = 50_000_00L)
        val pnl = profitAndLoss(sales = 1_000_000_00L, directIncomes = 0L, grossProfit = 300_000_00L, netProfit = 50_000_00L, purchases = 700_000_00L, directExpenses = 0L, indirectExpenses = 250_000_00L)

        val report = RatioAnalysisEngine.compute(bs, pnl)

        // currentAssets/currentLiabilities are RESIDUAL buckets in BalanceSheetReport (Debtors/
        // Bank/Cash/Stock and Duties&Taxes are separately-named fields) - the engine adds them
        // back in for the true total, so with sundryDebtors/bankAccounts/cashInHand/dutiesAndTaxes
        // all zero here: totalCurrentAssets = 200000+0+0+0+50000(stock) = 250000.
        assertEquals(2.5, report.currentRatio, 0.0001) // 250000/100000
        assertEquals(2.0, report.quickRatio, 0.0001) // (250000-50000)/100000
        assertEquals(100_000_00L / 350_000_00.0, report.debtEquityRatio, 0.0001) // loans / (capital+reserves+netProfit)
        assertEquals(30.0, report.grossProfitRatioPercent, 0.0001) // 300000/1000000*100
        assertEquals(5.0, report.netProfitRatioPercent, 0.0001) // 50000/1000000*100
        assertEquals(95.0, report.operatingRatioPercent, 0.0001) // (700000+0+250000)/1000000*100
        assertEquals(50_000_00L / 450_000_00.0 * 100.0, report.returnOnCapitalEmployedPercent, 0.0001) // netProfit/(capitalEmployed)*100
    }

    @Test
    fun testRatioAnalysisEngine_zeroDenominator_returnsZeroNotCrash() {
        val bs = balanceSheet(currentAssets = 100_00L, currentLiabilities = 0L, stockInHand = 0L, loans = 0L, capital = 0L, reserves = 0L, netProfit = 0L)
        val pnl = profitAndLoss(sales = 0L, directIncomes = 0L, grossProfit = 0L, netProfit = 0L, purchases = 0L, directExpenses = 0L, indirectExpenses = 0L)

        val report = RatioAnalysisEngine.compute(bs, pnl)

        assertEquals(0.0, report.currentRatio, 0.0001)
        assertEquals(0.0, report.debtEquityRatio, 0.0001)
        assertEquals(0.0, report.grossProfitRatioPercent, 0.0001)
        assertEquals(0.0, report.netProfitRatioPercent, 0.0001)
        assertEquals(0.0, report.returnOnCapitalEmployedPercent, 0.0001)
    }
}
