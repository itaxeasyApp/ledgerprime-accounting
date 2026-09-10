package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.core.database.AppDatabase
import com.example.accounting.data.local.entity.GstTransactionEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.DoubleEntryValidator
import com.example.accounting.domain.accounting.JournalItem
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.financialyear.AccountingPeriod
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.taxation.gst.GSTRules
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.domain.trading.TradingLineInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: AccountingRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AccountingRepository(db.accountingDao(), db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LedgerPrime", appName)
    }

    @Test
    fun `money arithmetic maintains exact paise precision`() {
        val m1 = Money.parse("1500.50")
        val m2 = Money.parse("250.25")
        val sum = m1 + m2
        assertEquals(175075L, sum.paise)
        assertEquals("₹1,750.75", sum.format())

        val tax18 = sum.percentage(18.0)
        assertEquals(31514L, tax18.paise) // ₹315.135 rounds half-up to 315.14
    }

    @Test
    fun `gst rules calculate intra-state and inter-state accurately`() {
        val taxable = Money.fromRupees(10000.0) // ₹10,000

        // Intra-State 18% -> CGST 9% (₹900) + SGST 9% (₹900) = ₹1,800
        val intra = GSTRules.calculateTax(taxable, 18.0, SupplyType.INTRA_STATE)
        assertEquals(90000L, intra.cgstAmount.paise)
        assertEquals(90000L, intra.sgstAmount.paise)
        assertEquals(0L, intra.igstAmount.paise)
        assertEquals(180000L, intra.totalTax.paise)
        assertEquals(1180000L, intra.totalWithTax.paise)

        // Inter-State 18% -> IGST 18% (₹1,800)
        val inter = GSTRules.calculateTax(taxable, 18.0, SupplyType.INTER_STATE)
        assertEquals(0L, inter.cgstAmount.paise)
        assertEquals(0L, inter.sgstAmount.paise)
        assertEquals(180000L, inter.igstAmount.paise)
        assertEquals(180000L, inter.totalTax.paise)
    }

    @Test
    fun `double entry validator rejects unbalanced vouchers`() {
        val fy = FinancialYear(
            financialYearId = "FY1",
            companyId = "COMP1",
            fyCode = "FY 2026-27",
            startDate = LocalDate.parse("2026-04-01"),
            endDate = LocalDate.parse("2027-03-31")
        )
        val period = AccountingPeriod(
            periodId = "P1",
            companyId = "COMP1",
            financialYearId = "FY1",
            name = "Aug 2026",
            startDate = LocalDate.parse("2026-08-01"),
            endDate = LocalDate.parse("2026-08-31"),
            status = PeriodStatus.OPEN
        )

        // Unbalanced: Dr ₹5,000 vs Cr ₹4,000
        val unbalancedVoucher = Voucher(
            voucherId = "V1",
            companyId = "COMP1",
            financialYearId = "FY1",
            voucherNumber = "PMT-001",
            voucherType = VoucherType.PAYMENT,
            date = LocalDate.parse("2026-08-15"),
            items = listOf(
                JournalItem("1", "V1", "COMP1", "FY1", "LED1", "Rent", DrCr.DEBIT, Money.fromRupees(5000.0)),
                JournalItem("2", "V1", "COMP1", "FY1", "LED2", "Bank", DrCr.CREDIT, Money.fromRupees(4000.0))
            )
        )

        val result = DoubleEntryValidator.validate(
            voucher = unbalancedVoucher,
            activeFinancialYear = fy,
            activePeriod = period,
            validLedgerIdsForCompany = setOf("LED1", "LED2")
        )

        assertTrue(result is AccountingResult.Failure)
    }

    @Test
    fun `double entry validator accepts balanced vouchers within period`() {
        val fy = FinancialYear(
            financialYearId = "FY1",
            companyId = "COMP1",
            fyCode = "FY 2026-27",
            startDate = LocalDate.parse("2026-04-01"),
            endDate = LocalDate.parse("2027-03-31")
        )
        val period = AccountingPeriod(
            periodId = "P1",
            companyId = "COMP1",
            financialYearId = "FY1",
            name = "Aug 2026",
            startDate = LocalDate.parse("2026-08-01"),
            endDate = LocalDate.parse("2026-08-31"),
            status = PeriodStatus.OPEN
        )

        // Balanced: Dr ₹5,000 == Cr ₹5,000
        val balancedVoucher = Voucher(
            voucherId = "V1",
            companyId = "COMP1",
            financialYearId = "FY1",
            voucherNumber = "PMT-001",
            voucherType = VoucherType.PAYMENT,
            date = LocalDate.parse("2026-08-15"),
            items = listOf(
                JournalItem("1", "V1", "COMP1", "FY1", "LED1", "Rent", DrCr.DEBIT, Money.fromRupees(5000.0)),
                JournalItem("2", "V1", "COMP1", "FY1", "LED2", "Bank", DrCr.CREDIT, Money.fromRupees(5000.0))
            )
        )

        val result = DoubleEntryValidator.validate(
            voucher = balancedVoucher,
            activeFinancialYear = fy,
            activePeriod = period,
            validLedgerIdsForCompany = setOf("LED1", "LED2")
        )

        assertTrue(result is AccountingResult.Success)
    }

    @Test
    fun `full repository seeding and company initialization`() = runBlocking {
        // Exercises seedInitialDataForCompany directly with an explicit test fixture - production
        // startup no longer auto-seeds any default company (a fresh install starts with zero
        // companies), so this test provides its own company identity instead of relying on that
        // removed auto-seed, matching the pattern already used by Phase7JBVoucherPostingTest/
        // Phase7FRecurringVoucherPostingTest/SuspenseControlArchitectureTest.
        val testCompanyId = "COMP_TEST_SEED"
        repository.seedInitialDataForCompany(testCompanyId, "Test Seed Co", "27AAAAA0000A1Z5")

        val companies = repository.getCompanies().first()
        assertFalse(companies.isEmpty())
        val defaultComp = companies.first()
        assertEquals(testCompanyId, defaultComp.companyId)

        val fyList = repository.getFinancialYears(defaultComp.companyId).first()
        assertEquals(3, fyList.size)
        assertTrue(fyList.any { it.fyCode == "FY 2025-26" })
        assertTrue(fyList.any { it.fyCode == "FY 2026-27" })
        assertTrue(fyList.any { it.fyCode == "FY 2027-28" })

        // Check Chart of Accounts Groups
        val groups = repository.getGroups(defaultComp.companyId).first()
        assertTrue(groups.size >= 22)

        // Check Chart of Accounts Ledgers and Balanced Opening Balances
        val ledgers = repository.getLedgers(defaultComp.companyId).first()
        assertFalse(ledgers.isEmpty())
        val totalDebitOpening = ledgers.filter { it.openingBalanceType == DrCr.DEBIT }.sumOf { it.openingBalance.paise }
        val totalCreditOpening = ledgers.filter { it.openingBalanceType == DrCr.CREDIT }.sumOf { it.openingBalance.paise }
        assertEquals(totalDebitOpening, totalCreditOpening)
    }

    /**
     * Architecture Checkpoint (UI-06 follow-up): proves the actual structural capability the
     * checkpoint required - a [GstTransactionEntity] can now be persisted with `voucherId = null`,
     * i.e. with no accounting [com.example.accounting.data.local.entity.VoucherEntity] at all. This
     * exercises the real SQLite foreign key (a real Room-backed database, not a fake DAO) - if the
     * column were still NOT NULL or the FK still rejected NULL children, this insert would throw.
     * Nothing in production code calls this yet (no new UI, no new repository function per the
     * checkpoint's "smallest change only" scope) - this test exists purely to prove the schema
     * itself no longer forces the coupling, ahead of a future phase actually wiring a GST-only
     * posting path to it.
     */
    @Test
    fun `GstTransaction can be persisted with no Voucher at all`() = runBlocking {
        val companyId = "COMP_GST_ONLY_TEST"
        val fyId = "FY_GST_ONLY_TEST"
        val dao = db.accountingDao()

        dao.insertGstTransactions(
            listOf(
                GstTransactionEntity(
                    gstTransactionId = "GST_NO_VOUCHER_1",
                    companyId = companyId,
                    financialYearId = fyId,
                    voucherId = null,
                    voucherType = VoucherType.SALES,
                    partyLedgerId = "LED_SOME_PARTY",
                    partyGstin = "27AAAAA0000A1Z5",
                    placeOfSupply = "27",
                    supplyType = SupplyType.INTRA_STATE,
                    itemId = null,
                    hsnSacCode = "9983",
                    quantityRaw = null,
                    taxableAmountPaise = 100000L,
                    gstRatePercent = 18.0,
                    cgstPaise = 9000L,
                    sgstPaise = 9000L,
                    igstPaise = 0L,
                    cessPaise = 0L,
                    direction = GstDirection.OUTPUT,
                    lineOrder = 1,
                    createdAt = System.currentTimeMillis()
                )
            )
        )

        val stored = dao.getGstTransactionsForCompanyFY(companyId, fyId)
        assertEquals(1, stored.size)
        assertNull("A GST-only transaction must be storable with no voucherId", stored.first().voucherId)
        assertEquals(9000L, stored.first().cgstPaise)
    }

    /**
     * Architecture Checkpoint follow-up (GST-only Sale application/repository capability). Full
     * stack: [AccountingRepository.postGstOnlySale] -> [com.example.accounting.domain.trading.TradingWorkflowEngine.buildGstOnlySale]
     * -> real Room persistence -> real Outbox enqueue. Proves, against a real database (not a fake
     * DAO), every guarantee this capability must hold: correct GST math, `voucherId = null`, zero
     * Voucher/JournalItem rows, zero ledger-balance mutation, and an Outbox payload that cannot be
     * mistaken for an accounting mutation by whatever eventually reads it server-side.
     */
    @Test
    fun `GST-only Sale persists with no Voucher, no JournalItem, and no ledger balance change`() = runBlocking {
        val companyId = "COMP_GST_ONLY_SALE"
        repository.seedInitialDataForCompany(companyId, "GST Only Sale Co", "27AAAAA0000A1Z5")
        val fy = repository.getFinancialYears(companyId).first().first { it.isCurrent }
        // Rule 29 (Place of Supply): a customer ledger with no state on file is now rejected by
        // postGstOnlySale rather than silently defaulted - pick a starter ledger that actually has
        // one, matching real Place-of-Supply requirements, instead of assuming list order.
        val customerLedger = repository.getLedgers(companyId).first().first { it.stateCode.isNotBlank() }
        val balancePaiseBefore = customerLedger.currentBalance.paise
        val balanceTypeBefore = customerLedger.currentBalanceType

        val lines = listOf(
            TradingLineInput(
                itemId = "ITEM_X", itemName = "Consulting", hsnSacCode = "9983",
                quantity = Quantity.fromLong(1), rate = Money.fromRupees(1000L), gstRatePercent = 18.0
            )
        )

        val result = repository.postGstOnlySale(
            companyId = companyId, financialYearId = fy.financialYearId,
            customerLedgerId = customerLedger.ledgerId, lines = lines,
            date = java.time.LocalDate.of(2026, 5, 10),
            idempotencyKey = "TEST_GST_ONLY_KEY_1"
        )

        assertTrue("Expected success, got $result", result is AccountingResult.Success)
        val gstTransactions = (result as AccountingResult.Success).data
        assertEquals(1, gstTransactions.size)
        assertNull(gstTransactions.first().voucherId)
        assertEquals(90_00L, gstTransactions.first().cgst.paise)
        assertEquals(90_00L, gstTransactions.first().sgst.paise)

        // 1/2. Persisted, voucherId null, via the real query path.
        val stored = repository.getGstTransactionsForCompanyFY(companyId, fy.financialYearId)
        assertEquals(1, stored.size)
        assertNull(stored.first().voucherId)

        // 3/4. No Voucher, no JournalItem.
        val dao = db.accountingDao()
        assertTrue("No Voucher may be created by a GST-only Sale", dao.getAllVouchersByCompany(companyId).first().isEmpty())
        assertTrue("No JournalItem may be created by a GST-only Sale", dao.getAllJournalItems(companyId, fy.financialYearId).first().isEmpty())

        // 5. No ledger balance change.
        val ledgerAfter = dao.getLedgerById(companyId, customerLedger.ledgerId)!!
        assertEquals("Ledger balance must be unchanged", balancePaiseBefore, ledgerAfter.currentBalancePaise)
        assertEquals(balanceTypeBefore, ledgerAfter.currentBalanceType)

        // Outbox payload cannot be mistaken for an accounting mutation.
        val outboxItem = dao.getOutboxByIdempotencyKey("TEST_GST_ONLY_KEY_1")
        assertNotNull("A GST-only Sale must still enqueue an Outbox entry", outboxItem)
        assertTrue(outboxItem!!.payloadJson.contains("\"voucher\":null"))
        assertTrue(outboxItem.payloadJson.contains("\"operation\":\"POST_GST_TRANSACTION\""))
        assertTrue(outboxItem.payloadJson.contains("\"aggregateType\":\"GST_TRANSACTION\""))
        // Transaction/Contract Hardening: the explicit classification travels on the wire per
        // line - a server reading this payload never has to infer SALES from `direction`.
        assertTrue("Outbox payload must carry the explicit SALES classification, not just direction", outboxItem.payloadJson.contains("\"voucherType\":\"SALES\""))
    }

    @Test
    fun `GST-only Sale enforces the company boundary`() = runBlocking {
        val result = repository.postGstOnlySale(
            companyId = "COMP_DOES_NOT_EXIST", financialYearId = "FY_DOES_NOT_EXIST",
            customerLedgerId = "LED_DOES_NOT_EXIST",
            lines = listOf(TradingLineInput("ITEM_X", "Consulting", "9983", Quantity.fromLong(1), Money.fromRupees(1000L), 18.0)),
            date = java.time.LocalDate.of(2026, 5, 10)
        )
        assertTrue(result is AccountingResult.Failure)
    }

    @Test
    fun `GST-only Sale enforces the financial year boundary`() = runBlocking {
        val companyA = "COMP_GST_FY_A"
        val companyB = "COMP_GST_FY_B"
        repository.seedInitialDataForCompany(companyA, "FY Boundary Co A", "27AAAAA0000A1Z5")
        repository.seedInitialDataForCompany(companyB, "FY Boundary Co B", "29BBBBB1111B1Z2")
        val fyOfCompanyB = repository.getFinancialYears(companyB).first().first { it.isCurrent }
        val ledgerOfCompanyA = repository.getLedgers(companyA).first().first()

        // Company A's request supplies Company B's financial year id - must be rejected, never
        // silently posted against the wrong company's books.
        val result = repository.postGstOnlySale(
            companyId = companyA, financialYearId = fyOfCompanyB.financialYearId,
            customerLedgerId = ledgerOfCompanyA.ledgerId,
            lines = listOf(TradingLineInput("ITEM_X", "Consulting", "9983", Quantity.fromLong(1), Money.fromRupees(1000L), 18.0)),
            date = java.time.LocalDate.of(2026, 5, 10)
        )
        assertTrue(result is AccountingResult.Failure)
    }
}
