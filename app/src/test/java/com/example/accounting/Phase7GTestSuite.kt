package com.example.accounting

import com.example.accounting.application.profile.ProfileApplicationService
import com.example.accounting.application.profile.TenantMismatchException
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.BusinessProfileEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.rendering.BusinessProfile
import com.example.accounting.domain.rendering.ConstitutionType
import com.example.accounting.domain.taxation.gst.GSTRules
import com.example.accounting.domain.taxation.gst.GstCalculationEngine
import com.example.accounting.domain.taxation.gst.SupplyType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 7G (Business/Individual Profile) test suite - three targeted areas: (1) a
 * [BusinessProfile] display-field update must leak zero mutations into any accounting-engine
 * ledger, (2) GST supply-type classification ([GSTRules.determineSupplyType]) must be exact for
 * matching vs. mismatching state codes, and (3) a cross-tenant profile request must fail
 * immediately with [TenantMismatchException]. Mock data uses real Indian GST state codes and
 * ordinary B2B invoice-sized amounts throughout - never placeholder/round-trip-only numbers.
 */
class Phase7GTestSuite {

    private class ProfileAwareDao(delegate: AccountingDao) : AccountingDao by delegate {
        private val businessProfiles = mutableMapOf<String, BusinessProfileEntity>()
        override suspend fun getBusinessProfile(companyId: String) = businessProfiles[companyId]
        override suspend fun insertBusinessProfile(profile: BusinessProfileEntity) { businessProfiles[profile.companyId] = profile }
        override suspend fun updateBusinessProfile(
            companyId: String, businessProfileId: String, businessName: String, legalName: String,
            constitutionType: ConstitutionType, address: String, pinCode: String, city: String, state: String, country: String,
            phone: String, email: String, website: String, gstin: String, pan: String, tan: String, udyam: String, logoAssetId: String?,
            bankName: String, bankAccountNumber: String, bankIfsc: String, bankBranch: String, upiId: String,
            qrCodeAssetId: String?, signatureAssetId: String?, termsAndConditions: String, updatedAt: Long
        ) {
            val existing = businessProfiles[companyId] ?: return
            if (existing.businessProfileId != businessProfileId) return
            businessProfiles[companyId] = existing.copy(
                businessName = businessName, legalName = legalName, constitutionType = constitutionType, address = address,
                pinCode = pinCode, city = city, state = state, country = country,
                phone = phone, email = email, website = website, gstin = gstin, pan = pan, tan = tan, udyam = udyam,
                logoAssetId = logoAssetId, bankName = bankName, bankAccountNumber = bankAccountNumber, bankIfsc = bankIfsc,
                bankBranch = bankBranch, upiId = upiId, qrCodeAssetId = qrCodeAssetId, signatureAssetId = signatureAssetId,
                termsAndConditions = termsAndConditions, updatedAt = updatedAt
            )
        }
    }

    // Real GST state codes (Maharashtra / Karnataka / Delhi) - not fictional placeholders.
    private val maharashtraStateCode = "27"
    private val karnatakaStateCode = "29"
    private val delhiStateCode = "07"

    private val companyId = "COMP_BHARAT_TEXTILES"
    private val otherCompanyId = "COMP_DECCAN_TRADERS"
    private val fyId = "FY_2026_27"

    private fun freshDao() = ProfileAwareDao(FakeAccountingDao())

