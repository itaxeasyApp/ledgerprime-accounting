package com.example.accounting

import com.example.accounting.Phase7JBFixtures.seedCompanyAndFy
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.StandardSystemGroups
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening-balance edit (Phase 8, L4). With posted journal entries the opening balance is frozen: a request that
 * CHANGES the opening amount (or the side of a non-zero opening) is rejected explicitly and nothing is saved; a
 * request that carries the stored opening is a normal edit. Without entries the opening balance stays editable.
 */
class LedgerOpeningBalanceEditTest {

    private val companyId = Phase7JBFixtures.COMPANY_ID
    private val groupId = "${StandardSystemGroups.INDIRECT_EXPENSE_GROUP_ID}_$companyId"

    private fun freshDao() = Phase7JBAwareDao(Phase7BTestSuite.Phase7BAwareDao(FakeAccountingDao()))

    private fun persisted(opening: Long, type: DrCr) = LedgerEntity(
        ledgerId = "LED_OB", companyId = companyId, groupId = groupId, name = "Original", code = "C-1",
        openingBalancePaise = opening, openingBalanceType = type, currentBalancePaise = opening + 250_00L, currentBalanceType = type,
        gstin = "", pan = "", stateCode = "27", email = "old@example.com", phone = "9000000001", address = "Old address",
        bankAccountNumber = "", bankIfsc = "", isSystem = false, isActive = true, hsnSacCode = "", defaultTaxRate = 0.0,
        gstRegistrationStatus = "REGISTERED", pinCode = "400001", bankName = "", bankBranch = ""
    )

    /** A dialog-style edit with a new name/phone and the given opening values. */
    private fun edit(base: LedgerEntity, opening: Long, type: DrCr) = Ledger(
        ledgerId = base.ledgerId, companyId = companyId, groupId = base.groupId, name = "Edited", openingBalance = Money.fromPaise(opening),
        openingBalanceType = type, stateCode = base.stateCode, phone = "9999999999", email = base.email, address = base.address, pinCode = base.pinCode
    )

    private class Posted(private val delegate: AccountingDao) : AccountingDao by delegate {
        override suspend fun countJournalEntriesForLedger(companyId: String, ledgerId: String): Int = 4
    }

    private data class Env(val base: AccountingDao, val repo: AccountingRepository)

    private suspend fun env(opening: Long, type: DrCr, posted: Boolean): Pair<Env, LedgerEntity> {
        val base = freshDao(); base.seedCompanyAndFy()
        val l = persisted(opening, type); base.insertLedger(l)
        return Env(base, AccountingRepository(if (posted) Posted(base) else base, db = null)) to l
    }

    private suspend fun Env.stored() = base.getLedgerById(companyId, "LED_OB")!!

    @Test fun withEntries_changingTheOpeningAmount_isRejectedExplicitly_andNothingIsSaved() = runBlocking {
        val (e, l) = env(5_000_00L, DrCr.DEBIT, posted = true)
        val r = e.repo.updateLedger(edit(l, 99_999_00L, DrCr.DEBIT))
        assertTrue(r is AccountingResult.Failure)
        assertTrue("an explicit business-rule failure, not a silent drop", (r as AccountingResult.Failure).error is AppError.BusinessRuleViolation)
        assertTrue(r.error.message.contains("opening balance"))
        assertEquals("the WHOLE ledger (name, phone too) must be unchanged", l, e.stored())
    }

    @Test fun withEntries_changingTheSideOfANonZeroOpening_isRejected_andNothingIsSaved() = runBlocking {
        val (e, l) = env(5_000_00L, DrCr.DEBIT, posted = true)
        val r = e.repo.updateLedger(edit(l, 5_000_00L, DrCr.CREDIT))
        assertTrue(r is AccountingResult.Failure)
        assertEquals(l, e.stored())
    }

    @Test fun withEntries_changingBothAmountAndSide_isRejected_andNothingIsSaved() = runBlocking {
        val (e, l) = env(5_000_00L, DrCr.CREDIT, posted = true)
        assertTrue(e.repo.updateLedger(edit(l, 1L, DrCr.DEBIT)) is AccountingResult.Failure)
        assertEquals(l, e.stored())
    }

    @Test fun withEntries_anUnchangedOpening_plusOtherEdits_succeeds_andTheOtherEditsAreSaved() = runBlocking {
        val (e, l) = env(5_000_00L, DrCr.CREDIT, posted = true)
        val r = e.repo.updateLedger(edit(l, 5_000_00L, DrCr.CREDIT))
        assertTrue(r is AccountingResult.Success)
        val after = e.stored()
        assertEquals("Edited", after.name); assertEquals("9999999999", after.phone)
        assertEquals(5_000_00L, after.openingBalancePaise); assertEquals(DrCr.CREDIT, after.openingBalanceType)
        assertEquals("the running balance is untouched", l.currentBalancePaise, after.currentBalancePaise)
        assertEquals(l.currentBalanceType, after.currentBalanceType)
    }

    @Test fun withEntries_aZeroOpening_theSideIsIrrelevant_soASideOnlyDifferenceIsNotAChange() = runBlocking {
        val (e, l) = env(0L, DrCr.DEBIT, posted = true)
        val r = e.repo.updateLedger(edit(l, 0L, DrCr.CREDIT))
        assertTrue("zero amount: the side carries no meaning, so this is not an opening-balance change", r is AccountingResult.Success)
        assertEquals("Edited", e.stored().name)
        assertEquals("a zero opening keeps its stored side", DrCr.DEBIT, e.stored().openingBalanceType)
    }

    @Test fun withEntries_aZeroOpening_butANonZeroRequestedAmount_isRejected() = runBlocking {
        val (e, l) = env(0L, DrCr.DEBIT, posted = true)
        assertTrue(e.repo.updateLedger(edit(l, 100_00L, DrCr.DEBIT)) is AccountingResult.Failure)
        assertEquals(l, e.stored())
    }

    @Test fun withoutEntries_theOpeningBalanceStaysEditable_andTheCurrentBalanceTracksIt() = runBlocking {
        val (e, l) = env(5_000_00L, DrCr.DEBIT, posted = false)
        val r = e.repo.updateLedger(edit(l, 7_000_00L, DrCr.CREDIT))
        assertTrue(r is AccountingResult.Success)
        val after = e.stored()
        assertEquals(7_000_00L, after.openingBalancePaise); assertEquals(DrCr.CREDIT, after.openingBalanceType)
        assertEquals(7_000_00L, after.currentBalancePaise); assertEquals(DrCr.CREDIT, after.currentBalanceType)
        assertEquals("Edited", after.name)
    }

    @Test fun theRejectionComesBeforeAnyWrite_evenWhenOtherFieldsAreAlsoChanged() = runBlocking {
        val (e, l) = env(5_000_00L, DrCr.DEBIT, posted = true)
        val r = e.repo.updateLedger(
            edit(l, 6_000_00L, DrCr.DEBIT).copy(gstin = "27AAPFU0939F1ZV", pan = "AAPFU0939F", bankName = "New Bank", bankAccountNumber = "999", address = "New address")
        )
        assertTrue(r is AccountingResult.Failure)
        assertEquals(l, e.stored())
    }
}
