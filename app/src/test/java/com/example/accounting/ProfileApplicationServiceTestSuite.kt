package com.example.accounting

import com.example.accounting.application.profile.ProfileApplicationService
import com.example.accounting.application.profile.SensitiveDataMasker
import com.example.accounting.application.profile.TenantMismatchException
import com.example.accounting.application.profile.toLogSafeString
import com.example.accounting.application.profile.toMaskedSummary
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.BusinessProfileEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.IndividualProfileEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.banking.BankUpiProfile
import com.example.accounting.domain.banking.UpiMetadata
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.rendering.BusinessProfile
import com.example.accounting.domain.rendering.IndividualProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests [ProfileApplicationService] - tenant-isolation enforcement (explicit
 * [TenantMismatchException], not a silent null/wrong result), [SensitiveDataMasker] correctness,
 * and the zero-ledger-side-effect guarantee for profile mutations. [ProfileAwareDao] adds real
 * `BusinessProfile`/`IndividualProfile` backing on top of the base `FakeAccountingDao` (whose own
 * versions are permanent no-op stubs, same pattern established since Phase 7D).
 */
class ProfileApplicationServiceTestSuite {

    private class ProfileAwareDao(delegate: AccountingDao) : AccountingDao by delegate {
        private val businessProfiles = mutableMapOf<String, BusinessProfileEntity>()
        override suspend fun getBusinessProfile(companyId: String) = businessProfiles[companyId]
        override suspend fun insertBusinessProfile(profile: BusinessProfileEntity) { businessProfiles[profile.companyId] = profile }
        override suspend fun updateBusinessProfile(
            companyId: String, businessProfileId: String, businessName: String, legalName: String,
            constitutionType: com.example.accounting.domain.rendering.ConstitutionType, address: String,
            pinCode: String, city: String, state: String, country: String,
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
                logoAssetId = logoAssetId, bankName = bankName,
                bankAccountNumber = bankAccountNumber, bankIfsc = bankIfsc, bankBranch = bankBranch, upiId = upiId,
                qrCodeAssetId = qrCodeAssetId, signatureAssetId = signatureAssetId, termsAndConditions = termsAndConditions,
                updatedAt = updatedAt
            )
        }