    private suspend fun AccountingDao.seed(targetCompanyId: String, stateCode: String) {
        insertCompany(CompanyEntity(
            companyId = targetCompanyId, name = "Bharat Textiles Pvt Ltd", tradeName = "Bharat Textiles",
            gstin = "27AABCB1234D1Z5", pan = "AABCB1234D", stateCode = stateCode, stateName = "Maharashtra",
            email = "accounts@bharattextiles.in", phone = "9820012345", address = "14 Mill Road, Mumbai",
            currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
            accountingMode = AccountingMode.ACCOUNT_ONLY, businessType = BusinessType.TRADING
        ))
        insertFinancialYear(FinancialYearEntity(fyId, targetCompanyId, "FY 2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_$targetCompanyId", targetCompanyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
        // Realistic opening balances - a small trading business, not round test placeholders.
        insertLedger(ledger("LED_CASH_$targetCompanyId", targetCompanyId, StandardSystemGroups.CASH_GROUP_ID, openingPaise = 52_340_00L))
        insertLedger(ledger("LED_DEBTOR_$targetCompanyId", targetCompanyId, StandardSystemGroups.DEBTORS_GROUP_ID, openingPaise = 0L))
        insertLedger(ledger("LED_SALES_$targetCompanyId", targetCompanyId, StandardSystemGroups.SALES_GROUP_ID, openingType = DrCr.CREDIT))
    }

    private fun ledger(id: String, targetCompanyId: String, bareGroup: String, openingPaise: Long = 0L, openingType: DrCr = DrCr.DEBIT) = LedgerEntity(
        id, targetCompanyId, "${bareGroup}_$targetCompanyId", id, "", openingPaise, openingType, openingPaise, openingType,
        "27AABCB1234D1Z5", "AABCB1234D", "27", "", "", "", "", "", "", "", false, true, "", 0.0
    )

    // ==========================================
    // 1. BusinessProfile display-field updates leak zero mutations into ledgers
    // ==========================================
    @Test
    fun testUpdateBusinessProfileDisplayFields_zeroMutationsLeakIntoAccountingLedgers() = runBlocking {
        val dao = freshDao()
        dao.seed(companyId, maharashtraStateCode)
        val repository = AccountingRepository(dao)
        val service = ProfileApplicationService(repository)

        // A realistic B2B cash sale: Dr Cash / Cr Sales, taxable value only (GST voucher setup is
        // out of scope for this test - only that a later profile edit never touches this data).
        val invoiceTaxableAmountPaise = 85_000_00L
        val voucher = VoucherEntity(
            voucherId = "V_INV_1001", companyId = companyId, financialYearId = fyId, voucherNumber = "INV-2026-1001",
            voucherType = VoucherType.SALES, date = "2026-06-12", referenceNumber = "PO-4471",
            narration = "Sale of cotton fabric - 500m", totalAmountPaise = invoiceTaxableAmountPaise,
            isPosted = true, isCancelled = false, syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L,
            createdBy = "ACCOUNTANT", partyGstin = "", isGstApplicable = false
        )
        VoucherPostingEngine.post(
            dao, voucher,
            listOf(
                JournalItemEntity("V_INV_1001-1", "V_INV_1001", companyId, fyId, "LED_CASH_$companyId", DrCr.DEBIT, invoiceTaxableAmountPaise, "", 1),
                JournalItemEntity("V_INV_1001-2", "V_INV_1001", companyId, fyId, "LED_SALES_$companyId", DrCr.CREDIT, invoiceTaxableAmountPaise, "", 2)
            ),
            "IK_V_INV_1001", "ACCOUNTANT"
        )

        service.upsertBusinessProfile(
            companyId,
            BusinessProfile(businessProfileId = "", companyId = companyId, businessName = "Bharat Textiles", legalName = "Bharat Textiles Pvt Ltd")
        )

        val ledgersBefore = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        val journalItemsBefore = dao.getJournalItemsForVoucherSync("V_INV_1001")

        // Edit only display/branding fields - a realistic profile-settings update, not an
        // accounting operation.
        service.upsertBusinessProfile(
            companyId,
            BusinessProfile(
                businessProfileId = "", companyId = companyId, businessName = "Bharat Textiles Exports",
                legalName = "Bharat Textiles Pvt Ltd", address = "22 Andheri Industrial Estate, Mumbai 400093",
                phone = "9820099887", email = "sales@bharattextiles.in", website = "https://bharattextiles.in"
            )
        )

        val ledgersAfter = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        val journalItemsAfter = dao.getJournalItemsForVoucherSync("V_INV_1001")

        assertEquals("Ledger set must be unchanged (no ledger created/deleted)", ledgersBefore.keys, ledgersAfter.keys)
        for ((ledgerId, before) in ledgersBefore) {
            assertEquals("Ledger '$ledgerId' must be byte-identical before/after a profile edit", before, ledgersAfter.getValue(ledgerId))
        }
        assertEquals("Journal items for the already-posted voucher must be untouched", journalItemsBefore, journalItemsAfter)
        // Sanity: Cash correctly reflects opening balance + the posted sale (52,340.00 + 85,000.00),
        // confirming the "before" snapshot itself captured the voucher's real effect, not a no-op.
        assertEquals(137_340_00L, ledgersAfter.getValue("LED_CASH_$companyId").currentBalancePaise)
        assertEquals("Bharat Textiles Exports", service.getBusinessProfile(companyId)?.businessName)
    }

    // ==========================================
    // 2. GST supply-type classification - matching vs. mismatching state codes
    // ==========================================
    @Test
    fun testSupplyTypeClassification_matchingStateCodes_isIntraState() {
        assertEquals(SupplyType.INTRA_STATE, GSTRules.determineSupplyType(maharashtraStateCode, maharashtraStateCode))
    }

    @Test
    fun testSupplyTypeClassification_mismatchingStateCodes_isInterState() {
        assertEquals(SupplyType.INTER_STATE, GSTRules.determineSupplyType(maharashtraStateCode, karnatakaStateCode))
        assertEquals(SupplyType.INTER_STATE, GSTRules.determineSupplyType(delhiStateCode, maharashtraStateCode))
    }

    @Test
    fun testGstCalculation_intraState_splitsIntoEqualCgstAndSgst_realisticInvoiceAmount() {
        // A ₹85,000 taxable sale at 18% GST, seller and buyer both in Maharashtra.
        val breakdown = GstCalculationEngine.calculate(
            taxableAmount = Money.fromPaise(85_000_00L), taxRatePercent = 18.0,
            companyStateCode = maharashtraStateCode, partyStateCode = maharashtraStateCode
        )
        assertEquals(SupplyType.INTRA_STATE, breakdown.supplyType)
        assertEquals(7_650_00L, breakdown.cgstAmount.paise) // 9% of 85,000
        assertEquals(7_650_00L, breakdown.sgstAmount.paise) // 9% of 85,000
        assertEquals(0L, breakdown.igstAmount.paise)
        assertEquals(15_300_00L, breakdown.totalTax.paise) // 18% of 85,000
        assertEquals(1_00_300_00L, breakdown.totalWithTax.paise)
    }

    @Test
    fun testGstCalculation_interState_isSingleIgstLine_realisticInvoiceAmount() {
        // The same ₹85,000 taxable sale, but the buyer is in Karnataka - inter-state.
        val breakdown = GstCalculationEngine.calculate(
            taxableAmount = Money.fromPaise(85_000_00L), taxRatePercent = 18.0,
            companyStateCode = maharashtraStateCode, partyStateCode = karnatakaStateCode
        )
        assertEquals(SupplyType.INTER_STATE, breakdown.supplyType)
        assertEquals(0L, breakdown.cgstAmount.paise)
        assertEquals(0L, breakdown.sgstAmount.paise)
        assertEquals(15_300_00L, breakdown.igstAmount.paise) // 18% of 85,000, as one IGST line
        assertEquals(15_300_00L, breakdown.totalTax.paise)
        assertEquals(1_00_300_00L, breakdown.totalWithTax.paise)
    }

    // ==========================================
    // 3. Cross-tenant profile requests fail immediately with TenantMismatchException
    // ==========================================
    @Test
    fun testCrossTenantProfileUpdate_failsImmediatelyWithTenantMismatchException() = runBlocking {
        val dao = freshDao()
        dao.seed(companyId, maharashtraStateCode)
        dao.seed(otherCompanyId, karnatakaStateCode)
        val service = ProfileApplicationService(AccountingRepository(dao))

        val bharatTextilesProfile = BusinessProfile(
            businessProfileId = "", companyId = companyId, businessName = "Bharat Textiles",
            legalName = "Bharat Textiles Pvt Ltd", gstin = "27AABCB1234D1Z5", pan = "AABCB1234D"
        )

        try {
            // Deccan Traders' own session context attempting to write into Bharat Textiles' profile.
            service.upsertBusinessProfile(otherCompanyId, bharatTextilesProfile)
            fail("Expected TenantMismatchException - Deccan Traders must never be able to write Bharat Textiles' profile")
        } catch (e: TenantMismatchException) {
            assertEquals(otherCompanyId, e.contextCompanyId)
            assertEquals(companyId, e.resourceCompanyId)
        }

        // Confirm no partial write occurred - Deccan Traders still has no profile of its own.
        assertTrue(service.getBusinessProfile(otherCompanyId) == null)
    }

    @Test
    fun testCrossTenantProfileRead_neverReturnsAnotherCompanysData() = runBlocking {
        val dao = freshDao()
        dao.seed(companyId, maharashtraStateCode)
        dao.seed(otherCompanyId, karnatakaStateCode)
        val service = ProfileApplicationService(AccountingRepository(dao))

        service.upsertBusinessProfile(companyId, BusinessProfile(businessProfileId = "", companyId = companyId, businessName = "Bharat Textiles"))
        service.upsertBusinessProfile(otherCompanyId, BusinessProfile(businessProfileId = "", companyId = otherCompanyId, businessName = "Deccan Traders"))

        assertEquals("Bharat Textiles", service.getBusinessProfile(companyId)?.businessName)
        assertEquals("Deccan Traders", service.getBusinessProfile(otherCompanyId)?.businessName)
    }
}
