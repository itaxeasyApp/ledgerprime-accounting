package com.example.accounting

import com.example.accounting.application.voucher.VoucherDraftStatus
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.BankUpiProfileEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.CompanySubscriptionEntity
import com.example.accounting.data.local.entity.DocumentAssetEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.SettlementAllocationEntity
import com.example.accounting.data.local.entity.StockItemEntity
import com.example.accounting.data.local.entity.VoucherDocumentReferenceEntity
import com.example.accounting.data.local.entity.VoucherDraftEntity
import com.example.accounting.data.local.entity.VoucherDraftLineEntity
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.PeriodStatus
import kotlinx.coroutines.flow.flowOf

/**
 * Shared, non-private fake DAO decorator for every Phase 7J-B pure-JVM test suite (Phase 7J-B's
 * own equivalent of [Phase7BTestSuite.Phase7BAwareDao] / [Phase4TestSuite.InventoryAwareDao] -
 * public so other Phase 7J-B suites can compose with it, exactly the way [Phase7FTestSuite] already
 * reuses [Phase7BTestSuite.Phase7BAwareDao]). Adds real in-memory backing for the five new Phase
 * 7J-B tables plus `account_groups`/`document_assets` (needed by [ReportManagementService] and the
 * Import/QR adapters respectively) - the base [FakeAccountingDao]'s own methods for all of these
 * are permanent no-op stubs, added purely so the interface compiles.
 */
class Phase7JBAwareDao(private val delegate: AccountingDao) : AccountingDao by delegate {
    private val groups = LinkedHashMap<String, GroupEntity>()
    private val documentAssets = mutableListOf<DocumentAssetEntity>()
    private val voucherDrafts = LinkedHashMap<String, VoucherDraftEntity>()
    private val voucherDraftLines = mutableListOf<VoucherDraftLineEntity>()
    private val voucherDocumentReferences = mutableListOf<VoucherDocumentReferenceEntity>()
    private val subscriptions = LinkedHashMap<String, CompanySubscriptionEntity>()
    private val bankUpiProfiles = LinkedHashMap<String, BankUpiProfileEntity>()
    private val settlementAllocations = mutableListOf<SettlementAllocationEntity>()
    private val stockItems = LinkedHashMap<String, StockItemEntity>()

    override fun getStockItemsByCompany(companyId: String) = flowOf(stockItems.values.filter { it.companyId == companyId })
    override suspend fun getStockItemById(companyId: String, itemId: String) = stockItems[itemId]?.takeIf { it.companyId == companyId }
    override suspend fun insertStockItem(stockItem: StockItemEntity) { stockItems[stockItem.itemId] = stockItem }

    override suspend fun getAllocationsForInvoice(invoiceVoucherId: String) = settlementAllocations.filter { it.invoiceVoucherId == invoiceVoucherId }
    override suspend fun getAllocationsForSettlement(settlementVoucherId: String) = settlementAllocations.filter { it.settlementVoucherId == settlementVoucherId }
    override suspend fun insertSettlementAllocations(allocations: List<SettlementAllocationEntity>) { settlementAllocations += allocations }

    override fun getGroupsByCompany(companyId: String) = flowOf(groups.values.filter { it.companyId == companyId })
    override suspend fun getGroupById(companyId: String, groupId: String) = groups[groupId]?.takeIf { it.companyId == companyId }
    override suspend fun insertGroup(group: GroupEntity) { groups[group.groupId] = group }
    override suspend fun insertGroups(newGroups: List<GroupEntity>) { newGroups.forEach { groups[it.groupId] = it } }
    override suspend fun updateGroup(group: GroupEntity) { groups[group.groupId] = group }
    override suspend fun deleteGroup(companyId: String, groupId: String): Int = if (groups.remove(groupId) != null) 1 else 0

    override fun getDocumentAssetsByCompany(companyId: String) = flowOf(documentAssets.filter { it.companyId == companyId })
    override suspend fun getDocumentAssetById(companyId: String, assetId: String) =
        documentAssets.firstOrNull { it.companyId == companyId && it.assetId == assetId }
    override suspend fun insertDocumentAsset(asset: DocumentAssetEntity) { documentAssets += asset }
    override suspend fun deleteDocumentAsset(companyId: String, assetId: String): Int {
        val existing = documentAssets.firstOrNull { it.companyId == companyId && it.assetId == assetId } ?: return 0
        documentAssets.remove(existing)
        return 1
    }

