package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.GstRegistrationStatus
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.domain.trading.TradingLineInput
import com.example.accounting.domain.trading.TradingWorkflowEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * D1b (GST-Only Purchase + Sales/Purchase Return + GST Fact Hardening) - pure-JVM coverage,
 * following the exact Phase5TestSuite pattern this phase extends: [TradingWorkflowEngine]'s pure
 * functions are exercised directly (no Room needed, real successful persistence for the GST-only
 * repository functions needs a real `AppDatabase` - see [AccountingRepository.postGstOnlySale]'s
 * own `dbTransaction` guard - so that specific path is Robolectric-only, matching the pre-existing,
 * already-disclosed limitation `ExampleRobolectricTest.kt`'s own GST-only Sale tests document).
 * Every repository-level rejection path tested here (missing Place of Supply, original-not-found,
 * wrong-original-type) is now checked BEFORE the `dbTransaction` guard, so it genuinely executes
 * here without needing Robolectric.
 */
class D1bGstOnlyTestSuite {

    private val companyId = "COMP_D1B"
    private val fyId = "FY_D1B_2026_27"

    private fun freshDao() = Phase5TestSuite.Phase5AwareDao(Phase4TestSuite.InventoryAwareDao(FakeAccountingDao()))

    private suspend fun com.example.accounting.data.local.dao.AccountingDao.seedCompanyAndFy() {
        insertCompany(
            CompanyEntity(
                companyId = companyId, name = "D1b Co", tradeName = "D1b Co", gstin = "27AAAAA0000A1Z5",
                pan = "AAAAA0000A", stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
                currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
                accountingMode = AccountingMode.ACCOUNT_ONLY, businessType = BusinessType.TRADING
            )
        )
        insertFinancialYear(FinancialYearEntity(fyId, companyId, "FY 2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_D1B_1", companyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
    }

    private fun ledger(id: String, stateCode: String = "27", gstRegistrationStatus: GstRegistrationStatus? = GstRegistrationStatus.REGISTERED) = LedgerEntity(
        ledgerId = id, companyId = companyId, groupId = "GRP_TEST_$companyId", name = id, code = id,
        openingBalancePaise = 0L, openingBalanceType = com.example.accounting.core.common.DrCr.DEBIT,
        currentBalancePaise = 0L, currentBalanceType = com.example.accounting.core.common.DrCr.DEBIT,
        gstin = "27AAAAA0000A1Z5", pan = "", stateCode = stateCode, email = "", phone = "", address = "",
        bankAccountNumber = "", bankIfsc = "", isSystem = false, isActive = true, hsnSacCode = "", defaultTaxRate = 0.0,
        gstRegistrationStatus = gstRegistrationStatus?.name
    )

    private fun line(itemId: String, qty: Long, ratePaise: Long, gstRate: Double, supplyNature: GstSupplyNature = GstSupplyNature.NORMAL, chargeType: GstChargeType = GstChargeType.FORWARD_CHARGE) =
        TradingLineInput(
            itemId = itemId, itemName = itemId, hsnSacCode = "8471", quantity = Quantity.fromLong(qty),
            rate = Money.fromPaise(ratePaise), gstRatePercent = gstRate, supplyNature = supplyNature, chargeType = chargeType
        )

    // ==========================================
    // GST-only Purchase - engine level (buildGstOnlyPurchase), mirrors Phase5TestSuite's a11-a15
    // GST-only Sale coverage exactly, plus RCM (Purchase-only, so Sale never needed this).
    // ==========================================

    @Test
    fun purchase_Taxable_IntraState_SplitsCgstSgst() {
        val result = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "27AAAAA0000A1Z5",
            companyStateCode = "27", placeOfSupply = "27", lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = GstRegistrationStatus.REGISTERED
        )
        val gt = result.single()
        assertNull("GST-only Purchase must never carry a voucherId", gt.voucherId)
        assertEquals(VoucherType.PURCHASE, gt.voucherType)
        assertEquals(GstDirection.INPUT, gt.direction)
        assertEquals(90_00L, gt.cgst.paise)
        assertEquals(90_00L, gt.sgst.paise)
        assertEquals(0L, gt.igst.paise)
    }

    @Test
    fun purchase_InterState_UsesIgstOnly() {
        val result = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "29AAAAA0000A1Z5",
            companyStateCode = "27", placeOfSupply = "29", lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        )
        val gt = result.single()
        assertEquals(180_00L, gt.igst.paise)
        assertEquals(Money.ZERO, gt.cgst)
        assertEquals(SupplyType.INTER_STATE, gt.supplyType)
    }

    @Test
    fun purchase_ZeroRatedExport_ProducesZeroTax_ButPreservesSupplyNature() {
        val result = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "",
            companyStateCode = "27", placeOfSupply = "29", lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0, supplyNature = GstSupplyNature.EXPORT)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        )
        val gt = result.single()
        assertEquals(Money.ZERO, gt.cgst); assertEquals(Money.ZERO, gt.igst)
        assertEquals(SupplyType.EXPORT, gt.supplyType)
        assertEquals("The collapsed SupplyType must not erase the source GstSupplyNature", GstSupplyNature.EXPORT, gt.supplyNature)
    }

    @Test
    fun purchase_Exempt_And_NilRated_BothCollapseToSupplyTypeExempt_ButRemainDistinguishableViaSupplyNature() {
        val exempt = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "",
            companyStateCode = "27", placeOfSupply = "27", lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0, supplyNature = GstSupplyNature.EXEMPT)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        ).single()
        val nilRated = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "",
            companyStateCode = "27", placeOfSupply = "27", lines = listOf(line("ITEM_B", 1, 1000_00L, 18.0, supplyNature = GstSupplyNature.NIL_RATED)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        ).single()

        // The tax engine's own persisted SupplyType genuinely cannot tell these apart - confirming
        // this is real, not a defect this phase introduced.
        assertEquals(SupplyType.EXEMPT, exempt.supplyType)
        assertEquals(SupplyType.EXEMPT, nilRated.supplyType)
        // The whole point of D1b's supplyNature field: they ARE distinguishable there.
        assertEquals(GstSupplyNature.EXEMPT, exempt.supplyNature)
        assertEquals(GstSupplyNature.NIL_RATED, nilRated.supplyNature)
        assertNotEquals(exempt.supplyNature, nilRated.supplyNature)
    }

    @Test
    fun purchase_NormalCharge_And_RcmCharge_RemainDistinguishable() {
        val normal = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "",
            companyStateCode = "27", placeOfSupply = "27", lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        ).single()
        val rcm = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "",
            companyStateCode = "27", placeOfSupply = "27",
            lines = listOf(line("ITEM_B", 1, 1000_00L, 18.0, chargeType = GstChargeType.REVERSE_CHARGE)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        ).single()

        assertEquals(GstChargeType.FORWARD_CHARGE, normal.chargeType)
        assertEquals(GstChargeType.REVERSE_CHARGE, rcm.chargeType)
        // RCM is still a real, computed tax fact for GST-only - just tagged, never a different amount.
        assertEquals(normal.cgst, rcm.cgst)
        assertEquals(normal.sgst, rcm.sgst)
    }

    @Test
    fun purchase_RcmCombinedWithNonNormalNature_Rejected() {
        try {
            TradingWorkflowEngine.buildGstOnlyPurchase(
                companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "",
                companyStateCode = "27", placeOfSupply = "27",
                lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0, supplyNature = GstSupplyNature.EXEMPT, chargeType = GstChargeType.REVERSE_CHARGE)),
                date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
            )
            fail("RCM on a non-Taxable line must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Reverse charge requires a Taxable line") == true)
        }
    }

    @Test
    fun purchase_RcmNeverInferred_FromRegistrationStatusOrRateOrAnythingElse() {
        // An UNREGISTERED, high-rate line still defaults to FORWARD_CHARGE unless the caller
        // explicitly marks REVERSE_CHARGE - proves chargeType is a pure pass-through, never derived.
        val result = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "",
            companyStateCode = "27", placeOfSupply = "27", lines = listOf(line("ITEM_A", 1, 1000_00L, 28.0)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = GstRegistrationStatus.UNREGISTERED
        )
        assertEquals(GstChargeType.FORWARD_CHARGE, result.single().chargeType)
    }

    @Test
    fun purchase_TransactionGroupId_SharedWithinOneCall_DifferentAcrossCalls() {
        val callA = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "",
            companyStateCode = "27", placeOfSupply = "27",
            lines = listOf(line("ITEM_A", 1, 100_00L, 18.0), line("ITEM_B", 1, 200_00L, 18.0)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        )
        val callB = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "",
            companyStateCode = "27", placeOfSupply = "27", lines = listOf(line("ITEM_C", 1, 300_00L, 18.0)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        )
        assertEquals(2, callA.size)
        assertEquals(callA[0].transactionGroupId, callA[1].transactionGroupId)
        assertTrue(callA[0].transactionGroupId.isNotBlank())
        assertNotEquals("Two separate postings must never share a transactionGroupId", callA[0].transactionGroupId, callB[0].transactionGroupId)
    }

    @Test
    fun purchase_TransactionDateAndRegistrationStatus_Stamped() {
        val date = LocalDate.of(2026, 6, 15)
        val result = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "",
            companyStateCode = "27", placeOfSupply = "27", lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)),
            date = date, partyGstRegistrationStatus = GstRegistrationStatus.UNREGISTERED
        )
        assertEquals(date, result.single().transactionDate)
        assertEquals(GstRegistrationStatus.UNREGISTERED, result.single().partyGstRegistrationStatus)
    }

    // ==========================================
    // GST-only Credit Note / Debit Note - engine level (buildGstOnlyNote).
    // ==========================================

    @Test
    fun creditNote_NegatesAmounts_SameDirection_CorrectVoucherType_NoAccountingEntries() {
        val originalSale = TradingWorkflowEngine.buildGstOnlySale(
            companyId = companyId, financialYearId = fyId, customerLedgerId = "LED_CUST", customerGstin = "27AAAAA0000A1Z5",
            companyStateCode = "27", placeOfSupply = "27", lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = GstRegistrationStatus.REGISTERED
        )
        val note = TradingWorkflowEngine.buildGstOnlyNote(
            noteVoucherType = VoucherType.CREDIT_NOTE, originalGstTransactions = originalSale, date = LocalDate.of(2026, 6, 1)
        )
        val orig = originalSale.single(); val rev = note.single()
        assertNull("A GST-only note must never carry a voucherId either", rev.voucherId)
        assertEquals(VoucherType.CREDIT_NOTE, rev.voucherType)
        // Same direction as original (OUTPUT) - NOT flipped to INPUT; standard GST-return semantics.
        assertEquals(orig.direction, rev.direction)
        assertEquals(GstDirection.OUTPUT, rev.direction)
        assertEquals(-orig.taxableAmount.paise, rev.taxableAmount.paise)
        assertEquals(-orig.cgst.paise, rev.cgst.paise)
        assertEquals(-orig.sgst.paise, rev.sgst.paise)
        // Party facts carried forward from the original, not re-derived.
        assertEquals(orig.partyGstin, rev.partyGstin)
        assertEquals(orig.partyGstRegistrationStatus, rev.partyGstRegistrationStatus)
        // The note is its own new correlation id and date, never the original's.
        assertNotEquals(orig.transactionGroupId, rev.transactionGroupId)
        assertEquals(LocalDate.of(2026, 6, 1), rev.transactionDate)
    }

    @Test
    fun debitNote_NegatesAmounts_SameDirection_CorrectVoucherType() {
        val originalPurchase = TradingWorkflowEngine.buildGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP", supplierGstin = "27AAAAA0000A1Z5",
            companyStateCode = "27", placeOfSupply = "27", lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = GstRegistrationStatus.REGISTERED
        )
        val note = TradingWorkflowEngine.buildGstOnlyNote(
            noteVoucherType = VoucherType.DEBIT_NOTE, originalGstTransactions = originalPurchase, date = LocalDate.of(2026, 6, 1)
        )
        val orig = originalPurchase.single(); val rev = note.single()
        assertEquals(VoucherType.DEBIT_NOTE, rev.voucherType)
        assertEquals(GstDirection.INPUT, rev.direction)
        assertEquals(-orig.cgst.paise, rev.cgst.paise)
        assertEquals(-orig.sgst.paise, rev.sgst.paise)
    }

    @Test
    fun note_DoesNotAssumeNaiveFullFieldNegation_OnlyTaxAmountsFlip() {
        val originalSale = TradingWorkflowEngine.buildGstOnlySale(
            companyId = companyId, financialYearId = fyId, customerLedgerId = "LED_CUST", customerGstin = "27AAAAA0000A1Z5",
            companyStateCode = "27", placeOfSupply = "27", lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        )
        val rev = TradingWorkflowEngine.buildGstOnlyNote(VoucherType.CREDIT_NOTE, originalSale, LocalDate.of(2026, 6, 1)).single()
        val orig = originalSale.single()
        // Rate/HSN/placeOfSupply/gstRatePercent/direction are facts about the SAME supply, not sign-flipped.
        assertEquals(orig.gstRatePercent, rev.gstRatePercent, 0.0001)
        assertEquals(orig.hsnSacCode, rev.hsnSacCode)
        assertEquals(orig.placeOfSupply, rev.placeOfSupply)
        assertEquals(orig.direction, rev.direction)
    }

    // ==========================================
    // Repository-level rejection paths - genuinely executable in pure JVM (no real Room needed)
    // since the dbTransaction guard was moved after these business-rule checks.
    // ==========================================

    @Test
    fun repo_PostGstOnlyPurchase_MissingPlaceOfSupply_Rejected() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        dao.insertLedger(ledger("LED_SUPP_NOSTATE", stateCode = ""))
        val repo = AccountingRepository(dao)

        val result = repo.postGstOnlyPurchase(
            companyId = companyId, financialYearId = fyId, supplierLedgerId = "LED_SUPP_NOSTATE",
            lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)), date = LocalDate.of(2026, 5, 10)
        )
        assertTrue(result is AccountingResult.Failure)
        assertTrue((result as AccountingResult.Failure).error is AppError.ValidationError)
        assertTrue(result.error.message.contains("Place of Supply"))
    }

    @Test
    fun repo_PostGstOnlyPurchase_CompanyNotFound_Rejected() = runBlocking {
        val dao = freshDao()
        val repo = AccountingRepository(dao)
        val result = repo.postGstOnlyPurchase(
            companyId = "COMP_DOES_NOT_EXIST", financialYearId = "FY_DOES_NOT_EXIST", supplierLedgerId = "LED_X",
            lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)), date = LocalDate.of(2026, 5, 10)
        )
        assertTrue(result is AccountingResult.Failure)
    }

    @Test
    fun repo_PostGstOnlyCreditNote_OriginalNotFound_Rejected() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repo = AccountingRepository(dao)
        val result = repo.postGstOnlyCreditNote(companyId, fyId, "GROUP_DOES_NOT_EXIST", LocalDate.of(2026, 6, 1))
        assertTrue(result is AccountingResult.Failure)
        assertTrue((result as AccountingResult.Failure).error is AppError.ResourceNotFound)
    }

    @Test
    fun repo_PostGstOnlyCreditNote_WrongOriginalType_Rejected() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        dao.insertLedger(ledger("LED_SUPP"))
        val repo = AccountingRepository(dao)

        // Post a real GST-only Purchase, then try to issue a CREDIT NOTE (Sale-only) against it.
        val purchaseResult = repo.postGstOnlyPurchase(
            companyId, fyId, "LED_SUPP", listOf(line("ITEM_A", 1, 1000_00L, 18.0)), LocalDate.of(2026, 5, 10)
        )
        // dbTransaction is null in this pure-JVM test, so the purchase itself cannot actually
        // persist - insert its would-be rows directly (same "simulate an already-posted fact"
        // pattern Phase2AuditTestSuite uses for historical vouchers) so the wrong-type check has
        // real data to reject against.
        assertTrue(purchaseResult is AccountingResult.Failure) // confirms dbTransaction really is null here
        val fakeGroupId = "GROUP_PURCHASE_1"
        dao.insertGstTransactions(
            listOf(
                com.example.accounting.data.local.entity.GstTransactionEntity(
                    gstTransactionId = "GST_1", companyId = companyId, financialYearId = fyId, voucherId = null,
                    voucherType = VoucherType.PURCHASE, partyLedgerId = "LED_SUPP", partyGstin = "", placeOfSupply = "27",
                    supplyType = SupplyType.INTRA_STATE, itemId = "ITEM_A", hsnSacCode = "8471", quantityRaw = null,
                    taxableAmountPaise = 1000_00L, gstRatePercent = 18.0, cgstPaise = 90_00L, sgstPaise = 90_00L,
                    igstPaise = 0L, cessPaise = 0L, direction = GstDirection.INPUT, lineOrder = 1, createdAt = 0L,
                    transactionGroupId = fakeGroupId
                )
            )
        )
        val result = repo.postGstOnlyCreditNote(companyId, fyId, fakeGroupId, LocalDate.of(2026, 6, 1))
        assertTrue(result is AccountingResult.Failure)
        assertTrue((result as AccountingResult.Failure).error is AppError.ValidationError)
        assertTrue(result.error.message.contains("Credit"))
    }

    // ==========================================
    // GST-only Sale - existing implementation still passes. Not duplicated here in full (Phase5TestSuite's
    // a11-a15 already cover it exhaustively and were updated for D1b's new required date/
    // partyGstRegistrationStatus params) - this is a single, direct confirmation in this file too.
    // ==========================================

    @Test
    fun sale_ExistingImplementation_StillProducesCorrectFacts_WithD1bFieldsAdded() {
        val result = TradingWorkflowEngine.buildGstOnlySale(
            companyId = companyId, financialYearId = fyId, customerLedgerId = "LED_CUST", customerGstin = "27AAAAA0000A1Z5",
            companyStateCode = "27", placeOfSupply = "27", lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)),
            date = LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = GstRegistrationStatus.REGISTERED
        )
        val gt = result.single()
        assertNull(gt.voucherId)
        assertEquals(VoucherType.SALES, gt.voucherType)
        assertEquals(90_00L, gt.cgst.paise)
        assertEquals(90_00L, gt.sgst.paise)
        assertTrue(gt.transactionGroupId.isNotBlank())
        assertEquals(LocalDate.of(2026, 5, 10), gt.transactionDate)
        assertEquals(GstRegistrationStatus.REGISTERED, gt.partyGstRegistrationStatus)
    }
}
