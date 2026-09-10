package com.example.accounting

import com.example.accounting.core.common.DrCr
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GstTransactionEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.SupplyType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Step 3 (Voucher create/edit/modify/reverse) live-device regression suite.
 *
 * Pins the exact fix made after a real on-device finding: correcting Receipt RCT-2026-0002
 * reposted its replacement as ANOTHER "RCT-2026-0002" - two different real transactions sharing
 * one voucher number - because [VoucherPostingEngine.cancel] used to hard-delete a cancelled
 * voucher's row (and its journal/GST rows) instead of soft-flagging it via the already-existing,
 * previously-unused [com.example.accounting.data.local.dao.AccountingDao.cancelVoucher]. That
 * hard delete also let `getVoucherCountByType`'s plain COUNT(*) hand the freed-up number straight
 * back out, and made [com.example.accounting.presentation.components.VoucherDetailDialog]'s own
 * CANCELLED badge and `correctedByVoucher` link permanently unreachable.
 *
 * Pure JVM tests (no Robolectric), exercising [VoucherPostingEngine] directly against
 * [FakeAccountingDao] - matching [Phase2TestSuite]'s own documented pattern.
 * [AccountingRepository.postVoucher]/`deleteVoucherSafely` both require a real Room `AppDatabase`
 * (`dbTransaction != null`), environment-blocked here exactly like every other Robolectric-
 * dependent suite in this project; `generateNextVoucherNumber`/`generateTrialBalance` are pure
 * reads and need no `db`, so `AccountingRepository(dao, db = null)` is used for those only.
 */
class Step3VoucherCancellationSoftCancelTestSuite {

    private val companyId = "COMP_S3_001"
    private val fyId = "FY_S3_2026_27"

    private fun ledger(id: String, openingPaise: Long, type: DrCr) = LedgerEntity(
        ledgerId = id, companyId = companyId, groupId = "GRP_TEST", name = id, code = id,
        openingBalancePaise = openingPaise, openingBalanceType = type,
        currentBalancePaise = openingPaise, currentBalanceType = type,
        gstin = "", pan = "", stateCode = "27", email = "", phone = "", address = "",
        bankAccountNumber = "", bankIfsc = "", isSystem = false, isActive = true,
        hsnSacCode = "", defaultTaxRate = 0.0
    )

    private fun voucherEntity(voucherId: String, voucherNumber: String, type: VoucherType, totalPaise: Long, date: String = "2026-04-15") = VoucherEntity(
        voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = voucherNumber,
        voucherType = type, date = date, referenceNumber = "", narration = "Step 3 test voucher",
        totalAmountPaise = totalPaise, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        createdBy = "TESTER", partyGstin = "", isGstApplicable = false
    )

    private fun item(voucherId: String, ledgerId: String, type: DrCr, amountPaise: Long, order: Int) = JournalItemEntity(
        itemId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = companyId, financialYearId = fyId,
        ledgerId = ledgerId, type = type, amountPaise = amountPaise, narration = "", lineOrder = order
    )

    private fun gstLine(voucherId: String, taxableAmountPaise: Long, cgstPaise: Long, sgstPaise: Long) = GstTransactionEntity(
        gstTransactionId = UUID.randomUUID().toString(), companyId = companyId, financialYearId = fyId,
        voucherId = voucherId, voucherType = VoucherType.SALES, partyLedgerId = "LED_DEBTOR", partyGstin = "",
        placeOfSupply = "27", supplyType = SupplyType.INTRA_STATE, itemId = null, hsnSacCode = "9983",
        quantityRaw = null, taxableAmountPaise = taxableAmountPaise, gstRatePercent = 18.0,
        cgstPaise = cgstPaise, sgstPaise = sgstPaise, igstPaise = 0L, cessPaise = 0L,
        direction = GstDirection.OUTPUT, lineOrder = 1, createdAt = 0L
    )

