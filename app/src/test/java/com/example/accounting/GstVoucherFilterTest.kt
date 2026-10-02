package com.example.accounting

import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.taxation.gst.GstVoucherFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Phase 8, Step 4 - which vouchers the GST Dashboard / return views may show. */
class GstVoucherFilterTest {

    private fun v(
        id: String, type: VoucherType, gst: Boolean = true, cancelled: Boolean = false, date: LocalDate = LocalDate.of(2026, 4, 10)
    ) = Voucher(
        voucherId = id, companyId = "C1", financialYearId = "FY1", voucherNumber = id, voucherType = type,
        date = date, isGstApplicable = gst, isCancelled = cancelled
    )

    @Test
    fun includesSalesPurchaseCreditNoteAndDebitNote() {
        val all = listOf(
            v("S", VoucherType.SALES), v("P", VoucherType.PURCHASE),
            v("CN", VoucherType.CREDIT_NOTE), v("DN", VoucherType.DEBIT_NOTE)
        )
        assertEquals(listOf("S", "P", "CN", "DN"), GstVoucherFilter.filter(all).map { it.voucherId })
    }

    @Test
    fun rcmPurchaseIsAPurchaseAndIsIncluded() {
        // RCM is not a voucher type: it is a Purchase whose GST lines are reverse-charge, so the
        // same Purchase rule must keep it.
        assertTrue(GstVoucherFilter.isGstRelevant(v("P_RCM", VoucherType.PURCHASE)))
    }

    @Test
    fun excludesNonGstVoucherTypes() {
        val nonGst = listOf(
            VoucherType.CONTRA, VoucherType.PAYMENT, VoucherType.RECEIPT, VoucherType.JOURNAL,
            VoucherType.STOCK_JOURNAL, VoucherType.SALES_ORDER, VoucherType.QUOTATION
        )
        nonGst.forEach { type ->
            // even if one were wrongly flagged GST-applicable, its type alone keeps it out
            assertFalse("$type must not appear in the GST view", GstVoucherFilter.isGstRelevant(v("X", type, gst = true)))
        }
    }

    @Test
    fun excludesSalesOrPurchaseWithoutGstFacts() {
        assertFalse(GstVoucherFilter.isGstRelevant(v("S_NOGST", VoucherType.SALES, gst = false)))
        assertFalse(GstVoucherFilter.isGstRelevant(v("P_NOGST", VoucherType.PURCHASE, gst = false)))
    }

    @Test
    fun excludesCancelledVouchersOfEveryGstType() {
        listOf(VoucherType.SALES, VoucherType.PURCHASE, VoucherType.CREDIT_NOTE, VoucherType.DEBIT_NOTE).forEach {
            assertFalse("cancelled $it must be excluded", GstVoucherFilter.isGstRelevant(v("X", it, cancelled = true)))
        }
    }

    @Test
    fun dateRangeKeepsOnlyVouchersInsideThePeriodInclusive() {
        val range = LocalDate.of(2026, 4, 1)..LocalDate.of(2026, 4, 30)
        val all = listOf(
            v("FIRST", VoucherType.SALES, date = LocalDate.of(2026, 4, 1)),
            v("LAST", VoucherType.SALES, date = LocalDate.of(2026, 4, 30)),
            v("BEFORE", VoucherType.SALES, date = LocalDate.of(2026, 3, 31)),
            v("AFTER", VoucherType.SALES, date = LocalDate.of(2026, 5, 1))
        )
        assertEquals(listOf("FIRST", "LAST"), GstVoucherFilter.filter(all, range).map { it.voucherId })
    }

    @Test
    fun aVoucherAppearsOnceEvenIfListedTwice() {
        val s = v("S", VoucherType.SALES)
        assertEquals(1, GstVoucherFilter.filter(listOf(s, s, s.copy(narration = "dup"))).size)
    }

    @Test
    fun mixedList_keepsOnlyLiveGstDocuments() {
        val all = listOf(
            v("S1", VoucherType.SALES), v("S_CANC", VoucherType.SALES, cancelled = true),
            v("P1", VoucherType.PURCHASE), v("CONTRA", VoucherType.CONTRA, gst = false),
            v("RCT", VoucherType.RECEIPT, gst = false), v("CN1", VoucherType.CREDIT_NOTE),
            v("S_PLAIN", VoucherType.SALES, gst = false)
        )
        assertEquals(setOf("S1", "P1", "CN1"), GstVoucherFilter.filter(all).map { it.voucherId }.toSet())
    }
}