        private val individualProfiles = mutableMapOf<String, IndividualProfileEntity>()
        override suspend fun getIndividualProfile(companyId: String) = individualProfiles[companyId]
        override suspend fun insertIndividualProfile(profile: IndividualProfileEntity) { individualProfiles[profile.companyId] = profile }
        override suspend fun updateIndividualProfile(
            companyId: String, individualProfileId: String, name: String, address: String,
            pinCode: String, city: String, state: String, country: String, pan: String,
            phone: String, email: String, signatureAssetId: String?, termsAndConditions: String, updatedAt: Long
        ) {
            val existing = individualProfiles[companyId] ?: return
            if (existing.individualProfileId != individualProfileId) return
            individualProfiles[companyId] = existing.copy(
                name = name, address = address, pinCode = pinCode, city = city, state = state, country = country,
                pan = pan, phone = phone, email = email,
                signatureAssetId = signatureAssetId, termsAndConditions = termsAndConditions, updatedAt = updatedAt
            )
        }
    }

    private val companyId = "COMP_PROFILE_A"
    private val otherCompanyId = "COMP_PROFILE_B"
    private val fyId = "FY_PROFILE_2026_27"

    private fun freshDao() = ProfileAwareDao(FakeAccountingDao())

    private suspend fun AccountingDao.seed(targetCompanyId: String) {
        insertCompany(CompanyEntity(
            companyId = targetCompanyId, name = "Test Co $targetCompanyId", tradeName = "Test Co", gstin = "27AAAAA0000A1Z5",
            pan = "AAAAA0000A", stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
            currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
            accountingMode = AccountingMode.ACCOUNT_ONLY, businessType = BusinessType.TRADING
        ))
        insertFinancialYear(FinancialYearEntity(fyId, targetCompanyId, "FY 2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_$targetCompanyId", targetCompanyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
        insertLedger(LedgerEntity(
            "LED_CASH_$targetCompanyId", targetCompanyId, "${StandardSystemGroups.CASH_GROUP_ID}_$targetCompanyId", "Cash", "",
            0L, com.example.accounting.core.common.DrCr.DEBIT, 0L, com.example.accounting.core.common.DrCr.DEBIT,
            "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0
        ))
    }

    // ==========================================
    // Tenant isolation - explicit TenantMismatchException
    // ==========================================
    @Test
    fun testUpsertBusinessProfile_crossCompanyContextThrowsTenantMismatchException() = runBlocking {
        val dao = freshDao()
        dao.seed(companyId)
        val service = ProfileApplicationService(AccountingRepository(dao))

        val profileForCompanyA = BusinessProfile(businessProfileId = "", companyId = companyId, businessName = "Apex Traders")
        try {
            service.upsertBusinessProfile(otherCompanyId, profileForCompanyA)
            fail("Expected TenantMismatchException when contextCompanyId does not match the profile's own companyId")
        } catch (e: TenantMismatchException) {
            assertEquals(otherCompanyId, e.contextCompanyId)
            assertEquals(companyId, e.resourceCompanyId)
        }
    }

    @Test
    fun testUpsertIndividualProfile_crossCompanyContextThrowsTenantMismatchException() = runBlocking {
        val dao = freshDao()
        dao.seed(companyId)
        val service = ProfileApplicationService(AccountingRepository(dao))

        val profileForCompanyA = IndividualProfile(individualProfileId = "", companyId = companyId, name = "Ramesh Kumar")
        try {
            service.upsertIndividualProfile(otherCompanyId, profileForCompanyA)
            fail("Expected TenantMismatchException")
        } catch (e: TenantMismatchException) {
            assertEquals(otherCompanyId, e.contextCompanyId)
            assertEquals(companyId, e.resourceCompanyId)
        }
    }

    @Test
    fun testGetBusinessProfile_matchingContextSucceeds() = runBlocking {
        val dao = freshDao()
        dao.seed(companyId)
        val service = ProfileApplicationService(AccountingRepository(dao))
        service.upsertBusinessProfile(companyId, BusinessProfile(businessProfileId = "", companyId = companyId, businessName = "Apex Traders"))

        val fetched = service.getBusinessProfile(companyId)
        assertEquals("Apex Traders", fetched?.businessName)
    }

    @Test
    fun testGetBusinessProfile_noProfileYetReturnsNullNotException() = runBlocking {
        val dao = freshDao()
        dao.seed(companyId)
        val service = ProfileApplicationService(AccountingRepository(dao))
        assertNull(service.getBusinessProfile(companyId))
    }

    // ==========================================
    // Data masking
    // ==========================================
    @Test
    fun testMaskPan_matchesDocumentedExample() {
        assertEquals("ABCDE****F", SensitiveDataMasker.maskPan("ABCDE1234F"))
    }

    @Test
    fun testMaskGstin_keepsStateCodeAndChecksumVisible() {
        val masked = SensitiveDataMasker.maskGstin("27AAAAA0000A1Z5")
        assertEquals("27AAA********Z5", masked)
        assertFalse("The full unmasked GSTIN must never appear in the masked output", masked.contains("0000A1"))
    }

    @Test
    fun testMaskBankAccountNumber_revealsOnlyLastFourDigits() {
        assertEquals("*******8901", SensitiveDataMasker.maskBankAccountNumber("12345678901"))
        assertEquals("********9012", SensitiveDataMasker.maskBankAccountNumber("123456789012"))
    }

    @Test
    fun testMask_blankInputPassesThroughUnchanged() {
        assertEquals("", SensitiveDataMasker.maskPan(""))
        assertEquals("", SensitiveDataMasker.maskGstin(""))
        assertEquals("", SensitiveDataMasker.maskBankAccountNumber(""))
    }

    @Test
    fun testMask_tooShortToHaveAMeaningfulMiddleIsFullyMasked() {
        assertEquals("***", SensitiveDataMasker.maskPan("ABC"))
        assertEquals("****", SensitiveDataMasker.maskBankAccountNumber("1234"))
    }

    // ==========================================
    // Phase 7G - generic maskSensitiveData(input, visibleSuffixLength)
    // ==========================================
    @Test
    fun testMaskSensitiveData_matchesDocumentedExample() {
        assertEquals("XXXXXXXX1234", SensitiveDataMasker.maskSensitiveData("123456781234", 4))
    }

    @Test
    fun testMaskSensitiveData_zeroVisibleSuffix_masksEntireValue() {
        assertEquals("XXXXXXXXXXXX", SensitiveDataMasker.maskSensitiveData("123456789012", 0))
    }

    @Test
    fun testMaskSensitiveData_blankInputPassesThroughUnchanged() {
        assertEquals("", SensitiveDataMasker.maskSensitiveData("", 4))
    }

    @Test
    fun testMaskSensitiveData_inputNoLongerThanSuffix_fullyMasked_neverRevealsWholeValue() {
        assertEquals("XXXX", SensitiveDataMasker.maskSensitiveData("1234", 4))
        assertEquals("XXX", SensitiveDataMasker.maskSensitiveData("ABC", 10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testMaskSensitiveData_negativeVisibleSuffixLength_rejected() {
        SensitiveDataMasker.maskSensitiveData("ABCDE1234F", -1)
    }

    // ==========================================
    // Phase 7G - BankUpiProfile (pure domain model, no DAO/ledger access)
    // ==========================================
    @Test
    fun testBankUpiProfile_companyOwnedByDefault_partyIdNullUnlessSet() {
        val profile = BankUpiProfile(
            bankUpiProfileId = "BUP_1", companyId = companyId, bankName = "HDFC Bank",
            accountHolderName = "Acme Corp", accountNumber = "123456789012", ifscCode = "HDFC0000123"
        )
        assertNull("A profile not explicitly linked to a Party belongs to the company itself", profile.partyId)

        val partyProfile = profile.copy(bankUpiProfileId = "BUP_2", partyId = "PARTY_1")
        assertEquals("PARTY_1", partyProfile.partyId)
    }

    @Test
    fun testBankUpiProfile_sensitiveFieldsAreMaskedForDisplay_neverStoredMasked() {
        val profile = BankUpiProfile(
            bankUpiProfileId = "BUP_1", companyId = companyId, bankName = "HDFC Bank",
            accountNumber = "123456781234", ifscCode = "HDFC0000123",
            upi = UpiMetadata(upiId = "acme@hdfcbank", isVerified = true)
        )
        // The stored value stays exact - masking is a presentation concern only.
        assertEquals("123456781234", profile.accountNumber)

        val maskedForDisplay = SensitiveDataMasker.maskSensitiveData(profile.accountNumber, visibleSuffixLength = 4)
        assertEquals("XXXXXXXX1234", maskedForDisplay)
        assertFalse(maskedForDisplay.contains("12345678"))
    }

    @Test
    fun testMaskedSummary_neverContainsRawPanGstinOrBankAccountNumber() = runBlocking {
        val dao = freshDao()
        dao.seed(companyId)
        val service = ProfileApplicationService(AccountingRepository(dao))
        service.upsertBusinessProfile(
            companyId,
            BusinessProfile(
                businessProfileId = "", companyId = companyId, businessName = "Apex Traders",
                gstin = "27AAAAA0000A1Z5", pan = "AAAAA0000A", bankAccountNumber = "998877665544"
            )
        )

        val summary = service.getBusinessProfileSummary(companyId)!!
        assertFalse(summary.gstinMasked.contains("AAAAA0000A"))
        assertFalse(summary.panMasked.contains("AAAA0000"))
        assertFalse(summary.bankAccountNumberMasked.contains("998877"))
        assertEquals("********5544", summary.bankAccountNumberMasked)
    }

    @Test
    fun testToLogSafeString_neverContainsRawSensitiveValues() = runBlocking {
        val dao = freshDao()
        dao.seed(companyId)
        val service = ProfileApplicationService(AccountingRepository(dao))
        service.upsertBusinessProfile(
            companyId,
            BusinessProfile(
                businessProfileId = "", companyId = companyId, businessName = "Apex Traders",
                gstin = "27AAAAA0000A1Z5", pan = "AAAAA0000A", bankAccountNumber = "998877665544"
            )
        )
        val profile = service.getBusinessProfile(companyId)!!
        val logLine = profile.toLogSafeString()
        assertFalse(logLine.contains("27AAAAA0000A1Z5"))
        assertFalse(logLine.contains("AAAAA0000A"))
        assertFalse(logLine.contains("998877665544"))
        assertTrue(logLine.contains("Apex Traders"))
    }

    // ==========================================
    // Zero direct ledger changes from profile mutations
    // ==========================================
    @Test
    fun testProfileMutations_causeExactlyZeroLedgerChanges() = runBlocking {
        val dao = freshDao()
        dao.seed(companyId)
        val service = ProfileApplicationService(AccountingRepository(dao))

        val ledgersBefore = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }

        service.upsertBusinessProfile(companyId, BusinessProfile(businessProfileId = "", companyId = companyId, businessName = "Apex Traders"))
        service.upsertBusinessProfile(companyId, BusinessProfile(businessProfileId = "", companyId = companyId, businessName = "Apex Traders Pvt Ltd", pan = "AAAAA0000A"))
        service.upsertIndividualProfile(companyId, IndividualProfile(individualProfileId = "", companyId = companyId, name = "Ramesh Kumar"))
        service.upsertIndividualProfile(companyId, IndividualProfile(individualProfileId = "", companyId = companyId, name = "Ramesh Kumar", pan = "BBBBB1111B"))

        val ledgersAfter = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        assertEquals("Profile mutations must not create/delete any ledger row", ledgersBefore.keys, ledgersAfter.keys)
        for ((id, before) in ledgersBefore) {
            assertEquals("Profile mutations must cause exactly zero ledger balance changes", before.currentBalancePaise, ledgersAfter.getValue(id).currentBalancePaise)
            assertEquals(before, ledgersAfter.getValue(id))
        }
    }

    @Test
    fun testUpsertBusinessProfile_isCompanyScoped_secondCompanyUnaffected() = runBlocking {
        val dao = freshDao()
        dao.seed(companyId)
        dao.seed(otherCompanyId)
        val service = ProfileApplicationService(AccountingRepository(dao))

        service.upsertBusinessProfile(companyId, BusinessProfile(businessProfileId = "", companyId = companyId, businessName = "Apex Traders"))

        assertNull("Company B must never see Company A's profile", service.getBusinessProfile(otherCompanyId))
        val resultA = service.getBusinessProfile(companyId)
        assertEquals("Apex Traders", resultA?.businessName)
    }
}
