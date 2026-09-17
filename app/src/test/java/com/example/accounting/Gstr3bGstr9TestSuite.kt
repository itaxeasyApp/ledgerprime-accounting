package com.example.accounting

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.domain.taxation.gstreturn.Gstr1B2clInvoice
import com.example.accounting.domain.taxation.gstreturn.Gstr1B2csRow
import com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnData
import com.example.accounting.domain.taxation.gstreturn.Gstr1ValidationSeverity
import com.example.accounting.domain.taxation.gstreturn.Gstr3bReturnBuilder
import com.example.accounting.domain.taxation.gstreturn.Gstr3bReturnData
import com.example.accounting.domain.taxation.gstreturn.Gstr3bValidator
import com.example.accounting.domain.taxation.gstreturn.Gstr9ItcSummary
import com.example.accounting.domain.taxation.gstreturn.Gstr9OutwardSummary
import com.example.accounting.domain.taxation.gstreturn.Gstr9ReturnBuilder
import com.example.accounting.domain.taxation.gstreturn.Gstr9Validator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 8 - GSTR-3B and GSTR-9 foundation test suite, mirroring [Gstr1FoundationTestSuite]'s own
 * "pure domain, no DAO needed" style - these builders/validators take already-fetched data and
 * return already-computed results, no Room/Robolectric dependency.
 */
class Gstr3bGstr9TestSuite {

    private fun gt(
        voucherId: String?, ledgerId: String, gstin: String, pos: String, supplyType: SupplyType,
        direction: GstDirection = GstDirection.OUTPUT, chargeType: GstChargeType = GstChargeType.FORWARD_CHARGE,
        supplyNature: GstSupplyNature = GstSupplyNature.NORMAL, hsn: String = "8471", rate: Double = 18.0, taxable: Long = 100_000_00L
    ): GstTransaction {
        val taxableM = Money.fromPaise(taxable)
        val (cgst, sgst, igst) = when (supplyType) {
            SupplyType.INTRA_STATE -> Triple(taxableM.percentage(rate / 2), taxableM.percentage(rate / 2), Money.ZERO)
            SupplyType.INTER_STATE -> Triple(Money.ZERO, Money.ZERO, taxableM.percentage(rate))
            else -> Triple(Money.ZERO, Money.ZERO, Money.ZERO)
        }
        return GstTransaction(
            gstTransactionId = UUID.randomUUID().toString(), companyId = "C1", financialYearId = "FY1",
            voucherId = voucherId, voucherType = if (direction == GstDirection.OUTPUT) VoucherType.SALES else VoucherType.PURCHASE,
            partyLedgerId = ledgerId, partyGstin = gstin, placeOfSupply = pos, supplyType = supplyType,
            itemId = null, hsnSacCode = hsn, quantity = Quantity.fromLong(1), taxableAmount = taxableM, gstRatePercent = rate,
            cgst = cgst, sgst = sgst, igst = igst, cess = Money.ZERO, direction = direction, lineOrder = 1,
            chargeType = chargeType, supplyNature = supplyNature
        )
    }

    // ==========================================
    // Gstr3bReturnBuilder
    // ==========================================

    @Test
    fun t1_Gstr3b_TaxableOutward_SumsIntraAndInterState() {
        val intra = gt("V1", "L1", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE, taxable = 100_00L)
        val inter = gt("V2", "L2", "29AAPFU0939F1ZV", "29", SupplyType.INTER_STATE, taxable = 200_00L)
        val data = Gstr3bReturnBuilder.build("27AAPFU0939F1ZV", "202604", listOf(intra, inter))
        assertEquals(300_00L, data.outward.taxableOutward.taxableValue.paise)
    }

    @Test
    fun t2_Gstr3b_ZeroRatedExports_SeparateFromTaxableOutward() {
        val export = gt("V3", "L3", "", "96", SupplyType.EXPORT, supplyNature = GstSupplyNature.EXPORT, taxable = 500_00L)
        val data = Gstr3bReturnBuilder.build("27AAPFU0939F1ZV", "202604", listOf(export))
        assertEquals(0L, data.outward.taxableOutward.taxableValue.paise)
        assertEquals(500_00L, data.outward.zeroRatedOutward.taxableValue.paise)
    }

