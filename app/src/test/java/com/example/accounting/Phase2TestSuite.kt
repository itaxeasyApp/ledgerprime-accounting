package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.database.AccountingTransactionException
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.domain.accounting.DoubleEntryValidator
import com.example.accounting.domain.accounting.JournalItem
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.financialyear.AccountingPeriod
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.financialyear.PeriodStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Phase 2 - Production Double-Entry Voucher & Posting Engine test suite.
 *
 * These are pure JVM tests (no Robolectric) exercising [VoucherPostingEngine] - the
 * Room-independent core of the posting/cancellation logic - directly against the existing
 * [FakeAccountingDao] (defined in Phase0TestSuite.kt). This deliberately covers the guard
 * logic (duplicate voucher numbers, idempotent replay, double-cancellation rejection,
 * compensating reversal correctness) that is new in Phase 2.
 *
 * NOT covered here, and NOT to be claimed as covered: genuine SQLite transaction
 * rollback-on-exception behavior of [com.example.accounting.core.database.DatabaseTransaction],
 * which requires a real Room `AppDatabase` (Robolectric or instrumented test). That remains a
 * separate, environment-blocked test-infrastructure item - see `SuspenseControlArchitectureTest`.
 */
class Phase2TestSuite {

    private val companyId = "COMP_P2_001"
    private val fyId = "FY_2026_27_P2"

    private fun ledger(id: String, openingPaise: Long, type: DrCr, name: String = id) = LedgerEntity(
        ledgerId = id,
        companyId = companyId,
        groupId = "GRP_TEST",
        name = name,
        code = id,
        openingBalancePaise = openingPaise,
        openingBalanceType = type,
        currentBalancePaise = openingPaise,
        currentBalanceType = type,
        gstin = "",
        pan = "",
        stateCode = "27",
        email = "",
        phone = "",
        address = "",
        bankAccountNumber = "",
        bankIfsc = "",
        isSystem = false,
        isActive = true,
        hsnSacCode = "",
        defaultTaxRate = 0.0
    )

    private fun voucherEntity(
        voucherId: String,
        voucherNumber: String,
        type: VoucherType,
        totalPaise: Long
    ) = VoucherEntity(
        voucherId = voucherId,
        companyId = companyId,
        financialYearId = fyId,
        voucherNumber = voucherNumber,
        voucherType = type,
        date = "2026-04-15",
        referenceNumber = "",
        narration = "Phase 2 test voucher",
        totalAmountPaise = totalPaise,
        isPosted = true,
        isCancelled = false,
        syncState = SyncState.PENDING,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        createdBy = "TESTER",
        partyGstin = "",
        isGstApplicable = false
    )

    private fun item(voucherId: String, ledgerId: String, type: DrCr, amountPaise: Long, order: Int) = JournalItemEntity(
        itemId = UUID.randomUUID().toString(),
        voucherId = voucherId,
        companyId = companyId,
        financialYearId = fyId,
        ledgerId = ledgerId,
        type = type,
        amountPaise = amountPaise,
        narration = "",
        lineOrder = order
    )

