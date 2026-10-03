package com.example.accounting

import com.example.accounting.Phase7JBFixtures.seedCompanyAndFy
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.GstRegistrationStatus
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.StandardSystemGroups
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ledger-edit preservation (Phase 8, L1/L2). The edit path rebuilds a [Ledger] from only the fields the
 * Create/Edit Ledger dialog collects, so every other attribute reaches [AccountingRepository.updateLedger]
 * as a constructor default. The update must keep the EXISTING persisted `code`, `isSystem`, `isActive`,
 * `gstRegistrationStatus` and `defaultTaxRate`, while the fields the user actually edited are saved.
 */
class LedgerEditPreservationTest {

    private val companyId = Phase7JBFixtures.COMPANY_ID
    private val groupId = "${StandardSystemGroups.INDIRECT_EXPENSE_GROUP_ID}_$companyId"

    private fun freshDao() = Phase7JBAwareDao(Phase7BTestSuite.Phase7BAwareDao(FakeAccountingDao()))

    private fun persisted(
        id: String = "LED_E1", name: String = "Edit Me", isSystem: Boolean = false, isActive: Boolean = true,
        gst: String? = "REGISTERED", taxRate: Double = 18.0, code: String = "C-77"
    ) = LedgerEntity(
        ledgerId = id, companyId = companyId, groupId = groupId, name = name, code = code,
        openingBalancePaise = 5_000_00L, openingBalanceType = DrCr.DEBIT, currentBalancePaise = 5_000_00L, currentBalanceType = DrCr.DEBIT,
        gstin = "27AAPFU0939F1ZV", pan = "AAPFU0939F", stateCode = "27", email = "old@example.com", phone = "9000000001",
        address = "Old address", bankAccountNumber = "111", bankIfsc = "OLD0000001", isSystem = isSystem, isActive = isActive,
        hsnSacCode = "9983", defaultTaxRate = taxRate, gstRegistrationStatus = gst, pinCode = "400001", bankName = "Old Bank", bankBranch = "Old Branch"
    )

    /** Exactly what AccountingViewModel.updateLedger builds: only the dialog's fields, defaults for everything else. */
    private fun dialogEdit(
        base: LedgerEntity, name: String = base.name, phone: String = base.phone, email: String = base.email,
        address: String = base.address, gstin: String = base.gstin, bankName: String = base.bankName
    ) = Ledger(
        ledgerId = base.ledgerId, companyId = companyId, groupId = base.groupId, name = name,
        openingBalance = Money.fromPaise(base.openingBalancePaise), openingBalanceType = base.openingBalanceType,
        gstin = gstin, pan = base.pan, stateCode = base.stateCode, phone = phone, email = email, address = address,
        pinCode = base.pinCode, hsnSacCode = base.hsnSacCode, defaultTaxRate = 0.0,
        bankName = bankName, bankAccountNumber = base.bankAccountNumber, bankIfsc = base.bankIfsc, bankBranch = base.bankBranch
    )

    private suspend fun setup(entity: LedgerEntity): Pair<AccountingDao, AccountingRepository> {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        dao.insertLedger(entity)
        return dao to AccountingRepository(dao, db = null)
    }

    @Test fun editingPreservesIsSystem_andTheLedgerStaysProtectedFromDeletion() = runBlocking {
        val (dao, repo) = setup(persisted(id = "LED_SYSTEMISH", isSystem = true))
        val r = repo.updateLedger(dialogEdit(dao.getLedgerById(companyId, "LED_SYSTEMISH")!!, phone = "9111111111"))
        assertTrue(r is AccountingResult.Success)
        assertTrue("isSystem must survive the edit", dao.getLedgerById(companyId, "LED_SYSTEMISH")!!.isSystem)
        assertTrue("the ledger must still be undeletable", repo.deleteLedgerSafely(companyId, "LED_SYSTEMISH") is AccountingResult.Failure)
        assertNotNull(dao.getLedgerById(companyId, "LED_SYSTEMISH"))
    }

    @Test fun editingAnOrdinaryLedgerDoesNotTurnItIntoASystemLedger() = runBlocking {
        val (dao, repo) = setup(persisted(isSystem = false))
        repo.updateLedger(dialogEdit(dao.getLedgerById(companyId, "LED_E1")!!, phone = "9222222222"))
        assertFalse(dao.getLedgerById(companyId, "LED_E1")!!.isSystem)
    }

    @Test fun editingPreservesIsActive_false() = runBlocking {
        val (dao, repo) = setup(persisted(isActive = false))
        repo.updateLedger(dialogEdit(dao.getLedgerById(companyId, "LED_E1")!!, phone = "9333333333"))
        assertFalse("an inactive ledger must not be silently reactivated", dao.getLedgerById(companyId, "LED_E1")!!.isActive)
    }

    @Test fun editingPreservesIsActive_true() = runBlocking {
        val (dao, repo) = setup(persisted(isActive = true))
        repo.updateLedger(dialogEdit(dao.getLedgerById(companyId, "LED_E1")!!, phone = "9444444444"))
        assertTrue(dao.getLedgerById(companyId, "LED_E1")!!.isActive)
    }

    @Test fun editingPreservesGstRegistrationStatus_registered_unregistered_andUnknown() = runBlocking {
        listOf("REGISTERED", "UNREGISTERED", null).forEachIndexed { i, status ->
            val id = "LED_G$i"
            val (dao, repo) = setup(persisted(id = id, gst = status))
            val r = repo.updateLedger(dialogEdit(dao.getLedgerById(companyId, id)!!, phone = "955555555$i"))
            assertEquals("status $status", status, dao.getLedgerById(companyId, id)!!.gstRegistrationStatus)
            val expected = status?.let { GstRegistrationStatus.valueOf(it) }
            assertEquals("the returned ledger reports what was persisted", expected, (r as AccountingResult.Success).data.gstRegistrationStatus)
        }
    }