    override suspend fun getVoucherDraftById(companyId: String, draftId: String) =
        voucherDrafts[draftId]?.takeIf { it.companyId == companyId }
    override fun getVoucherDraftsByStatus(companyId: String, status: VoucherDraftStatus) =
        flowOf(voucherDrafts.values.filter { it.companyId == companyId && it.status == status })
    override suspend fun insertVoucherDraft(draft: VoucherDraftEntity) { voucherDrafts[draft.draftId] = draft }
    override suspend fun updateVoucherDraft(draft: VoucherDraftEntity) { voucherDrafts[draft.draftId] = draft }
    override suspend fun getLinesForVoucherDraft(draftId: String) =
        voucherDraftLines.filter { it.draftId == draftId }.sortedBy { it.lineOrder }
    override suspend fun insertVoucherDraftLines(lines: List<VoucherDraftLineEntity>) { voucherDraftLines += lines }
    override suspend fun deleteLinesForVoucherDraft(draftId: String) { voucherDraftLines.removeAll { it.draftId == draftId } }
    override suspend fun insertVoucherDocumentReference(reference: VoucherDocumentReferenceEntity) {
        // Mirrors the real DAO's OnConflictStrategy.IGNORE on the (voucherId, documentAssetId)
        // unique index (MIGRATION_16_17) - a duplicate pair is silently dropped, original kept.
        val duplicate = voucherDocumentReferences.any { it.voucherId == reference.voucherId && it.documentAssetId == reference.documentAssetId }
        if (!duplicate) voucherDocumentReferences += reference
    }
    override suspend fun getDocumentReferencesForVoucher(companyId: String, voucherId: String) =
        voucherDocumentReferences.filter { it.companyId == companyId && it.voucherId == voucherId }
    override suspend fun deleteVoucherDocumentReference(companyId: String, referenceId: String): Int {
        val existing = voucherDocumentReferences.firstOrNull { it.companyId == companyId && it.referenceId == referenceId }
            ?: return 0
        voucherDocumentReferences.remove(existing)
        return 1
    }
    override suspend fun getVoucherAttachments(companyId: String, voucherId: String) =
        voucherDocumentReferences
            .filter { it.companyId == companyId && it.voucherId == voucherId }
            .mapNotNull { ref ->
                val asset = documentAssets.firstOrNull { it.assetId == ref.documentAssetId } ?: return@mapNotNull null
                com.example.accounting.data.local.dao.VoucherAttachmentRow(
                    referenceId = ref.referenceId, voucherId = ref.voucherId, documentAssetId = asset.assetId,
                    type = asset.type, storageReference = asset.storageReference, checksum = asset.checksum,
                    mimeType = asset.mimeType, sizeBytes = asset.sizeBytes, attachedAt = ref.createdAt
                )
            }
            .sortedByDescending { it.attachedAt }

    override suspend fun getSubscriptionForCompanyAndFy(companyId: String, financialYearId: String) =
        subscriptions.values.firstOrNull { it.companyId == companyId && it.financialYearId == financialYearId }
    override fun getSubscriptionsForCompany(companyId: String) = flowOf(subscriptions.values.filter { it.companyId == companyId })
    override suspend fun insertSubscription(subscription: CompanySubscriptionEntity) {
        check(subscriptions.values.none { it.companyId == subscription.companyId && it.financialYearId == subscription.financialYearId }) {
            "Duplicate (companyId, financialYearId) - simulates the unique DB index violation"
        }
        subscriptions[subscription.subscriptionId] = subscription
    }
    override suspend fun updateSubscription(subscription: CompanySubscriptionEntity) { subscriptions[subscription.subscriptionId] = subscription }

    override fun getBankUpiProfilesForCompany(companyId: String) = flowOf(bankUpiProfiles.values.filter { it.companyId == companyId })
    override fun getBankUpiProfilesForParty(companyId: String, partyId: String) =
        flowOf(bankUpiProfiles.values.filter { it.companyId == companyId && it.partyId == partyId })
    override suspend fun getBankUpiProfileById(companyId: String, bankUpiProfileId: String) =
        bankUpiProfiles[bankUpiProfileId]?.takeIf { it.companyId == companyId }
    override suspend fun insertBankUpiProfile(profile: BankUpiProfileEntity) { bankUpiProfiles[profile.bankUpiProfileId] = profile }
    override suspend fun updateBankUpiProfile(profile: BankUpiProfileEntity) { bankUpiProfiles[profile.bankUpiProfileId] = profile }
    override suspend fun deleteBankUpiProfile(companyId: String, bankUpiProfileId: String): Int =
        if (bankUpiProfiles[bankUpiProfileId]?.companyId == companyId && bankUpiProfiles.remove(bankUpiProfileId) != null) 1 else 0
}

/** Shared company/FY/period/ledger seed helper for Phase 7J-B pure-JVM suites - mirrors
 * [Phase7FTestSuite]'s own private `seed()` shape closely enough to stay familiar, kept here (not
 * private) so every 7J-B suite can reuse one canonical fixture instead of re-deriving it. */
object Phase7JBFixtures {
    const val COMPANY_ID = "COMP_P7JB"
    const val FY_ID = "FY_P7JB_2026_27"

    suspend fun AccountingDao.seedCompanyAndFy(companyId: String = COMPANY_ID, fyId: String = FY_ID, fyStart: String = "2026-04-01", fyEnd: String = "2027-03-31") {
        insertCompany(
            CompanyEntity(
                companyId = companyId, name = "Company", tradeName = "Company", gstin = "27AAAAA0000A1Z5",
                pan = "AAAAA0000A", stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
                currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
                accountingMode = AccountingMode.ACCOUNT_ONLY, businessType = BusinessType.TRADING
            )
        )
        insertFinancialYear(FinancialYearEntity(fyId, companyId, "FY", fyStart, fyEnd, true, false, null, null))
        insertPeriods(listOf(com.example.accounting.data.local.entity.AccountingPeriodEntity("PER_$fyId", companyId, fyId, "Full Year", fyStart, fyEnd, PeriodStatus.OPEN, null, null)))
    }

    fun ledgerEntity(id: String, companyId: String, bareGroupId: String, name: String = id, openingType: com.example.accounting.core.common.DrCr = com.example.accounting.core.common.DrCr.DEBIT) =
        LedgerEntity(
            id, companyId, "${bareGroupId}_$companyId", name, "", 0L, openingType, 0L, openingType,
            "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0
        )
}
