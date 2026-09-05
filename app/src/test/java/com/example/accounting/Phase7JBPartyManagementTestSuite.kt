package com.example.accounting

import com.example.accounting.Phase7JBFixtures.seedCompanyAndFy
import com.example.accounting.application.inventory.StockItemManagementService
import com.example.accounting.application.ledger.LedgerManagementService
import com.example.accounting.application.party.PartyManagementService
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.inventory.StockItem
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyEntityType
import com.example.accounting.domain.party.PartyRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7J-B - [PartyManagementService], [LedgerManagementService], [StockItemManagementService]
 * ("Party/Ledger/Item" scope) - all three are thin, pure-delegation facades over the existing,
 * unmodified [AccountingRepository] functions; these tests prove the delegation is exact (same row
 * created/returned as calling the repository directly), not new capability.
 */
class Phase7JBPartyManagementTestSuite {

    private val companyId = Phase7JBFixtures.COMPANY_ID

    private fun freshDao() = Phase7JBAwareDao(Phase7BTestSuite.Phase7BAwareDao(FakeAccountingDao()))

    @Test
    fun testPartyManagementService_createParty_createsUnderlyingLedgerToo() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)
        val service = PartyManagementService(repository)

        val result = service.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Traders")
        )
        val party = (result as AccountingResult.Success).data
        assertTrue(party.ledgerId.isNotBlank())

        val allParties = service.getParties(companyId, PartyRole.CUSTOMER).first()
        assertEquals(1, allParties.size)
        assertEquals("Acme Traders", allParties.first().displayName)
    }

    // Architecture correction - a Customer/Supplier previously had no way to record a pre-existing
    // balance at all (structurally impossible via CreatePartyDialog); this proves the underlying
    // ledgerTemplate.openingBalance now actually persists onto the created ledger.
    @Test
    fun testCreateParty_WithOpeningBalance_PersistsOntoUnderlyingLedger() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)

        val result = repository.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.SUPPLIER, entityType = PartyEntityType.BUSINESS, displayName = "Existing Supplier"),
            ledgerTemplate = Ledger(
                ledgerId = "", companyId = companyId, groupId = "", name = "Existing Supplier",
                openingBalance = Money.fromPaise(25_000_00L), openingBalanceType = DrCr.CREDIT
            )
        )
        val party = (result as AccountingResult.Success).data

        val ledger = dao.getLedgerById(companyId, party.ledgerId)!!
        assertEquals("Pre-existing balance must persist onto the created ledger", 25_000_00L, ledger.openingBalancePaise)
        assertEquals(DrCr.CREDIT, ledger.openingBalanceType)
        assertEquals(25_000_00L, ledger.currentBalancePaise)
    }

    // 13-point correctness pass, item 5 (Rapid B2C Customer Flow) - quick-creating a second walk-in
    // customer with the same name AND phone reuses the first party/ledger instead of duplicating.
    @Test
    fun testCreateParty_B2cDedup_SameNameAndPhone_ReusesExistingParty() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)

        val firstResult = repository.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.CUSTOMER, entityType = PartyEntityType.INDIVIDUAL, displayName = "Walk-in Buyer"),
            ledgerTemplate = Ledger(ledgerId = "", companyId = companyId, groupId = "", name = "Walk-in Buyer", phone = "9876543210")
        )
        val firstParty = (firstResult as AccountingResult.Success).data

        val secondResult = repository.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.CUSTOMER, entityType = PartyEntityType.INDIVIDUAL, displayName = "walk-in buyer"),
            ledgerTemplate = Ledger(ledgerId = "", companyId = companyId, groupId = "", name = "walk-in buyer", phone = "9876543210")
        )
        val secondParty = (secondResult as AccountingResult.Success).data

        assertEquals("Same name+phone must resolve to the same party, not a duplicate", firstParty.partyId, secondParty.partyId)
        assertEquals(firstParty.ledgerId, secondParty.ledgerId)
        val allCustomers = repository.getParties(companyId, PartyRole.CUSTOMER).first()
        assertEquals(1, allCustomers.size)
    }

    // A different phone (or a blank one) must never be silently merged into an existing party.
    @Test
    fun testCreateParty_B2cDedup_DifferentPhone_CreatesSeparateParty() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)

        repository.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.CUSTOMER, entityType = PartyEntityType.INDIVIDUAL, displayName = "Walk-in Buyer"),
            ledgerTemplate = Ledger(ledgerId = "", companyId = companyId, groupId = "", name = "Walk-in Buyer", phone = "9876543210")
        )
        repository.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.CUSTOMER, entityType = PartyEntityType.INDIVIDUAL, displayName = "Walk-in Buyer"),
            ledgerTemplate = Ledger(ledgerId = "", companyId = companyId, groupId = "", name = "Walk-in Buyer", phone = "9123456780")
        )

        val allCustomers = repository.getParties(companyId, PartyRole.CUSTOMER).first()
        assertEquals(2, allCustomers.size)
    }

    @Test
    fun testLedgerManagementService_createAndList_andDeleteSafely() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)
        val service = LedgerManagementService(repository)

        val createResult = service.createLedger(
            Ledger(ledgerId = "", companyId = companyId, groupId = "${StandardSystemGroups.INDIRECT_EXPENSE_GROUP_ID}_$companyId", name = "Office Supplies")
        )
        val ledger = (createResult as AccountingResult.Success).data
        assertTrue(ledger.ledgerId.isNotBlank())

        val allLedgers = service.getLedgers(companyId).first()
        assertTrue(allLedgers.any { it.ledgerId == ledger.ledgerId })

        val deleteResult = service.deleteLedgerSafely(companyId, ledger.ledgerId)
        assertTrue("A never-posted-to ledger must be safely deletable", deleteResult is AccountingResult.Success)
    }

    // 13-point correctness pass, item 8 (Editable Ledgers) - Opening Balance (and Current Balance,
    // which is never caller-settable at all) must survive an edit attempt once the ledger has a
    // posted journal entry, while other fields (name) remain editable. `FakeAccountingDao.countJournalEntriesForLedger`
    // is hardcoded to always return 0 (used by many other tests that don't care about this guard),
    // so this test wraps it with a minimal interface-delegated override that reports a non-zero
    // count instead of actually posting a voucher through the full engine - the guard only reads
    // this one count, so this exercises exactly what `updateLedger` branches on.
    private class EntryCountOverrideDao(
        private val delegate: com.example.accounting.data.local.dao.AccountingDao,
        private val fixedCount: Int
    ) : com.example.accounting.data.local.dao.AccountingDao by delegate {
        override suspend fun countJournalEntriesForLedger(companyId: String, ledgerId: String): Int = fixedCount
    }

    @Test
    fun testUpdateLedger_OpeningBalanceFrozen_OnceEntriesExist_OtherFieldsStillEditable() = runBlocking {
        val baseDao = freshDao()
        baseDao.seedCompanyAndFy()
        val setupRepository = AccountingRepository(baseDao, db = null)

        val created = (setupRepository.createLedger(
            Ledger(
                ledgerId = "", companyId = companyId,
                groupId = "${StandardSystemGroups.INDIRECT_EXPENSE_GROUP_ID}_$companyId",
                name = "Office Supplies", openingBalance = Money.fromPaise(5000_00L), openingBalanceType = DrCr.DEBIT
            )
        ) as AccountingResult.Success).data

        val dao = EntryCountOverrideDao(baseDao, fixedCount = 1)
        val repository = AccountingRepository(dao, db = null)
        val updateResult = repository.updateLedger(
            created.copy(name = "Office Supplies (Renamed)", openingBalance = Money.fromPaise(99_999_00L), openingBalanceType = DrCr.CREDIT)
        )
        val updated = (updateResult as AccountingResult.Success).data

        assertEquals("Name must still be editable after entries exist", "Office Supplies (Renamed)", updated.name)
        assertEquals("Opening balance must be silently preserved once entries exist", 5000_00L, updated.openingBalance.paise)
        assertEquals(DrCr.DEBIT, updated.openingBalanceType)
    }

    @Test
    fun testStockItemManagementService_createAndList() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)
        val service = StockItemManagementService(repository)

        val createResult = service.createStockItem(StockItem(itemId = "", companyId = companyId, name = "Widget", sku = "SKU-1"))
        val item = (createResult as AccountingResult.Success).data
        assertTrue(item.itemId.isNotBlank())

        val allItems = service.getStockItems(companyId).first()
        assertEquals(1, allItems.size)
        assertEquals("Widget", allItems.first().name)
    }
}