    @Test
    fun t3_Gstr3b_ReverseChargeInward_IsRecipientsOwnLiability_NeverOutwardTax() {
        val rcmPurchase = gt("V4", "L4", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE, direction = GstDirection.INPUT, chargeType = GstChargeType.REVERSE_CHARGE, taxable = 50_00L)
        val data = Gstr3bReturnBuilder.build("27AAPFU0939F1ZV", "202604", listOf(rcmPurchase))
        assertEquals(50_00L, data.outward.reverseChargeInward.taxableValue.paise)
        assertEquals(0L, data.outward.taxableOutward.taxableValue.paise)
        // This company's own RCM liability is real cgst+sgst (intra-state), never zero.
        assertTrue(data.outward.reverseChargeInward.cgst.paise > 0L)
    }

    @Test
    fun t4_Gstr3b_ItcAvailable_SplitsForwardChargeFromReverseCharge() {
        val forward = gt("V5", "L5", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE, direction = GstDirection.INPUT, chargeType = GstChargeType.FORWARD_CHARGE, taxable = 1000_00L)
        val reverse = gt("V6", "L6", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE, direction = GstDirection.INPUT, chargeType = GstChargeType.REVERSE_CHARGE, taxable = 200_00L)
        val data = Gstr3bReturnBuilder.build("27AAPFU0939F1ZV", "202604", listOf(forward, reverse))
        assertEquals(1000_00L, data.itc.available.allOtherItc.taxableValue.paise)
        assertEquals(200_00L, data.itc.available.inwardReverseCharge.taxableValue.paise)
        assertEquals(1200_00L, data.itc.netItc.taxableValue.paise)
    }

    @Test
    fun t5_Gstr3b_InterStateUnregistered_GroupedByPlaceOfSupply() {
        val row1 = gt("V7", "L7", "", "29", SupplyType.INTER_STATE, taxable = 100_00L)
        val row2 = gt("V8", "L8", "", "29", SupplyType.INTER_STATE, taxable = 150_00L)
        val row3 = gt("V9", "L9", "", "33", SupplyType.INTER_STATE, taxable = 50_00L)
        val data = Gstr3bReturnBuilder.build("27AAPFU0939F1ZV", "202604", listOf(row1, row2, row3))
        val ka = data.interStateUnregistered.first { it.posStateCode == "29" }
        assertEquals(250_00L, ka.taxableValue.paise)
        assertEquals(1, data.interStateUnregistered.count { it.posStateCode == "33" })
    }

    @Test
    fun t6_Gstr3b_RegisteredInterState_NeverCountedAsUnregistered() {
        val b2b = gt("V10", "L10", "29AAPFU0939F1ZV", "29", SupplyType.INTER_STATE, taxable = 100_00L)
        val data = Gstr3bReturnBuilder.build("27AAPFU0939F1ZV", "202604", listOf(b2b))
        assertTrue(data.interStateUnregistered.isEmpty())
    }

    @Test
    fun t7_Gstr3b_EstimatedCashLiability_NetsOutputAgainstItc_FlooredAtZero() {
        val output = gt("V11", "L11", "", "27", SupplyType.INTRA_STATE, taxable = 100_00L, rate = 18.0) // cgst+sgst = 9+9
        val itcInput = gt("V12", "L12", "", "27", SupplyType.INTRA_STATE, direction = GstDirection.INPUT, taxable = 200_00L, rate = 18.0) // cgst+sgst = 18+18, more than output
        val data = Gstr3bReturnBuilder.build("27AAPFU0939F1ZV", "202604", listOf(output, itcInput))
        val cash = data.estimatedCashLiability
        assertEquals(0L, cash.cgst.paise) // ITC exceeds output tax - floored at zero, never negative
        assertEquals(0L, cash.sgst.paise)
    }

    @Test
    fun t8_Gstr3b_ReverseChargeLiability_AlwaysCashPaid_NeverNettedAgainstItc() {
        // Real ITC available (from an ordinary forward-charge purchase) is large; the RCM liability
        // must still show up in full in estimatedCashLiability - RCM is never offset by ITC.
        val itc = gt("V13", "L13", "", "27", SupplyType.INTRA_STATE, direction = GstDirection.INPUT, taxable = 1000_00L, rate = 18.0)
        val rcm = gt("V14", "L14", "", "27", SupplyType.INTRA_STATE, direction = GstDirection.INPUT, chargeType = GstChargeType.REVERSE_CHARGE, taxable = 50_00L, rate = 18.0)
        val data = Gstr3bReturnBuilder.build("27AAPFU0939F1ZV", "202604", listOf(itc, rcm))
        val expectedRcmCgst = data.outward.reverseChargeInward.cgst
        assertTrue(data.estimatedCashLiability.cgst.paise >= expectedRcmCgst.paise)
    }