    @Test fun editingPreservesDefaultTaxRate_andCode() = runBlocking {
        val (dao, repo) = setup(persisted(taxRate = 12.5, code = "ZX-9"))
        val r = repo.updateLedger(dialogEdit(dao.getLedgerById(companyId, "LED_E1")!!, phone = "9666666666"))
        val e = dao.getLedgerById(companyId, "LED_E1")!!
        assertEquals(12.5, e.defaultTaxRate, 0.0)
        assertEquals("ZX-9", e.code)
        assertEquals(12.5, (r as AccountingResult.Success).data.defaultTaxRate, 0.0)
        assertEquals("ZX-9", r.data.code)
    }

    @Test fun intentionallyEditedFieldsAreStillSaved_whileUntouchedFieldsStayIntact() = runBlocking {
        val (dao, repo) = setup(persisted())
        val before = dao.getLedgerById(companyId, "LED_E1")!!
        val r = repo.updateLedger(
            dialogEdit(before, name = "Renamed Ledger", phone = "9777777777", email = "new@example.com", address = "New address", bankName = "New Bank")
        )
        assertTrue(r is AccountingResult.Success)
        val after = dao.getLedgerById(companyId, "LED_E1")!!
        // intentionally changed
        assertEquals("Renamed Ledger", after.name)
        assertEquals("9777777777", after.phone)
        assertEquals("new@example.com", after.email)
        assertEquals("New address", after.address)
        assertEquals("New Bank", after.bankName)
        // preserved, as the dialog never touches them
        assertEquals("C-77", after.code); assertEquals("REGISTERED", after.gstRegistrationStatus)
        assertEquals(18.0, after.defaultTaxRate, 0.0); assertTrue(after.isActive); assertFalse(after.isSystem)
        // and the other persisted fields are exactly what they were
        assertEquals(before.groupId, after.groupId); assertEquals(before.gstin, after.gstin); assertEquals(before.pan, after.pan)
        assertEquals(before.stateCode, after.stateCode); assertEquals(before.pinCode, after.pinCode); assertEquals(before.hsnSacCode, after.hsnSacCode)
        assertEquals(before.bankAccountNumber, after.bankAccountNumber); assertEquals(before.bankIfsc, after.bankIfsc); assertEquals(before.bankBranch, after.bankBranch)
        assertEquals(before.openingBalancePaise, after.openingBalancePaise); assertEquals(before.currentBalancePaise, after.currentBalancePaise)
    }

    @Test fun preservationHolds_whenTheLedgerAlreadyHasPostedEntries() = runBlocking {
        val baseDao = freshDao().also { it.seedCompanyAndFy(); it.insertLedger(persisted(isSystem = true, isActive = false, gst = "UNREGISTERED", taxRate = 5.0)) }
        val dao = object : AccountingDao by baseDao {
            override suspend fun countJournalEntriesForLedger(companyId: String, ledgerId: String): Int = 3
        }
        val repo = AccountingRepository(dao, db = null)
        val r = repo.updateLedger(dialogEdit(baseDao.getLedgerById(companyId, "LED_E1")!!, phone = "9888888888"))
        assertTrue(r is AccountingResult.Success)
        val after = baseDao.getLedgerById(companyId, "LED_E1")!!
        assertEquals("9888888888", after.phone)
        assertTrue(after.isSystem); assertFalse(after.isActive); assertEquals("UNREGISTERED", after.gstRegistrationStatus); assertEquals(5.0, after.defaultTaxRate, 0.0)
        assertEquals("opening balance is still frozen once entries exist", 5_000_00L, after.openingBalancePaise)
    }

    @Test fun anUnknownGstStatusStaysUnknown_neverDefaultedToARegistrationStatus() = runBlocking {
        val (dao, repo) = setup(persisted(gst = null))
        repo.updateLedger(dialogEdit(dao.getLedgerById(companyId, "LED_E1")!!, phone = "9999999999"))
        assertNull(dao.getLedgerById(companyId, "LED_E1")!!.gstRegistrationStatus)
    }

    @Test fun legacyPrimaryBankAccountCanBeRenamedToARealBank_andIsUnlocked() = runBlocking {
        val (dao, repo) = setup(persisted(id = "LED_BANK_$companyId", name = "Primary Bank Account", isSystem = true))
        val r = repo.updateLedger(dialogEdit(dao.getLedgerById(companyId, "LED_BANK_$companyId")!!, name = "SBI Current A/c"))
        assertTrue("the legacy seeded bank ledger must be renamable", r is AccountingResult.Success)
        val saved = dao.getLedgerById(companyId, "LED_BANK_$companyId")!!
        assertEquals("SBI Current A/c", saved.name)
        assertFalse("it becomes an ordinary, independent bank ledger", saved.isSystem)
    }

    @Test fun otherSystemLedgersStillCannotBeRenamed() = runBlocking {
        val (dao, repo) = setup(persisted(id = "LED_CASH_$companyId", name = "Cash in Hand", isSystem = true))
        val r = repo.updateLedger(dialogEdit(dao.getLedgerById(companyId, "LED_CASH_$companyId")!!, name = "Petty Cash"))
        assertTrue(r is AccountingResult.Failure)
        assertEquals("Cash in Hand", dao.getLedgerById(companyId, "LED_CASH_$companyId")!!.name)
    }
}
