package com.example.accounting

import com.example.accounting.Phase7JBFixtures.seedCompanyAndFy
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.accounting.StandardSystemGroups
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ledger group change (Phase 8, L3). Reports resolve a ledger's CURRENT group at report time, so a ledger
 * with posted journal history may only move to a classification-neutral group: same Primary Group and same
 * nearest System Group anchor. Every rejection must leave the whole ledger unchanged.
 */
class LedgerGroupChangeTest {

    private val companyId = Phase7JBFixtures.COMPANY_ID
    private fun gid(bare: String) = "${bare}_$companyId"

    private val indirectExp = gid(StandardSystemGroups.INDIRECT_EXPENSE_GROUP_ID)
    private val directExp = gid(StandardSystemGroups.DIRECT_EXPENSE_GROUP_ID)
    private val bank = gid(StandardSystemGroups.BANK_GROUP_ID)
    private val cash = gid(StandardSystemGroups.CASH_GROUP_ID)
    private val currentAssets = gid(StandardSystemGroups.CURRENT_ASSETS_GROUP_ID)
    private val fixedAssets = gid(StandardSystemGroups.FIXED_ASSETS_GROUP_ID)
    private val debtors = gid(StandardSystemGroups.DEBTORS_GROUP_ID)
    private val creditors = gid(StandardSystemGroups.CREDITORS_GROUP_ID)
    private val sales = gid(StandardSystemGroups.SALES_GROUP_ID)
    private val indirectIncome = gid(StandardSystemGroups.INDIRECT_INCOME_GROUP_ID)
    private val suspense = "${StandardSystemGroups.SUSPENSE_GROUP_ID}_$companyId"
    private val roundOff = "${StandardSystemGroups.ROUND_OFF_GROUP_ID}_$companyId"

    // user groups (isSystem = false) - each inherits its parent's primary group, as CreateGroup does
    private val ugExpA = "UG_EXP_A"; private val ugExpB = "UG_EXP_B"; private val ugExpA1 = "UG_EXP_A1"
    private val ugBank = "UG_BANK"; private val ugCurrentAssets = "UG_CA"; private val ugDebtors = "UG_DEBTORS"

    private fun freshDao() = Phase7JBAwareDao(Phase7BTestSuite.Phase7BAwareDao(FakeAccountingDao()))

    private fun userGroup(id: String, parent: String, primary: PrimaryGroup, company: String = companyId) =
        GroupEntity(id, company, id, primary, parent, false, false, 500)

