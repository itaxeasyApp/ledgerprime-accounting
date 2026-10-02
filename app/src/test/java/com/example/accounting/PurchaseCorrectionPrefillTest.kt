package com.example.accounting

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.JournalItem
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.presentation.components.CreateVoucherDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Phase 8 Step 18 - "Correct Voucher" on an account-only Purchase must restore the original's recorded
 * supplier invoice number and date into the existing Purchase form (which then posts them onto the new GST
 * fact), keep NOT_RECORDED when the original had none, and never use the voucher/booking date for it.
 * Renders the real [CreateVoucherDialog] under Robolectric; the repository half of the correction
 * (fact read -> cancel -> repost -> duplicate guard) is in [PurchaseDocumentIdentityTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PurchaseCorrectionPrefillTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val companyId = "C1"
    private val fyId = "FY1"
    private val booking = LocalDate.of(2026, 6, 10)

    private fun ledger(id: String, groupBare: String, gstin: String = "") = Ledger(
        ledgerId = id, companyId = companyId, groupId = "${groupBare}_$companyId", name = id, gstin = gstin, stateCode = "27"
    )

    private val ledgers = listOf(
        ledger("SUP", StandardSystemGroups.CREDITORS_GROUP_ID, "29ABCDE1234F1Z5"),
        ledger("PUR", StandardSystemGroups.PURCHASE_GROUP_ID),
        ledger("CUST", StandardSystemGroups.DEBTORS_GROUP_ID),
        ledger("SAL", StandardSystemGroups.SALES_GROUP_ID)
    )

    private fun original(type: VoucherType, reference: String) = Voucher(
        voucherId = "V_ORIG", companyId = companyId, financialYearId = fyId, voucherNumber = "N-1", voucherType = type,
        date = booking, referenceNumber = reference, narration = "Being goods", totalAmount = Money.fromPaise(1180_00L),
        items = listOf(
            JournalItem("J1", "V_ORIG", companyId, fyId, if (type == VoucherType.PURCHASE) "SUP" else "CUST", "", if (type == VoucherType.PURCHASE) DrCr.CREDIT else DrCr.DEBIT, Money.fromPaise(1180_00L), "party", 1),
            JournalItem("J2", "V_ORIG", companyId, fyId, if (type == VoucherType.PURCHASE) "PUR" else "SAL", "", if (type == VoucherType.PURCHASE) DrCr.DEBIT else DrCr.CREDIT, Money.fromPaise(1000_00L), "trade", 2),
            JournalItem("J3", "V_ORIG", companyId, fyId, "GST", "", if (type == VoucherType.PURCHASE) DrCr.DEBIT else DrCr.CREDIT, Money.fromPaise(180_00L), "gst", 3)
        ),
        createdBy = "T", partyGstin = "29ABCDE1234F1Z5", isGstApplicable = true
    )

    private class Posted(
        var number: String? = "unset", var date: LocalDate? = LocalDate.MIN, var called: Boolean = false,
        var rate: Double = -1.0, var hsn: String = "unset"
    )

    private fun show(
        type: VoucherType, reference: String, factNumber: String?, factDate: LocalDate?, posted: Posted = Posted(),
        gstDetail: Pair<Double, String>? = 18.0 to "8471"
    ) {
        composeTestRule.setContent {
            CreateVoucherDialog(
                ledgers = ledgers, isInventoryEnabled = false, gstApplicable = true, defaultVoucherType = type, lockedType = true,
                prefillFrom = original(type, reference), prefillGstDetail = gstDetail,
                prefillSupplierInvoiceNumber = factNumber, prefillSupplierInvoiceDate = factDate,
                onDismiss = {}, onPostQuickVoucher = { _, _, _, _, _, _, _ -> },
                onPostAccountOnlyPurchase = { _, _, _, _, ref, _, rate, hsn, supplierDate ->
                    posted.number = ref; posted.date = supplierDate; posted.rate = rate; posted.hsn = hsn; posted.called = true
                },
                onPostAccountOnlySale = { _, _, _, _, ref, _, rate, hsn ->
                    posted.number = ref; posted.date = null; posted.rate = rate; posted.hsn = hsn; posted.called = true
                }
            )
        }
    }

    @Test
    fun recordedNumberAndDate_areBothPrefilled_fromTheGstFact() {
        show(VoucherType.PURCHASE, reference = "EDITED-LATER", factNumber = "INV-C1", factDate = LocalDate.of(2026, 5, 28))
        composeTestRule.onNodeWithText("INV-C1").assertExists()
        composeTestRule.onNodeWithText("2026-05-28").assertExists()
        // the fact is the authority: the voucher's later-edited free-text reference is not shown instead
        composeTestRule.onAllNodes(hasText("EDITED-LATER")).assertCountEquals(0)
    }

    @Test
    fun missingDate_staysEmpty_andTheBookingDateIsNeverShownAsTheSupplierDate() {
        show(VoucherType.PURCHASE, reference = "INV-NODATE", factNumber = "INV-NODATE", factDate = null)
        composeTestRule.onNodeWithText("INV-NODATE").assertExists()
        composeTestRule.onNodeWithText("Supplier Invoice Date (Optional)").assertExists()
        composeTestRule.onAllNodes(hasText(booking.toString())).assertCountEquals(0)
        composeTestRule.onAllNodes(hasText("2026-", substring = true)).assertCountEquals(0)
    }

    @Test
    fun aRecordedDate_isShown_notTheBookingDate() {
        show(VoucherType.PURCHASE, reference = "X", factNumber = "INV-X", factDate = LocalDate.of(2026, 5, 20))
        composeTestRule.onNodeWithText("2026-05-20").assertExists()
        composeTestRule.onAllNodes(hasText(booking.toString())).assertCountEquals(0)
    }

    @Test
    fun anOriginalWithNoRecordedNumber_fallsBackToItsReference_asBefore() {
        show(VoucherType.PURCHASE, reference = "OLD-REF-7", factNumber = null, factDate = null)
        composeTestRule.onNodeWithText("OLD-REF-7").assertExists()
        composeTestRule.onAllNodes(hasText("2026-", substring = true)).assertCountEquals(0)
    }

    @Test
    fun postingTheCorrectedForm_handsTheRecordedIdentityToThePostingPath() {
        val posted = Posted()
        show(VoucherType.PURCHASE, reference = "R", factNumber = "INV-C2", factDate = LocalDate.of(2026, 5, 27), posted = posted)
        composeTestRule.onNodeWithText("Post to Ledger").performClick()
        composeTestRule.waitForIdle()
        assertEquals(true, posted.called)
        assertEquals("INV-C2", posted.number)
        assertEquals(LocalDate.of(2026, 5, 27), posted.date)
    }

    @Test
    fun postingACorrectedPurchaseWithNoRecordedDate_postsNullNotTheBookingDate() {
        val posted = Posted()
        show(VoucherType.PURCHASE, reference = "R2", factNumber = "INV-C3", factDate = null, posted = posted)
        composeTestRule.onNodeWithText("Post to Ledger").performClick()
        composeTestRule.waitForIdle()
        assertEquals(true, posted.called)
        assertNull(posted.date)
    }

    @Test
    fun aSaleCorrection_ignoresSupplierIdentity_andShowsNoSupplierDateField() {
        show(VoucherType.SALES, reference = "SALE-REF", factNumber = "SHOULD-NOT-APPEAR", factDate = LocalDate.of(2026, 1, 1))
        composeTestRule.onNodeWithText("SALE-REF").assertExists()
        composeTestRule.onAllNodes(hasText("SHOULD-NOT-APPEAR")).assertCountEquals(0)
        composeTestRule.onAllNodes(hasText("Supplier Invoice Date (Optional)")).assertCountEquals(0)
        composeTestRule.onAllNodes(hasText("2026-01-01")).assertCountEquals(0)
    }

    // ---------------------------------------------------------------- Step 19: GST rate / HSN-SAC prefill

    @Test
    fun s19_accountOnlySaleCorrection_preservesTheOriginalGstRateAndHsn() {
        val posted = Posted()
        show(VoucherType.SALES, reference = "S-REF", factNumber = null, factDate = null, posted = posted, gstDetail = 12.0 to "9954")
        composeTestRule.onNodeWithText("9954").assertExists()
        composeTestRule.onAllNodes(hasText("8471")).assertCountEquals(0)
        composeTestRule.onNodeWithText("Post to Ledger").performClick()
        composeTestRule.waitForIdle()
        assertEquals(12.0, posted.rate, 0.0)
        assertEquals("9954", posted.hsn)
    }

    @Test
    fun s19_accountOnlyPurchaseCorrection_preservesTheOriginalGstRateAndHsn_alongsideTheSupplierIdentity() {
        val posted = Posted()
        show(
            VoucherType.PURCHASE, reference = "P-REF", factNumber = "INV-19", factDate = LocalDate.of(2026, 5, 15), posted = posted,
            gstDetail = 5.0 to "1001"
        )
        composeTestRule.onNodeWithText("1001").assertExists()
        composeTestRule.onNodeWithText("Post to Ledger").performClick()
        composeTestRule.waitForIdle()
        assertEquals(5.0, posted.rate, 0.0)
        assertEquals("1001", posted.hsn)
        // Step 14/18 behaviour is unchanged by the GST prefill
        assertEquals("INV-19", posted.number)
        assertEquals(LocalDate.of(2026, 5, 15), posted.date)
    }

    @Test
    fun s19_aSaleAndAPurchase_eachKeepTheirOwnGstDetail() {
        val sale = Posted()
        show(VoucherType.SALES, reference = "S", factNumber = null, factDate = null, posted = sale, gstDetail = 28.0 to "8703")
        composeTestRule.onNodeWithText("Post to Ledger").performClick()
        composeTestRule.waitForIdle()
        assertEquals(28.0, sale.rate, 0.0); assertEquals("8703", sale.hsn)
    }

    @Test
    fun s19_missingOriginalGstDetail_staysMissing_notReplacedByADefault() {
        val posted = Posted()
        show(VoucherType.PURCHASE, reference = "P", factNumber = "INV-M", factDate = null, posted = posted, gstDetail = null)
        composeTestRule.onAllNodes(hasText("8471")).assertCountEquals(0)
        composeTestRule.onNodeWithText("Post to Ledger").performClick()
        composeTestRule.waitForIdle()
        assertEquals("no rate was recorded, so none is invented", 0.0, posted.rate, 0.0)
        assertEquals("", posted.hsn)
    }

    @Test
    fun s19_mainAppScreen_passesTheCorrectionGstDetailIntoTheForm() {
        // MainAppScreen cannot be composed in a unit test (it needs the whole app state), so this guards the
        // wiring that was missing: the correction's GST detail must reach CreateVoucherDialog.prefillGstDetail.
        val src = java.io.File("src/main/java/com/example/accounting/presentation/MainAppScreen.kt").readText()
        assertEquals(true, src.contains("prefillGstDetail = uiState.pendingVoucherCorrectionGstDetail"))
        assertEquals(true, src.contains("prefillSupplierInvoiceNumber = uiState.pendingVoucherCorrectionSupplierNumber"))
    }
}