    // ==========================================
    // Gstr3bValidator
    // ==========================================

    @Test
    fun t9_Gstr3bValidator_ValidCompanyGstin_NoGstinError() {
        val issues = Gstr3bValidator.validate("27AAPFU0939F1ZV", emptyList(), isNilReturn = true)
        assertFalse(issues.any { it.code.contains("GSTIN") })
    }

    @Test
    fun t10_Gstr3bValidator_InvalidCompanyGstinChecksum_IsBlockingError() {
        val issues = Gstr3bValidator.validate("27AAPFU0939F1ZZ", emptyList(), isNilReturn = true)
        val issue = issues.first { it.code == "INVALID_COMPANY_GSTIN_CHECKSUM" }
        assertEquals(Gstr1ValidationSeverity.ERROR, issue.severity)
    }

    @Test
    fun t11_Gstr3bValidator_BlankCompanyGstin_IsBlockingError() {
        val issues = Gstr3bValidator.validate("", emptyList(), isNilReturn = true)
        assertTrue(issues.any { it.code == "MISSING_COMPANY_GSTIN" && it.severity == Gstr1ValidationSeverity.ERROR })
    }

    @Test
    fun t12_Gstr3bValidator_ZeroActivityNotDeclaredNil_IsWarningOnly() {
        val issues = Gstr3bValidator.validate("27AAPFU0939F1ZV", emptyList(), isNilReturn = false)
        val issue = issues.first { it.code == "ZERO_ACTIVITY_NOT_DECLARED_NIL" }
        assertEquals(Gstr1ValidationSeverity.WARNING, issue.severity)
    }

    @Test
    fun t13_Gstr3bValidator_ZeroActivityButDeclaredNil_NoWarning() {
        val issues = Gstr3bValidator.validate("27AAPFU0939F1ZV", emptyList(), isNilReturn = true)
        assertFalse(issues.any { it.code == "ZERO_ACTIVITY_NOT_DECLARED_NIL" })
    }

    @Test
    fun t14_Gstr3bValidator_InvalidPlaceOfSupply_FlaggedAsError() {
        val bad = gt("V15", "L15", "", "99", SupplyType.INTER_STATE)
        val issues = Gstr3bValidator.validate("27AAPFU0939F1ZV", listOf(bad), isNilReturn = false)
        assertTrue(issues.any { it.code == "INVALID_PLACE_OF_SUPPLY" })
    }

    // ==========================================
    // Gstr9ReturnBuilder - regroups already-built Gstr1ReturnData/Gstr3bReturnData
    // ==========================================

    @Test
    fun t15_Gstr9_B2cAggregatesB2clAndB2cs() {
        val gstr1 = Gstr1ReturnData(
            companyGstin = "27AAPFU0939F1ZV", periodKey = "2026-27",
            b2cl = listOf(Gstr1B2clInvoice("V16", "INV1", java.time.LocalDate.of(2026, 4, 1), "29", emptyList())),
            b2cs = listOf(Gstr1B2csRow("27", 18.0, Money.fromPaise(500_00L), Money.fromPaise(45_00L), Money.fromPaise(45_00L), Money.ZERO, Money.ZERO))
        )
        val gstr3b = Gstr3bReturnBuilder.build("27AAPFU0939F1ZV", "2026-27", emptyList())
        val data = Gstr9ReturnBuilder.build("27AAPFU0939F1ZV", "2026-27", gstr1, gstr3b, emptyList())
        // b2cl's own invoice has no rate lines in this test (0), b2cs contributes 500.
        assertEquals(500_00L, data.outward.b2c.taxableValue.paise)
    }

    @Test
    fun t16_Gstr9_NetTaxPayable_SubtractsCreditNotesFromSubtotal() {
        val outward = Gstr9OutwardSummary(
            b2c = com.example.accounting.domain.taxation.gstreturn.Gstr3bTaxSummary(Money.fromPaise(1000_00L), Money.ZERO, Money.fromPaise(90_00L), Money.fromPaise(90_00L), Money.ZERO),
            b2b = com.example.accounting.domain.taxation.gstreturn.Gstr3bTaxSummary.ZERO,
            zeroRatedExports = Money.ZERO,
            inwardReverseCharge = com.example.accounting.domain.taxation.gstreturn.Gstr3bTaxSummary.ZERO,
            creditNotesIssued = com.example.accounting.domain.taxation.gstreturn.Gstr3bTaxSummary(Money.fromPaise(100_00L), Money.ZERO, Money.fromPaise(9_00L), Money.fromPaise(9_00L), Money.ZERO)
        )
        assertEquals(900_00L, outward.netTaxPayable.taxableValue.paise)
        assertEquals(81_00L, outward.netTaxPayable.cgst.paise)
    }

