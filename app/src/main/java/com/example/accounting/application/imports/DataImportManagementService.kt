package com.example.accounting.application.imports

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.dataimport.DataImportAdapter
import com.example.accounting.domain.dataimport.ImportFileFormat
import com.example.accounting.domain.dataimport.ImportReconciliationSummary
import com.example.accounting.domain.dataimport.ImportResult
import com.example.accounting.domain.dataimport.ImportRowOutcome
import com.example.accounting.domain.dataimport.ImportRowSuggestion
import com.example.accounting.domain.dataimport.ImportSuggestionType
import com.example.accounting.domain.inventory.StockItem
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyEntityType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.rendering.BusinessProfile
import kotlinx.coroutines.flow.first

/**
 * Application-service orchestration for the Import Draft/Suggestion/Review workflow (Phase 7J-B) -
 * [parseFile] is a thin pass-through to the injected [DataImportAdapter] (never re-implements
 * parsing). [reviewAndCreate] is the **only** function anywhere in this workflow that calls
 * [AccountingRepository.createParty]/[AccountingRepository.createLedger]/[AccountingRepository.createStockItem]
 * - always in direct response to an explicit human review action on one already-parsed
 * [ImportRowSuggestion], never automatically for a whole file. Kept on this service rather than the
 * adapter specifically to preserve the adapter's own zero-`AccountingRepository`-access guarantee.
 */
