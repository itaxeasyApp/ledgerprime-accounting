package com.example.accounting

import com.example.accounting.application.reports.ReportManagementService
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.rendering.TabularPdfRenderer
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.rendering.TabularReportChrome
import com.example.accounting.domain.reports.DayBookEntryStatus
import com.example.accounting.domain.reports.DayBookReport
import com.example.accounting.domain.reports.DayBookRow
import com.example.accounting.domain.reports.VoucherRegisterType
import com.example.accounting.domain.reports.buildVoucherRegister
import com.example.accounting.domain.reports.toDetailPdfData
import com.example.accounting.domain.reports.toMonthlySummaryPdfData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Sales/Purchase/Credit Note/Debit Note registers: monthly grouping, cancelled-voucher
 * exclusion, company/FY scoping, PDF mapping and multi-page planning. */
class VoucherRegisterTestSuite {

    private val fyStart = LocalDate.of(2026, 4, 1)
    private val fyEnd = LocalDate.of(2027, 3, 31)

    private fun row(id: String, type: VoucherType, date: String, paise: Long, status: DayBookEntryStatus = DayBookEntryStatus.POSTED) = DayBookRow(
        voucherId = id, voucherNumber = id, voucherType = type, date = LocalDate.parse(date),
        partyName = "Party $id", narration = "", totalAmount = Money.fromPaise(paise), status = status
    )

    private fun dayBook(vararg rows: DayBookRow) = DayBookReport("", rows.toList(), Money.ZERO)

    private val chrome = TabularReportChrome(companyName = "Acme Traders", gstin = "27AAAAA0000A1Z5", financialYearLabel = "Financial Year: 2026-27")

    @Test
    fun register_hasTwelveFyMonthsInOrder_withRealCountsAndTotals() {
        val report = buildVoucherRegister(
            VoucherRegisterType.SALES,
            dayBook(
                row("S2", VoucherType.SALES, "2026-04-20", 2_000_00),
                row("S1", VoucherType.SALES, "2026-04-05", 1_000_00),
                row("S3", VoucherType.SALES, "2027-01-15", 500_00)
            ),
            "2026-27", fyStart, fyEnd
        )
        assertEquals(12, report.months.size)
        assertEquals(YearMonth.of(2026, 4), report.months.first().month)
        assertEquals(YearMonth.of(2027, 3), report.months.last().month)
        val april = report.month(YearMonth.of(2026, 4))!!
        assertEquals(2, april.voucherCount)
        assertEquals(3_000_00L, april.totalAmount.paise)
        assertEquals(listOf("S1", "S2"), april.rows.map { it.voucherId })
        assertEquals(0, report.month(YearMonth.of(2026, 5))!!.voucherCount)
        assertEquals(3, report.totalCount)
        assertEquals(3_500_00L, report.totalAmount.paise)
    }

    @Test
    fun register_excludesCancelled_otherTypes_andOutOfFyDates() {
        val report = buildVoucherRegister(
            VoucherRegisterType.CREDIT_NOTE,
            dayBook(
                row("CN1", VoucherType.CREDIT_NOTE, "2026-06-01", 700_00),
                row("CN2", VoucherType.CREDIT_NOTE, "2026-06-02", 900_00, DayBookEntryStatus.CANCELLED),
                row("DN1", VoucherType.DEBIT_NOTE, "2026-06-03", 400_00),
                row("S1", VoucherType.SALES, "2026-06-04", 100_00),
                row("CN0", VoucherType.CREDIT_NOTE, "2026-03-31", 50_00)
            ),
            "2026-27", fyStart, fyEnd
        )
        assertEquals(1, report.totalCount)
        assertEquals(listOf("CN1"), report.months.flatMap { it.rows }.map { it.voucherId })
        assertEquals(700_00L, report.totalAmount.paise)
    }

    @Test
    fun eachRegisterTypeMapsToItsVoucherType_andTitleLookupRoundTrips() {
        assertEquals(VoucherType.SALES, VoucherRegisterType.SALES.voucherType)
        assertEquals(VoucherType.PURCHASE, VoucherRegisterType.PURCHASE.voucherType)
        assertEquals(VoucherType.CREDIT_NOTE, VoucherRegisterType.CREDIT_NOTE.voucherType)
        assertEquals(VoucherType.DEBIT_NOTE, VoucherRegisterType.DEBIT_NOTE.voucherType)
        VoucherRegisterType.entries.forEach { assertEquals(it, VoucherRegisterType.fromTitle(it.title)) }
        assertNull(VoucherRegisterType.fromTitle("Outstanding Receivables"))
    }

    @Test
    fun monthlySummaryPdf_listsEveryMonth_withTotalsRowAndBranding() {
        val report = buildVoucherRegister(
            VoucherRegisterType.PURCHASE,
            dayBook(row("P1", VoucherType.PURCHASE, "2026-08-10", 12_345_67)),
            "2026-27", fyStart, fyEnd
        )
        val data = report.toMonthlySummaryPdfData(chrome)
        assertEquals("Purchase Register", data.title)
        assertEquals(12, data.rows.size)
        assertEquals(listOf("August 2026", "1", "12345.67"), data.rows[4])
        assertEquals(listOf("Total", "1", "12345.67"), data.totalsRow)
        assertEquals("27AAAAA0000A1Z5", data.chrome!!.gstin)
        assertEquals("01-04-2026 to 31-03-2027", data.chrome!!.periodLabel)
    }