    @Test
    fun t17_Gstr9_HsnInward_GroupsByHsnAndRate_AcrossTheYear() {
        val gstr1 = Gstr1ReturnData(companyGstin = "27AAPFU0939F1ZV", periodKey = "2026-27")
        val gstr3b = Gstr3bReturnBuilder.build("27AAPFU0939F1ZV", "2026-27", emptyList())
        val jan = gt("V17", "L17", "", "27", SupplyType.INTRA_STATE, direction = GstDirection.INPUT, hsn = "1001", rate = 5.0, taxable = 100_00L)
        val mar = gt("V18", "L18", "", "27", SupplyType.INTRA_STATE, direction = GstDirection.INPUT, hsn = "1001", rate = 5.0, taxable = 200_00L)
        val other = gt("V19", "L19", "", "27", SupplyType.INTRA_STATE, direction = GstDirection.INPUT, hsn = "2002", rate = 12.0, taxable = 50_00L)
        val data = Gstr9ReturnBuilder.build("27AAPFU0939F1ZV", "2026-27", gstr1, gstr3b, listOf(jan, mar, other))
        val row1001 = data.hsnInward.first { it.hsnSacCode == "1001" }
        assertEquals(300_00L, row1001.taxableValue.paise)
        assertEquals(2, data.hsnInward.size)
    }

    @Test
    fun t18_Gstr9_TaxPaid_SplitsPayableBetweenItcAndCash() {
        val gstr1 = Gstr1ReturnData(companyGstin = "27AAPFU0939F1ZV", periodKey = "2026-27", b2cs = listOf(Gstr1B2csRow("27", 18.0, Money.fromPaise(1000_00L), Money.fromPaise(90_00L), Money.fromPaise(90_00L), Money.ZERO, Money.ZERO)))
        val itcRow = gt("V20", "L20", "", "27", SupplyType.INTRA_STATE, direction = GstDirection.INPUT, taxable = 500_00L, rate = 18.0) // cgst=sgst=45
        val gstr3b = Gstr3bReturnBuilder.build("27AAPFU0939F1ZV", "2026-27", listOf(itcRow))
        val data = Gstr9ReturnBuilder.build("27AAPFU0939F1ZV", "2026-27", gstr1, gstr3b, emptyList())
        val cgstRow = data.taxPaid.first { it.head == "CGST" }
        assertEquals(90_00L, cgstRow.taxPayable.paise)
        assertEquals(45_00L, cgstRow.paidThroughItc.paise) // ITC available is less than payable
        assertEquals(45_00L, cgstRow.paidInCash.paise)
    }

    // ==========================================
    // Gstr9Validator
    // ==========================================

    @Test
    fun t19_Gstr9Validator_AllPeriodsFiled_NoWarning() {
        val issues = Gstr9Validator.validate("27AAPFU0939F1ZV", filedPeriodCount = 12, totalPeriodCount = 12, isNilReturn = false, hasAnyActivity = true)
        assertFalse(issues.any { it.code == "UNDERLYING_PERIODS_NOT_ALL_FILED" })
    }

    @Test
    fun t20_Gstr9Validator_SomePeriodsUnfiled_WarnsButNotBlocking() {
        val issues = Gstr9Validator.validate("27AAPFU0939F1ZV", filedPeriodCount = 8, totalPeriodCount = 12, isNilReturn = false, hasAnyActivity = true)
        val issue = issues.first { it.code == "UNDERLYING_PERIODS_NOT_ALL_FILED" }
        assertEquals(Gstr1ValidationSeverity.WARNING, issue.severity)
    }

    @Test
    fun t21_Gstr9Validator_InvalidGstinChecksum_IsBlockingError() {
        val issues = Gstr9Validator.validate("27AAPFU0939F1ZZ", filedPeriodCount = 12, totalPeriodCount = 12, isNilReturn = false, hasAnyActivity = true)
        assertTrue(issues.any { it.code == "INVALID_COMPANY_GSTIN_CHECKSUM" && it.severity == Gstr1ValidationSeverity.ERROR })
    }
}