    // ==========================================
    // 1. Cancel voucher -> original remains CANCELLED, never deleted.
    // ==========================================
    @Test
    fun test1_CancelVoucher_OriginalRemainsCancelled() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_CASH", 500_000L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_RENT", 0L, DrCr.DEBIT))
        val voucherId = "VCH_S3_01"
        VoucherPostingEngine.post(
            dao, voucherEntity(voucherId, "PMT-2026-0001", VoucherType.PAYMENT, 100_00L),
            listOf(item(voucherId, "LED_RENT", DrCr.DEBIT, 100_00L, 1), item(voucherId, "LED_CASH", DrCr.CREDIT, 100_00L, 2)),
            "IK-S3-01-POST", "TESTER"
        )

        VoucherPostingEngine.cancel(dao, companyId, fyId, voucherId, "IK-S3-01-CANCEL", "TESTER")

        val cancelled = dao.getVoucherById(companyId, voucherId)
        assertNotNull("Cancelled voucher must remain queryable, never deleted", cancelled)
        assertTrue("Cancelled voucher must be flagged isCancelled", cancelled!!.isCancelled)
        assertEquals("PMT-2026-0001", cancelled.voucherNumber)
    }

    // ==========================================
    // 2. Correct voucher -> cancel-then-repost gets a NEW, different, unique voucher number.
    // ==========================================
    @Test
    fun test2_CorrectVoucher_CancelThenRepost_GetsNewUniqueVoucherNumber() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_CASH", 0L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_DEBTOR", 0L, DrCr.DEBIT))
        val repo = AccountingRepository(dao, db = null)

        val originalId = "VCH_S3_02_ORIG"
        val originalNumber = repo.generateNextVoucherNumber(companyId, fyId, VoucherType.RECEIPT)
        assertEquals("RCT-2026-0001", originalNumber)
        VoucherPostingEngine.post(
            dao, voucherEntity(originalId, originalNumber, VoucherType.RECEIPT, 222_00L),
            listOf(item(originalId, "LED_CASH", DrCr.DEBIT, 222_00L, 1), item(originalId, "LED_DEBTOR", DrCr.CREDIT, 222_00L, 2)),
            "IK-S3-02-POST", "TESTER"
        )

        // "Correct Voucher": cancel the original, then generate a number for the repost - exactly
        // what AccountingViewModel.correctVoucher + postQuickVoucher do in sequence.
        VoucherPostingEngine.cancel(dao, companyId, fyId, originalId, "IK-S3-02-CANCEL", "TESTER")
        val correctedNumber = repo.generateNextVoucherNumber(companyId, fyId, VoucherType.RECEIPT)

        assertNotEquals("A corrected repost must never reuse the cancelled original's number", originalNumber, correctedNumber)
        assertEquals("RCT-2026-0002", correctedNumber)

        val correctedId = "VCH_S3_02_CORRECTED"
        VoucherPostingEngine.post(
            dao, voucherEntity(correctedId, correctedNumber, VoucherType.RECEIPT, 333_00L),
            listOf(item(correctedId, "LED_CASH", DrCr.DEBIT, 333_00L, 1), item(correctedId, "LED_DEBTOR", DrCr.CREDIT, 333_00L, 2)),
            "IK-S3-02-REPOST", "TESTER"
        )

        // Two distinct rows now exist: the cancelled original and the live correction.
        val original = dao.getVoucherById(companyId, originalId)!!
        val corrected = dao.getVoucherById(companyId, correctedId)!!
        assertTrue(original.isCancelled)
        assertTrue(!corrected.isCancelled)
        assertEquals("RCT-2026-0001", original.voucherNumber)
        assertEquals("RCT-2026-0002", corrected.voucherNumber)
    }

    // ==========================================
    // 3. Cancelled voucher number is never reused, even across several subsequent posts.
    // ==========================================
    @Test
    fun test3_CancelledVoucherNumber_NeverReused_AcrossMultipleSubsequentPosts() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_CASH", 0L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_SUPPLIER", 0L, DrCr.CREDIT))
        val repo = AccountingRepository(dao, db = null)

        val v1Id = "VCH_S3_03_1"
        val v1Number = repo.generateNextVoucherNumber(companyId, fyId, VoucherType.PAYMENT)
        VoucherPostingEngine.post(
            dao, voucherEntity(v1Id, v1Number, VoucherType.PAYMENT, 100_00L),
            listOf(item(v1Id, "LED_SUPPLIER", DrCr.DEBIT, 100_00L, 1), item(v1Id, "LED_CASH", DrCr.CREDIT, 100_00L, 2)),
            "IK-S3-03-P1", "TESTER"
        )
        VoucherPostingEngine.cancel(dao, companyId, fyId, v1Id, "IK-S3-03-C1", "TESTER")

        val seenNumbers = mutableSetOf(v1Number)
        repeat(3) { idx ->
            val nextId = "VCH_S3_03_${idx + 2}"
            val nextNumber = repo.generateNextVoucherNumber(companyId, fyId, VoucherType.PAYMENT)
            assertTrue("Voucher number '$nextNumber' must never repeat a prior one (cancelled or live)", seenNumbers.add(nextNumber))
            VoucherPostingEngine.post(
                dao, voucherEntity(nextId, nextNumber, VoucherType.PAYMENT, 50_00L),
                listOf(item(nextId, "LED_SUPPLIER", DrCr.DEBIT, 50_00L, 1), item(nextId, "LED_CASH", DrCr.CREDIT, 50_00L, 2)),
                "IK-S3-03-P${idx + 2}", "TESTER"
            )
        }

        assertEquals(setOf("PMT-2026-0001", "PMT-2026-0002", "PMT-2026-0003", "PMT-2026-0004"), seenNumbers)
    }

    // ==========================================
    // 4. Reports remain correct after cancel/correction - Trial Balance reflects only the live
    // (corrected) voucher, never the cancelled original nor a doubled-up total.
    // ==========================================
    @Test
    fun test4_TrialBalance_CorrectAfterCancelAndCorrection() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_DEBTOR", 0L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_SALES", 0L, DrCr.CREDIT))
        // A real FinancialYearEntity is required here (unlike tests 1-3, which never call
        // generateTrialBalance): without one, generateTrialBalance's own safeParseDate(null)
        // fallback resolves fyStart to LocalDate.now() - today, in 2026 - which makes every
        // 2026-04-15 posting below look like "prior FY" and double-counts it into the opening
        // balance on top of its correct in-FY transaction total.
        dao.insertFinancialYear(FinancialYearEntity(
            financialYearId = fyId, companyId = companyId, fyCode = "FY 2026-27",
            startDate = "2026-04-01", endDate = "2027-03-31", isCurrent = true,
            isLocked = false, lockedAt = null, lockedBy = null
        ))
        val repo = AccountingRepository(dao, db = null)

        val originalId = "VCH_S3_04_ORIG"
        VoucherPostingEngine.post(
            dao, voucherEntity(originalId, "INV-2026-0001", VoucherType.SALES, 1000_00L),
            listOf(item(originalId, "LED_DEBTOR", DrCr.DEBIT, 1000_00L, 1), item(originalId, "LED_SALES", DrCr.CREDIT, 1000_00L, 2)),
            "IK-S3-04-POST", "TESTER"
        )
        VoucherPostingEngine.cancel(dao, companyId, fyId, originalId, "IK-S3-04-CANCEL", "TESTER")

        val correctedId = "VCH_S3_04_CORRECTED"
        VoucherPostingEngine.post(
            dao, voucherEntity(correctedId, "INV-2026-0002", VoucherType.SALES, 1500_00L),
            listOf(item(correctedId, "LED_DEBTOR", DrCr.DEBIT, 1500_00L, 1), item(correctedId, "LED_SALES", DrCr.CREDIT, 1500_00L, 2)),
            "IK-S3-04-REPOST", "TESTER"
        )

        val tb = repo.generateTrialBalance(companyId, fyId)
        val debtorRow = tb.rows.first { it.ledgerId == "LED_DEBTOR" }
        val salesRow = tb.rows.first { it.ledgerId == "LED_SALES" }

        // Only the corrected voucher's 1500 must count - the cancelled original's 1000 must not
        // be double-counted, nor silently dropped from the surviving total.
        assertEquals(1500_00L, debtorRow.closingDebit.paise)
        assertEquals(1500_00L, salesRow.closingCredit.paise)
        assertTrue(tb.isBalanced)
    }

    // ==========================================
    // 5. GST/journal history remains auditable - a cancelled voucher's own lines are still
    // queryable directly, while aggregate GST/report queries correctly exclude it.
    // ==========================================
    @Test
    fun test5_GstAndJournalHistory_RemainsAuditable_ButExcludedFromAggregates() = runBlocking {
        val dao = Phase5TestSuite.Phase5AwareDao(FakeAccountingDao())
        dao.insertLedger(ledger("LED_DEBTOR", 0L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_SALES", 0L, DrCr.CREDIT))

        val voucherId = "VCH_S3_05"
        VoucherPostingEngine.post(
            dao, voucherEntity(voucherId, "INV-2026-0010", VoucherType.SALES, 1180_00L),
            listOf(item(voucherId, "LED_DEBTOR", DrCr.DEBIT, 1180_00L, 1), item(voucherId, "LED_SALES", DrCr.CREDIT, 1000_00L, 2)),
            "IK-S3-05-POST", "TESTER",
            gstTransactions = listOf(gstLine(voucherId, taxableAmountPaise = 1000_00L, cgstPaise = 90_00L, sgstPaise = 90_00L))
        )

        VoucherPostingEngine.cancel(dao, companyId, fyId, voucherId, "IK-S3-05-CANCEL", "TESTER")

        // Auditable: the voucher's own journal/GST history is still directly queryable.
        assertEquals("Cancelled voucher's own journal lines must remain queryable", 2, dao.getJournalItemsForVoucherSync(voucherId).size)
        assertEquals("Cancelled voucher's own GST transaction must remain queryable", 1, dao.getGstTransactionsForVoucher(voucherId).size)

        // Excluded from aggregates: cross-voucher report queries must not include it.
        assertTrue(
            "Trial-balance-feeding journal query must exclude the cancelled voucher",
            dao.getAllJournalItems(companyId, fyId).first().none { it.voucherId == voucherId }
        )
        assertTrue(
            "GST summary/GSTR/export query must exclude the cancelled voucher",
            dao.getGstTransactionsForCompanyFY(companyId, fyId).none { it.voucherId == voucherId }
        )
    }
}