    @Test
    fun monthDetailPdf_onlySelectedMonth_withCarryForwardValuesParallelToRows() {
        val report = buildVoucherRegister(
            VoucherRegisterType.SALES,
            dayBook(
                row("S1", VoucherType.SALES, "2026-04-05", 1_000_00),
                row("S2", VoucherType.SALES, "2026-05-06", 2_000_00),
                row("S3", VoucherType.SALES, "2026-05-07", 3_000_00)
            ),
            "2026-27", fyStart, fyEnd
        )
        val data = report.toDetailPdfData(YearMonth.of(2026, 5), chrome)
        assertEquals(listOf("S2", "S3"), data.rows.map { it[1] })
        assertEquals("06-05-2026", data.rows[0][0])
        assertEquals("5000.00", data.totalsRow!!.last())
        assertEquals(listOf(2_000_00L, 3_000_00L), data.chrome!!.carryForwardColumnPaise[4])
        assertEquals("01-05-2026 to 31-05-2026", data.chrome!!.periodLabel)
    }

    @Test
    fun pagePlanner_coversEveryRowOnce_acrossMultiplePages() {
        val rowCount = 250
        val pages = TabularPdfRenderer.planPages(rowCount, pageHeight = 595, hasCarryForward = true)
        assertTrue("expected several pages, got ${pages.size}", pages.size > 1)
        assertEquals((0 until rowCount).toList(), pages.flatMap { it.toList() })
        assertEquals(listOf(0 until 0), TabularPdfRenderer.planPages(0, pageHeight = 842, hasCarryForward = true))
    }

    // ---- End-to-end through ReportManagementService (company + FY scoping, cancellation) ----

    private class GroupAwareDao(delegate: AccountingDao) : AccountingDao by delegate {
        private val groups = LinkedHashMap<String, GroupEntity>()
        override fun getGroupsByCompany(companyId: String) = kotlinx.coroutines.flow.flowOf(groups.values.filter { it.companyId == companyId })
        override suspend fun getGroupById(companyId: String, groupId: String) = groups[groupId]?.takeIf { it.companyId == companyId }
        override suspend fun insertGroup(group: GroupEntity) { groups[group.groupId] = group }
        override suspend fun insertGroups(groups: List<GroupEntity>) { groups.forEach { this.groups[it.groupId] = it } }
    }

    private suspend fun AccountingDao.seedCompany(companyId: String, fyId: String) {
        insertCompany(CompanyEntity(
            companyId = companyId, name = "Company $companyId", tradeName = "", gstin = "", pan = "", stateCode = "27",
            stateName = "Maharashtra", email = "", phone = "", address = "", currency = "INR", financialYearStartMonth = 4,
            isDefault = false, createdAt = 0L, accountingMode = AccountingMode.ACCOUNT_ONLY, businessType = BusinessType.TRADING
        ))
        insertFinancialYear(FinancialYearEntity(fyId, companyId, "FY 2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_$companyId", companyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
        insertGroups(StandardSystemGroups.getStandardGroupsForCompany(companyId).map {
            GroupEntity(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        })
        insertLedger(ledger(companyId, "DEBTOR_$companyId", StandardSystemGroups.DEBTORS_GROUP_ID))
        insertLedger(ledger(companyId, "SALES_$companyId", StandardSystemGroups.SALES_GROUP_ID, DrCr.CREDIT))
    }

    private fun ledger(companyId: String, id: String, bareGroup: String, openingType: DrCr = DrCr.DEBIT) = LedgerEntity(
        id, companyId, "${bareGroup}_$companyId", id, "", 0L, openingType, 0L, openingType,
        "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0
    )

    private suspend fun postSale(dao: AccountingDao, companyId: String, fyId: String, voucherId: String, date: String, paise: Long) {
        val entity = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = voucherId,
            voucherType = VoucherType.SALES, date = date, referenceNumber = "", narration = "Sale",
            totalAmountPaise = paise, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "TESTER", partyGstin = "", isGstApplicable = false
        )
        val items = listOf(
            JournalItemEntity("$voucherId-1", voucherId, companyId, fyId, "DEBTOR_$companyId", DrCr.DEBIT, paise, "", 1),
            JournalItemEntity("$voucherId-2", voucherId, companyId, fyId, "SALES_$companyId", DrCr.CREDIT, paise, "", 2)
        )
        VoucherPostingEngine.post(dao, entity, items, "IK_$voucherId", "TESTER")
    }

    @Test
    fun service_salesRegister_isCompanyScoped_andExcludesCancelledVouchers() = runBlocking {
        val dao = GroupAwareDao(Phase7BTestSuite.Phase7BAwareDao(FakeAccountingDao()))
        dao.seedCompany("CO_A", "FY_A")
        dao.seedCompany("CO_B", "FY_B")
        postSale(dao, "CO_A", "FY_A", "SA1", "2026-04-10", 1_000_00)
        postSale(dao, "CO_A", "FY_A", "SA2", "2026-04-11", 2_000_00)
        postSale(dao, "CO_A", "FY_A", "SA3", "2026-07-01", 4_000_00)
        postSale(dao, "CO_B", "FY_B", "SB1", "2026-04-12", 9_000_00)
        VoucherPostingEngine.cancel(dao, "CO_A", "FY_A", "SA2", "IK_CANCEL_SA2", "TESTER")

        val fy = FinancialYear(
            financialYearId = "FY_A", companyId = "CO_A", fyCode = "2026-27",
            startDate = LocalDate.of(2026, 4, 1), endDate = LocalDate.of(2027, 3, 31)
        )
        val report = ReportManagementService(AccountingRepository(dao)).voucherRegister("CO_A", fy, VoucherRegisterType.SALES)

        assertEquals(listOf("SA1"), report.month(YearMonth.of(2026, 4))!!.rows.map { it.voucherId })
        assertEquals(listOf("SA3"), report.month(YearMonth.of(2026, 7))!!.rows.map { it.voucherId })
        assertEquals(2, report.totalCount)
        assertEquals(5_000_00L, report.totalAmount.paise)
    }
}