    private suspend fun seed(dao: AccountingDao) {
        dao.seedCompanyAndFy()
        dao.insertGroups(StandardSystemGroups.getStandardGroupsForCompany(companyId).map {
            GroupEntity(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        })
        dao.insertGroups(listOf(
            userGroup(ugExpA, indirectExp, PrimaryGroup.EXPENSES), userGroup(ugExpB, indirectExp, PrimaryGroup.EXPENSES),
            userGroup(ugExpA1, ugExpA, PrimaryGroup.EXPENSES), userGroup(ugBank, bank, PrimaryGroup.ASSETS),
            userGroup(ugCurrentAssets, currentAssets, PrimaryGroup.ASSETS), userGroup(ugDebtors, debtors, PrimaryGroup.ASSETS)
        ))
    }

    private fun ledgerIn(groupId: String, id: String = "LED_L3", opening: Long = 0L) = LedgerEntity(
        ledgerId = id, companyId = companyId, groupId = groupId, name = "Original Name", code = "K-1",
        openingBalancePaise = opening, openingBalanceType = DrCr.DEBIT, currentBalancePaise = opening, currentBalanceType = DrCr.DEBIT,
        gstin = "", pan = "", stateCode = "27", email = "old@example.com", phone = "9000000001", address = "Old address",
        bankAccountNumber = "", bankIfsc = "", isSystem = false, isActive = true, hsnSacCode = "", defaultTaxRate = 0.0,
        gstRegistrationStatus = "REGISTERED", pinCode = "400001", bankName = "", bankBranch = ""
    )

    /** A dialog-style edit: a NEW name and phone, plus the requested group. */
    private fun edit(base: LedgerEntity, toGroup: String) = Ledger(
        ledgerId = base.ledgerId, companyId = companyId, groupId = toGroup, name = "Edited Name",
        openingBalance = Money.fromPaise(base.openingBalancePaise), openingBalanceType = base.openingBalanceType,
        stateCode = base.stateCode, phone = "9999999999", email = base.email, address = base.address, pinCode = base.pinCode
    )

    private class History(private val delegate: AccountingDao, private val count: Int) : AccountingDao by delegate {
        override suspend fun countJournalEntriesForLedger(companyId: String, ledgerId: String): Int = count
    }

    private data class Env(val base: AccountingDao, val repo: AccountingRepository)

    private suspend fun env(ledger: LedgerEntity, posted: Boolean, prepare: suspend (AccountingDao) -> Unit = {}): Env {
        val base = freshDao(); seed(base); prepare(base); base.insertLedger(ledger)
        return Env(base, AccountingRepository(if (posted) History(base, 3) else base, db = null))
    }

    private suspend fun Env.move(ledger: LedgerEntity, to: String) = repo.updateLedger(edit(ledger, to))
    private suspend fun Env.stored(id: String = "LED_L3") = base.getLedgerById(companyId, id)!!

    private suspend fun assertAllowed(from: String, to: String, posted: Boolean = true, opening: Long = 0L) {
        val l = ledgerIn(from, opening = opening); val e = env(l, posted)
        val r = e.move(l, to)
        assertTrue("$from -> $to should be allowed (posted=$posted): $r", r is AccountingResult.Success)
        val after = e.stored()
        assertEquals(to, after.groupId); assertEquals("Edited Name", after.name); assertEquals("9999999999", after.phone)
    }

    private suspend fun assertRejected(from: String, to: String, posted: Boolean = true, prepare: suspend (AccountingDao) -> Unit = {}) {
        val l = ledgerIn(from); val e = env(l, posted, prepare)
        val r = e.move(l, to)
        assertTrue("$from -> $to must be rejected: $r", r is AccountingResult.Failure)
        assertEquals("a rejected update must leave the WHOLE ledger unchanged (name/phone were also edited)", l, e.stored())
    }

    // 1 -------------------------------------------------------------------------------------------
    @Test fun sameGroupSucceeds_withPostedHistory() = runBlocking { assertAllowed(indirectExp, indirectExp) }

    // 2 -------------------------------------------------------------------------------------------
    @Test fun postedLedger_toAUserGroupUnderTheSameSystemGroup_succeeds_andBackToTheSystemGroup() = runBlocking {
        assertAllowed(indirectExp, ugExpA)
        assertAllowed(ugExpA, indirectExp)
        assertAllowed(bank, ugBank)               // Bank Accounts -> a user group under Bank Accounts (same anchor)
        assertAllowed(ugBank, bank)
        assertAllowed(debtors, ugDebtors)
    }

    // 3 -------------------------------------------------------------------------------------------
    @Test fun postedLedger_toAnotherUserGroupUnderTheSameSystemGroup_succeeds_includingNestedOnes() = runBlocking {
        assertAllowed(ugExpA, ugExpB)
        assertAllowed(ugExpB, ugExpA1)            // nested user group under another user group, same system ancestor
        assertAllowed(ugExpA1, ugExpA)
    }

    // 4 -------------------------------------------------------------------------------------------
    @Test fun postedLedger_toADifferentSystemGroup_isRejected() = runBlocking {
        assertRejected(indirectExp, directExp)    // same primary group, other system group (Gross Profit split)
        assertRejected(ugExpA, directExp)
        assertRejected(debtors, fixedAssets)
        assertRejected(sales, indirectIncome)
        assertRejected(ugDebtors, ugCurrentAssets)
    }

    // 5 -------------------------------------------------------------------------------------------
    @Test fun postedLedger_toADifferentPrimaryGroup_isRejected() = runBlocking {
        assertRejected(indirectExp, fixedAssets)  // Expenses -> Assets
        assertRejected(debtors, creditors)        // Assets -> Liabilities
        assertRejected(sales, indirectExp)        // Income -> Expenses
        assertRejected(indirectExp, ugCurrentAssets)
    }

    @Test fun aUserGroupThatClaimsAnotherPrimaryGroupThanItsParent_isStillRejected() = runBlocking {
        // the primary group is compared on the groups themselves, so an inconsistent user group cannot be a back door
        assertRejected(indirectExp, "UG_BAD") { it.insertGroup(userGroup("UG_BAD", indirectExp, PrimaryGroup.ASSETS)) }
    }

    // 6 -------------------------------------------------------------------------------------------
    @Test fun postedLedger_whoseBankCashClassificationWouldChange_isRejected() = runBlocking {
        assertRejected(bank, cash)
        assertRejected(cash, bank)
        assertRejected(bank, currentAssets)
        assertRejected(bank, ugCurrentAssets)
        assertRejected(ugBank, ugCurrentAssets)
        assertRejected(currentAssets, bank)
        assertRejected(debtors, bank)
    }

    // 7 -------------------------------------------------------------------------------------------
    @Test fun postedLedger_intoOrOutOfSpecialControl_isRejected() = runBlocking {
        assertRejected(indirectExp, suspense)
        assertRejected(indirectExp, roundOff)
        assertRejected(bank, suspense)
        // a user ledger that sits in a control group cannot leave it, nor hop to the other control group
        assertRejected(roundOff, indirectExp)
        assertRejected(roundOff, suspense)
    }

    // 8 -------------------------------------------------------------------------------------------
    @Test fun withoutPostedHistory_anyValidGroupChangeStillWorks_asBefore_evenWithAnOpeningBalance() = runBlocking {
        assertAllowed(indirectExp, directExp, posted = false)
        assertAllowed(indirectExp, fixedAssets, posted = false)
        assertAllowed(debtors, bank, posted = false, opening = 7_500_00L)
        assertAllowed(bank, cash, posted = false, opening = 7_500_00L)
        // Income/Expense are period accounts and carry no opening balance (P2-3), so this move has none.
        assertAllowed(sales, indirectExp, posted = false)
        assertAllowed(indirectExp, suspense, posted = false)   // control groups are S2, deliberately untouched here
    }

    // 9 -------------------------------------------------------------------------------------------
    @Test fun aMissingGroupIsRejected_withAndWithoutHistory() = runBlocking {
        assertRejected(indirectExp, "GRP_DOES_NOT_EXIST", posted = true)
        assertRejected(indirectExp, "GRP_DOES_NOT_EXIST", posted = false)
    }

    // 10 ------------------------------------------------------------------------------------------
    @Test fun aGroupOfAnotherCompanyIsRejected_withAndWithoutHistory() = runBlocking {
        val prepare: suspend (AccountingDao) -> Unit = {
            it.insertGroup(GroupEntity("GRP_OTHER_CO", "COMP_OTHER", "Other Co Group", PrimaryGroup.EXPENSES, null, false, false, 1))
            it.insertGroup(GroupEntity(gid("GRP_FOREIGN_SYSTEM"), "COMP_OTHER", "Foreign", PrimaryGroup.EXPENSES, null, true, false, 1))
        }
        assertRejected(indirectExp, "GRP_OTHER_CO", posted = true, prepare = prepare)
        assertRejected(indirectExp, "GRP_OTHER_CO", posted = false, prepare = prepare)
    }

    // 11 ------------------------------------------------------------------------------------------
    @Test fun aRejectedUpdate_doesNotPartiallySaveOpeningBalanceGstOrBankFields() = runBlocking {
        val l = ledgerIn(bank, opening = 3_000_00L); val e = env(l, posted = true)
        val r = e.repo.updateLedger(
            edit(l, fixedAssets).copy(
                gstin = "27AAPFU0939F1ZV", pan = "AAPFU0939F", openingBalance = Money.fromPaise(99_999_00L), openingBalanceType = DrCr.CREDIT,
                bankName = "New Bank", bankAccountNumber = "999", bankIfsc = "NEW0000001", bankBranch = "New Branch", address = "New address"
            )
        )
        assertTrue(r is AccountingResult.Failure)
        assertTrue((r as AccountingResult.Failure).error is AppError.BusinessRuleViolation)
        assertEquals(l, e.stored())
    }

    @Test fun aBrokenOrCyclicSystemAnchor_withHistory_isRejected_withoutLooping() = runBlocking {
        // two user groups that are each other's parent: no system ancestor can be found
        assertRejected(indirectExp, "UG_CYC_A") {
            it.insertGroup(userGroup("UG_CYC_A", "UG_CYC_B", PrimaryGroup.EXPENSES)); it.insertGroup(userGroup("UG_CYC_B", "UG_CYC_A", PrimaryGroup.EXPENSES))
        }
    }

    @Test fun aLedgerWhoseCurrentGroupIsMissing_withHistory_cannotBeProvenNeutral_andIsRejected() = runBlocking {
        val l = ledgerIn("GRP_VANISHED"); val e = env(l, posted = true)
        assertTrue(e.move(l, indirectExp) is AccountingResult.Failure)
        assertEquals(l, e.stored())
    }

    @Test fun anEditThatKeepsTheGroup_isUnaffected_evenWithHistoryAndAMissingOldGroup() = runBlocking {
        val l = ledgerIn("GRP_VANISHED"); val e = env(l, posted = true)
        val r = e.move(l, "GRP_VANISHED")
        assertTrue(r is AccountingResult.Success)
        assertEquals("Edited Name", e.stored().name)
    }
}