class DataImportManagementService(
    private val adapter: DataImportAdapter,
    private val repository: AccountingRepository
) {

    suspend fun parseFile(requestingCompany: BusinessProfile, format: ImportFileFormat, fileAssetId: String): AccountingResult<ImportResult> =
        adapter.parseFile(requestingCompany, format, fileAssetId)

    /** Creates exactly one Party/Ledger/StockItem from one already-reviewed suggestion's raw
     * [ImportRowSuggestion.fieldValues] - [resolvedType] is the human reviewer's own confirmed
     * choice (never trusted from [ImportRowSuggestion.suggestionType] alone, since that field is
     * itself only a best-effort guess). A missing required column is a structured
     * [AppError.ValidationError], never a guessed default.
     *
     * Import-review group-picker fix - [groupIdOverride], when supplied, is the human reviewer's
     * own group choice from a live dropdown of the company's real, already-existing Groups (built
     * from [AccountingRepository.getGroups], same source [com.example.accounting.presentation.components.CreateLedgerDialog]
     * uses) and always wins over whatever the imported file's own "groupid"/"group" column
     * contained. Before this, a LEDGER row's group came only from that raw file column, which had
     * to already be this app's own internal groupId string (e.g. "GRP_BANK_OD_COMP123") - not
     * something any real external Balance Sheet export would ever contain, making it practically
     * impossible to correctly classify an imported Bank OD/Loan/etc. ledger. `null` (the default)
     * preserves the original file-column-only behavior exactly, for any caller that already has a
     * valid internal groupId (existing tests, programmatic imports). */
    suspend fun reviewAndCreate(companyId: String, suggestion: ImportRowSuggestion, resolvedType: ImportSuggestionType, groupIdOverride: String? = null): AccountingResult<Any> {
        val fields = suggestion.fieldValues
        return when (resolvedType) {
            ImportSuggestionType.PARTY -> {
                val name = firstNonBlank(fields, "name", "partyname", "displayname", "party")
                    ?: return AccountingResult.Failure(AppError.ValidationError("Row ${suggestion.rowNumber}: could not find a party name column."))
                repository.createParty(
                    Party(
                        partyId = "",
                        companyId = companyId,
                        ledgerId = "",
                        role = if (firstNonBlank(fields, "role")?.uppercase() == "SUPPLIER") PartyRole.SUPPLIER else PartyRole.CUSTOMER,
                        entityType = PartyEntityType.BUSINESS,
                        displayName = name
                    )
                )
            }
            ImportSuggestionType.LEDGER -> {
                val name = firstNonBlank(fields, "name", "ledgername", "ledger")
                    ?: return AccountingResult.Failure(AppError.ValidationError("Row ${suggestion.rowNumber}: could not find a ledger name column."))
                val groupId = groupIdOverride ?: firstNonBlank(fields, "groupid", "group")
                    ?: return AccountingResult.Failure(AppError.ValidationError("Row ${suggestion.rowNumber}: no Account Group was selected and the file has no group id column."))
                // Verified live: AccountingRepository.createLedger performs no existence check of
                // its own on `ledger.groupId` before inserting - without this lookup, an imported
                // row with a typo'd or cross-company group id would silently create a ledger that
                // every report then misclassifies (generateTrialBalance falls back to
                // PrimaryGroup.ASSETS for an unresolvable groupId, never an error). getGroups is
                // already company-scoped, so this one check covers both "exists" and "belongs to
                // this company" at once.
                val groupExists = repository.getGroups(companyId).first().any { it.groupId == groupId }
                if (!groupExists) {
                    return AccountingResult.Failure(AppError.ValidationError("Row ${suggestion.rowNumber}: group '$groupId' was not found for this company."))
                }

                // Opening balance: "openingbalance"/"opening_balance"/"opening balance" all
                // normalize to the same "openingbalance" key via firstNonBlank's own existing
                // normalization (lowercase, strip spaces/underscores) - "balance" is a distinct
                // normalized key, so it's listed separately. Same reasoning for the type column
                // ("openingbalancetype"/"opening_balance_type"/"opening balance type" collapse to
                // one key; "drcr"/"type" are separate). A blank value is indistinguishable from an
                // absent column here - firstNonBlank already treats them identically - so that is
                // this feature's blank-value policy: not a new rule, a reuse of the existing one.
                val openingBalanceRaw = firstNonBlank(fields, "openingbalance", "balance")
                val openingBalanceTypeRaw = firstNonBlank(fields, "openingbalancetype", "drcr", "type")

                val (openingBalance, openingBalanceType) = when {
                    openingBalanceRaw != null && openingBalanceTypeRaw != null -> {
                        val amount = when (val r = parseImportedOpeningBalance(suggestion.rowNumber, openingBalanceRaw)) {
                            is AccountingResult.Failure -> return r
                            is AccountingResult.Success -> r.data
                        }
                        val type = when (val r = parseImportedDrCr(suggestion.rowNumber, openingBalanceTypeRaw)) {
                            is AccountingResult.Failure -> return r
                            is AccountingResult.Success -> r.data
                        }
                        amount to type
                    }
                    openingBalanceRaw != null && openingBalanceTypeRaw == null ->
                        return AccountingResult.Failure(AppError.ValidationError("Row ${suggestion.rowNumber}: an opening balance was given without an opening balance type column."))
                    openingBalanceRaw == null && openingBalanceTypeRaw != null ->
                        return AccountingResult.Failure(AppError.ValidationError("Row ${suggestion.rowNumber}: an opening balance type was given without an opening balance column."))
                    // Both absent - preserves the pre-existing default (Ledger's own Money.ZERO/
                    // DrCr.DEBIT defaults) exactly, never a second policy.
                    else -> Money.ZERO to DrCr.DEBIT
                }

                repository.createLedger(
                    Ledger(
                        ledgerId = "", companyId = companyId, groupId = groupId, name = name,
                        openingBalance = openingBalance, openingBalanceType = openingBalanceType
                    )
                )
            }
            ImportSuggestionType.STOCK_ITEM -> {
                val name = firstNonBlank(fields, "name", "itemname", "item")
                    ?: return AccountingResult.Failure(AppError.ValidationError("Row ${suggestion.rowNumber}: could not find an item name column."))
                repository.createStockItem(
                    StockItem(
                        itemId = "",
                        companyId = companyId,
                        name = name,
                        sku = firstNonBlank(fields, "sku") ?: "",
                        hsnCode = firstNonBlank(fields, "hsn", "hsncode") ?: "",
                        gstRatePercent = firstNonBlank(fields, "gst", "gstrate", "gstratepercent")?.toDoubleOrNull() ?: 18.0
                    )
                )
            }
        }
    }

    /** Pure aggregation, direct delegation to [ImportReconciliationSummary.from] - never a second
     * computation of what was accepted/rejected/unresolved. [rowOutcomes] is the caller's own
     * record of each suggestion's review outcome (e.g. from repeated [reviewAndCreate] calls);
     * this service does not track outcomes itself. */
    fun summarize(result: ImportResult, rowOutcomes: Map<Int, ImportRowOutcome>): ImportReconciliationSummary =
        ImportReconciliationSummary.from(result, rowOutcomes)

    /** Fail-closed money parser for an imported opening-balance value - deliberately never
     * [com.example.accounting.core.common.Money.parse], which silently returns [Money.ZERO] for
     * any unparseable input (a reasonable default for a live UI field while typing; the wrong
     * behavior for an import row, where a bad value must be rejected, never guessed into a
     * plausible-looking zero). Accepts a plain decimal string (currency symbol/thousands
     * separators tolerated, matching `Money.parse`'s own sanitization) - anything else is a
     * row-numbered [AppError.ValidationError], never a silent [Money.ZERO]. */
    private fun parseImportedOpeningBalance(rowNumber: Int, raw: String): AccountingResult<Money> {
        val sanitized = raw.replace("₹", "").replace(",", "").trim()
        val value = sanitized.toDoubleOrNull()
            ?: return AccountingResult.Failure(AppError.ValidationError("Row $rowNumber: '$raw' is not a valid opening balance amount."))
        return AccountingResult.Success(Money.fromRupees(value))
    }

    /** Fail-closed [DrCr] parser for an imported opening-balance-type value - deliberately never
     * raw `DrCr.valueOf(...)`, which throws on anything other than exactly "DEBIT"/"CREDIT" and
     * would crash the whole import batch over one bad row. Normalizes case/whitespace and accepts
     * this import format's documented aliases (Debit/Credit, Dr/Cr) - anything else is a
     * row-numbered [AppError.ValidationError], never a thrown exception. */
    private fun parseImportedDrCr(rowNumber: Int, raw: String): AccountingResult<DrCr> = when (raw.trim().uppercase()) {
        "DEBIT", "DR" -> AccountingResult.Success(DrCr.DEBIT)
        "CREDIT", "CR" -> AccountingResult.Success(DrCr.CREDIT)
        else -> AccountingResult.Failure(AppError.ValidationError("Row $rowNumber: '$raw' is not a valid opening balance type (expected Debit/Credit/Dr/Cr)."))
    }

    private fun firstNonBlank(fields: Map<String, String>, vararg keys: String): String? {
        val normalized = fields.mapKeys { it.key.lowercase().replace(" ", "").replace("_", "") }
        for (key in keys) {
            normalized[key]?.let { if (it.isNotBlank()) return it }
        }
        return null
    }
}