    // ==========================================
    // 1. POSTING - HAPPY PATH
    // ==========================================
    @Test
    fun testPostVoucherAtomic_Success_UpdatesLedgersAndEnqueuesOutbox() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_CASH", 500_000L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_RENT", 0L, DrCr.DEBIT))

        val voucherId = "VCH_P2_001"
        val voucher = voucherEntity(voucherId, "PMT-2026-0001", VoucherType.PAYMENT, 150_000L)
        val items = listOf(
            item(voucherId, "LED_RENT", DrCr.DEBIT, 150_000L, 1),
            item(voucherId, "LED_CASH", DrCr.CREDIT, 150_000L, 2)
        )

        VoucherPostingEngine.post(dao, voucher, items, "IK-POST-1", "TESTER")

        assertNotNull("Voucher header must be persisted", dao.getVoucherById(companyId, voucherId))
        assertEquals(2, dao.getJournalItemsForVoucherSync(voucherId).size)

        val cash = dao.getLedgerById(companyId, "LED_CASH")!!
        val rent = dao.getLedgerById(companyId, "LED_RENT")!!
        assertEquals(350_000L, cash.currentBalancePaise) // 500,000 - 150,000, still Dr
        assertEquals(DrCr.DEBIT, cash.currentBalanceType)
        assertEquals(150_000L, rent.currentBalancePaise)
        assertEquals(DrCr.DEBIT, rent.currentBalanceType)

        val outboxEntry = dao.getOutboxByIdempotencyKey("IK-POST-1")
        assertNotNull("Outbox entry must be enqueued with the idempotency key", outboxEntry)
        assertEquals("INSERT", outboxEntry!!.operation)
    }

    // ==========================================
    // 2. DUPLICATE VOUCHER NUMBER GUARD
    // ==========================================
    @Test
    fun testPostVoucherAtomic_DuplicateVoucherNumber_Rejected() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_CASH", 500_000L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_RENT", 0L, DrCr.DEBIT))

        val firstId = "VCH_P2_010"
        VoucherPostingEngine.post(
            dao,
            voucherEntity(firstId, "PMT-2026-0010", VoucherType.PAYMENT, 100_00L),
            listOf(item(firstId, "LED_RENT", DrCr.DEBIT, 100_00L, 1), item(firstId, "LED_CASH", DrCr.CREDIT, 100_00L, 2)),
            "IK-DUP-1",
            "TESTER"
        )

        val secondId = "VCH_P2_011"
        try {
            VoucherPostingEngine.post(
                dao,
                voucherEntity(secondId, "PMT-2026-0010", VoucherType.PAYMENT, 200_00L), // same number, different voucher
                listOf(item(secondId, "LED_RENT", DrCr.DEBIT, 200_00L, 1), item(secondId, "LED_CASH", DrCr.CREDIT, 200_00L, 2)),
                "IK-DUP-2",
                "TESTER"
            )
            fail("Expected duplicate voucher number to be rejected")
        } catch (e: AccountingTransactionException) {
            assertTrue(e.appError is AppError.DuplicateVoucherNumber)
        }

        assertEquals("Second voucher must not have been persisted", null, dao.getVoucherById(companyId, secondId))
    }

    // ==========================================
    // 3. IDEMPOTENT REPLAY GUARD
    // ==========================================
    @Test
    fun testPostVoucherAtomic_IdempotentReplay_DoesNotDoubleApplyLedgerEffect() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_CASH", 500_000L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_RENT", 0L, DrCr.DEBIT))

        val voucherId = "VCH_P2_020"
        val voucher = voucherEntity(voucherId, "PMT-2026-0020", VoucherType.PAYMENT, 150_000L)
        val items = listOf(
            item(voucherId, "LED_RENT", DrCr.DEBIT, 150_000L, 1),
            item(voucherId, "LED_CASH", DrCr.CREDIT, 150_000L, 2)
        )

        VoucherPostingEngine.post(dao, voucher, items, "IK-REPLAY", "TESTER")
        val cashAfterFirst = dao.getLedgerById(companyId, "LED_CASH")!!.currentBalancePaise

        // Simulate a retried submission (same idempotency key) - must be a safe no-op
        VoucherPostingEngine.post(dao, voucher, items, "IK-REPLAY", "TESTER")
        val cashAfterReplay = dao.getLedgerById(companyId, "LED_CASH")!!.currentBalancePaise

        assertEquals("Ledger balance must not be double-applied on replay", cashAfterFirst, cashAfterReplay)
        assertEquals(1, dao.getOutboxItemsByCompany(companyId, 100).count { it.idempotencyKey == "IK-REPLAY" })
    }

    // ==========================================
    // 4. CANCELLATION - AUDITABLE SOFT-CANCEL (Step 3 live-device fix: a hard-delete here silently
    // let a cancelled voucher's number be reissued to a different real transaction - reproduced on
    // device correcting a Receipt - and made the app's own CANCELLED badge/audit-trail features
    // permanently unreachable. Ledger balances still net to zero; the row, its journal items, and
    // its GST transactions are never removed.)
    // ==========================================
    @Test
    fun testCancelVoucherAtomic_RestoresBalanceAndSoftCancelsWithoutDeletingHistory() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_CASH", 500_000L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_RENT", 0L, DrCr.DEBIT))

        val voucherId = "VCH_P2_030"
        val voucher = voucherEntity(voucherId, "PMT-2026-0030", VoucherType.PAYMENT, 150_000L)
        val items = listOf(
            item(voucherId, "LED_RENT", DrCr.DEBIT, 150_000L, 1),
            item(voucherId, "LED_CASH", DrCr.CREDIT, 150_000L, 2)
        )
        VoucherPostingEngine.post(dao, voucher, items, "IK-CANCEL-POST", "TESTER")

        VoucherPostingEngine.cancel(dao, companyId, fyId, voucherId, "IK-CANCEL-1", "TESTER")

        val cash = dao.getLedgerById(companyId, "LED_CASH")!!
        val rent = dao.getLedgerById(companyId, "LED_RENT")!!
        assertEquals("Cash balance must be restored to pre-posting value", 500_000L, cash.currentBalancePaise)
        assertEquals(DrCr.DEBIT, cash.currentBalanceType)
        assertEquals("Rent balance must be restored to pre-posting value", 0L, rent.currentBalancePaise)

        // The voucher row remains - soft-cancelled, not deleted - so its own detail view, the
        // CANCELLED badge, and any "corrected by" link can still find it.
        val cancelled = dao.getVoucherById(companyId, voucherId)
        assertNotNull("Cancellation must be a soft flag - the voucher row must still exist", cancelled)
        assertTrue("The persisted row must be flagged isCancelled", cancelled!!.isCancelled)

        // Journal items remain too - the voucher's own detail view still shows real Dr/Cr history,
        // never a second same-voucher offsetting entry.
        val allItems = dao.getJournalItemsForVoucherSync(voucherId)
        assertEquals("Cancellation must never delete the original journal lines", 2, allItems.size)

        val outboxEntry = dao.getOutboxByIdempotencyKey("IK-CANCEL-1")
        assertNotNull(outboxEntry)
        assertEquals("CANCEL", outboxEntry!!.operation)
    }

    @Test
    fun testCancelVoucherAtomic_AlreadyCancelled_Rejected() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_CASH", 500_000L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_RENT", 0L, DrCr.DEBIT))

        val voucherId = "VCH_P2_040"
        val voucher = voucherEntity(voucherId, "PMT-2026-0040", VoucherType.PAYMENT, 100_00L)
        val items = listOf(
            item(voucherId, "LED_RENT", DrCr.DEBIT, 100_00L, 1),
            item(voucherId, "LED_CASH", DrCr.CREDIT, 100_00L, 2)
        )
        VoucherPostingEngine.post(dao, voucher, items, "IK-DBLCANCEL-POST", "TESTER")
        VoucherPostingEngine.cancel(dao, companyId, fyId, voucherId, "IK-DBLCANCEL-1", "TESTER")

        // A second cancel attempt (a different idempotency key, so the replay guard doesn't just
        // silently no-op) must still be rejected - now via an explicit "already cancelled" guard,
        // since the row itself no longer disappears to reject this "for free" the way a hard
        // delete used to. Without this guard the ledger balances would be reversed a second time.
        try {
            VoucherPostingEngine.cancel(dao, companyId, fyId, voucherId, "IK-DBLCANCEL-2", "TESTER")
            fail("Expected cancelling an already-cancelled voucher to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains(voucherId) == true)
            assertTrue(e.message?.contains("already cancelled") == true)
        }

        // The ledger balance must reflect exactly one reversal, not two.
        val cash = dao.getLedgerById(companyId, "LED_CASH")!!
        assertEquals("A rejected second cancel must not reverse the ledger balance again", 500_000L, cash.currentBalancePaise)
    }

    // ==========================================
    // 5. FULL CHAIN: Voucher -> Journal -> Double Entry -> Atomic Transaction -> Ledger Effect -> Outbox
    // ==========================================
    @Test
    fun testFullChain_SalesVoucher_ValidatesPostsAndCancelsCorrectly() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_DEBTOR", 0L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_SALES", 0L, DrCr.CREDIT))
        dao.insertLedger(ledger("LED_GST", 0L, DrCr.CREDIT))

        val voucherId = "VCH_P2_050"
        val validLedgers = setOf("LED_DEBTOR", "LED_SALES", "LED_GST")
        val fy = FinancialYear(
            financialYearId = fyId,
            companyId = companyId,
            fyCode = "FY 2026-27",
            startDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2027, 3, 31),
            isCurrent = true
        )
        val period = AccountingPeriod(
            periodId = "PER_1",
            companyId = companyId,
            financialYearId = fyId,
            name = "Apr 2026",
            startDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2026, 4, 30),
            status = PeriodStatus.OPEN
        )

        val domainVoucher = Voucher(
            voucherId = voucherId,
            companyId = companyId,
            financialYearId = fyId,
            voucherNumber = "INV-2026-0050",
            voucherType = VoucherType.SALES,
            date = LocalDate.of(2026, 4, 15),
            totalAmount = Money.fromPaise(118_00L),
            items = listOf(
                JournalItem(UUID.randomUUID().toString(), voucherId, companyId, fyId, "LED_DEBTOR", "Debtor", DrCr.DEBIT, Money.fromPaise(118_00L), lineOrder = 1),
                JournalItem(UUID.randomUUID().toString(), voucherId, companyId, fyId, "LED_SALES", "Sales", DrCr.CREDIT, Money.fromPaise(100_00L), lineOrder = 2),
                JournalItem(UUID.randomUUID().toString(), voucherId, companyId, fyId, "LED_GST", "GST", DrCr.CREDIT, Money.fromPaise(18_00L), lineOrder = 3)
            )
        )

        // Double-entry validation must pass before the atomic transaction ever runs
        val validation = DoubleEntryValidator.validate(domainVoucher, fy, period, validLedgers)
        assertTrue("Multi-line SALES voucher must satisfy the double-entry invariant", validation is AccountingResult.Success)

        val entity = voucherEntity(voucherId, domainVoucher.voucherNumber, VoucherType.SALES, 118_00L)
        val itemEntities = domainVoucher.items.mapIndexed { idx, it -> item(voucherId, it.ledgerId, it.type, it.amount.paise, idx + 1) }

        VoucherPostingEngine.post(dao, entity, itemEntities, "IK-CHAIN-POST", "TESTER")

        assertEquals(118_00L, dao.getLedgerById(companyId, "LED_DEBTOR")!!.currentBalancePaise)
        assertEquals(100_00L, dao.getLedgerById(companyId, "LED_SALES")!!.currentBalancePaise)
        assertEquals(18_00L, dao.getLedgerById(companyId, "LED_GST")!!.currentBalancePaise)
        assertNotNull(dao.getOutboxByIdempotencyKey("IK-CHAIN-POST"))

        VoucherPostingEngine.cancel(dao, companyId, fyId, voucherId, "IK-CHAIN-CANCEL", "TESTER")

        assertEquals(0L, dao.getLedgerById(companyId, "LED_DEBTOR")!!.currentBalancePaise)
        assertEquals(0L, dao.getLedgerById(companyId, "LED_SALES")!!.currentBalancePaise)
        assertEquals(0L, dao.getLedgerById(companyId, "LED_GST")!!.currentBalancePaise)
        // Auditable soft-cancel (Step 3 live-device fix): the voucher row and its journal items
        // persist, flagged isCancelled - never deleted, never offset by a reversal entry.
        val cancelled = dao.getVoucherById(companyId, voucherId)
        assertNotNull("Voucher row must persist after cancellation", cancelled)
        assertTrue(cancelled!!.isCancelled)
        assertEquals(3, dao.getJournalItemsForVoucherSync(voucherId).size)
        assertNotNull(dao.getOutboxByIdempotencyKey("IK-CHAIN-CANCEL"))
    }
}
