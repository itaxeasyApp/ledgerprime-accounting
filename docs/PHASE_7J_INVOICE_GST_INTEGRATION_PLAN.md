# Phase 7J - Invoice + GST Integration - Audit & Plan (PLANNING ONLY, NOT IMPLEMENTED)

Scope: bring full GST coverage (party registration/GSTIN, applicability, inclusive/exclusive
pricing, Place of Supply, CGST/SGST/IGST/CESS, live totals, returns, validation, explicit-post)
**inside** the existing Invoice Dashboard/Form - no popup, Toast/Snackbar, floating dialog, or
separate GST screen. This document is the audit + sub-phase plan only. Nothing in this document
has been implemented; no code was changed to produce it.

---

## 0. What "the Invoice Dashboard/Form" actually is (audit finding)

There are **two** Sale/Purchase entry surfaces in this codebase today, and they are not the same
screen:

- **`QuickInvoiceEntryScreen.kt`** (`presentation/features/invoice/`) - the real, current Invoice
  Dashboard/Form. Full-screen (`Dialog(usePlatformDefaultWidth = false)`), one fixed title ("New
  Sale Invoice"/"New Purchase Bill"/"New Income Entry"/"New Expenditure Entry"), always-visible
  bottom bar with a live status line and one big Save button. This is what opens for every *fresh*
  Sale/Purchase (`MainAppScreen.kt`'s `isNewInvoiceEntry` check). **This is the screen this plan's
  new GST work goes inside of - not a new screen.**
- **`CreateVoucherDialog.kt`** - the older, generic 8-voucher-type modal. Still the *only* place
  Receipt/Payment/Contra/Journal/Credit Note/Debit Note are created, and the only place a Sale/
  Purchase *correction* (re-post) happens (`isCreateVoucherTypeLocked` + `pendingVoucherCorrection`
  routes back here even for Sales/Purchase). Confirmed via `MainAppScreen.kt`'s own routing:
  `isNewInvoiceEntry` is `false` whenever `pendingVoucherCorrection != null` or the type isn't
  SALES/PURCHASE.

Both wrap the **same** reusable form, `TradingForm.kt` (`presentation/components/`), for the
Sale/Purchase item-line UI - `QuickInvoiceEntryScreen` doesn't duplicate it, it just gives it
different chrome. This is the key reuse fact this whole plan is built on.

---

## 1. Existing components/files to reuse (confirmed present and working)

| File | What it already does |
|---|---|
| `presentation/features/invoice/QuickInvoiceEntryScreen.kt` | The Invoice Dashboard/Form itself - party/trade-ledger selection, always-visible status line + Save button, explicit-post only |
| `presentation/components/TradingForm.kt` | Party picker (Debtor/Creditor-filtered, "+ Add New" inline), trade-ledger picker, Place-of-Supply-missing inline banner (`errorContainer` `Surface`, already the established "no popup" pattern), GST Pricing Inclusive/Exclusive `FilterChip` row (item mode only today), per-line item/qty/rate/discount/tax-treatment/RCM editing |
| `TradingForm.kt`'s private `VoucherLineItemCard` | One item line's fields + live per-line GST text (`"Amount X - GST Y% - HSN Z"`) |
| `TradingForm.kt`'s private `TradingTotalsSummary` | Live Taxable/CGST/SGST/IGST/Round Off/Grand Total card - already reads real `RoundOffEngine.roundInvoiceTotal`, already correctly shows CGST+SGST XOR IGST never both |
| `domain/taxation/gst/GstCalculationEngine.kt` (`calculateDetailed`, `GstTransactionFacts`, `TaxBreakdown`) | The one GST calculation engine - **already Cess-capable** (`cessRatePercent`/`cessAmount` fields exist and are wired into `totalTax`/`totalWithTax`), just never fed a non-zero Cess rate from any UI today |
| `domain/taxation/gst/GSTRules.kt` (`isValidGSTIN`, `extractTaxableFromInclusive`) | Shared GSTIN format check and the one Inclusive->taxable formula (already used identically by `TradingForm` preview and `TradingWorkflowEngine` posting) |
| `core/common/Constants.kt` (`GST_RATES`, `GST_STATE_CODES`, `stateCodeForName`) | Shared rate list and state code/name lookup (just extended this session to two more screens) |
| `presentation/components/CreatePartyDialog.kt` | The just-fixed (this session) GST-Registration-derived-from-GSTIN pattern (`gstin.isNotBlank() -> REGISTERED else UNREGISTERED`) - the model to reuse for *displaying* a party's status here, not re-derive differently |
| `domain/trading/TradingWorkflowEngine.kt` | The one Sale/Purchase build/posting engine - already called by every `AccountingViewModel.postSaleInvoice`/`postPurchaseBill`/`postAccountOnlySale`/`postAccountOnlyPurchase` |
| `presentation/viewmodel/AccountingViewModel.kt` | `postTradingDocument`/`postAccountOnlyTradingDocument`'s existing State-blocked validation + the `gstBlockedLedgerFixTarget` StateFlow (added this session) - the established pattern for "block posting, but hand the UI something to fix it with," to extend rather than duplicate |
| `presentation/components/CreateVoucherDialog.kt`'s `NoteForm` + `isCreditNoteFlow`/`isDebitNoteFlow`/`eligibleOriginals`/`onPostCreditNote`/`onPostDebitNote` | The existing, working Credit/Debit Note flow - full reversal of a prior Sale/Purchase by `originalVoucherId`, GST inherited from the original voucher's own already-posted `GstTransaction` rows (never recomputed) |
| `domain/accounting/RoundOffEngine.kt` | The one round-off formula, shared by preview and posting |
| `presentation/components/SectionCard.kt` | **No component named "StateCard" exists anywhere in this codebase** (confirmed by repo-wide search) - this is the closest existing reusable card component. Treating this as what was meant; please confirm/correct if a different component was intended. |

## 2. Missing components/files (confirmed gaps, mapped to your 8 numbered areas)

None of these require a new screen, a new engine, or a new posting path - every gap below is
"extend an existing branch of `TradingForm.kt`/`QuickInvoiceEntryScreen.kt` with the same engine
calls the item-mode branch already makes."

| # | Area | Gap found |
|---|---|---|
| 1 | Party + Registered/Unregistered + GSTIN validation | The party picker shows only `name (groupName)` - no GSTIN, no Registered/Unregistered status, anywhere in `TradingForm`/`QuickInvoiceEntryScreen`. No inline re-validation of a selected party's stored GSTIN format at invoice time (only validated once, at party-creation, in `CreatePartyDialog`). |
| 2 | GST Applicable + Inclusive/Exclusive | GST Pricing Inclusive/Exclusive `FilterChip` row exists **only** in the item-mode branch of `TradingForm`. The Account-Only branch (`!isInventoryEnabled`) has GST Rate chips but no Inclusive/Exclusive choice at all - a lump-sum GST-inclusive account-only entry is not possible today. |
| 3 | Place of Supply + CGST/SGST/IGST/CESS | Place-of-Supply-missing banner exists and applies to both modes already (`placeOfSupplyMissing` gates on `isInventoryEnabled` generically). But `GstCalculationEngine.calculateDetailed` is only ever called from the item-mode branch - the Account-Only branch never calls it, so Account-Only invoices get **no CGST/SGST/IGST split preview at all**, only a flat rate chip. **CESS has no input anywhere** - `StockItem` has no `cessRatePercent` field and the Account-Only branch has no Cess field either, even though the engine already supports it. |
| 4 | Live Taxable Value + GST breakup + Total | `TradingTotalsSummary` (the live breakup card) is only called from the item-mode branch. Account-Only mode shows nothing but the bottom bar's plain `"Ready to Save - Total X"` - no Taxable/CGST/SGST/IGST breakup before Save. This was directly observed live this session: posting a real Account-Only GST sale showed no tax breakdown until after posting. |
| 5 | Sales/Purchase Returns + Credit/Debit Notes | Confirmed: Credit Note/Debit Note creation only exists in the old `CreateVoucherDialog` modal (`NoteForm`), never in `QuickInvoiceEntryScreen`. `MainAppScreen.kt`'s own routing comment says so explicitly ("every other voucher type... keep using CreateVoucherDialog completely unchanged"). Not reachable from inside the Invoice Dashboard/Form at all today. |
| 6 | Inline validation/errors | The two existing inline banners (Place-of-Supply-missing, no-trade-ledger) are the right pattern, but line-level fields (Qty/Rate/Discount in `VoucherLineItemCard`) have no `isError`/format validation, and a party's stored GSTIN is never re-checked for format at invoice time (only at creation). |
| 7 | Live recalculation | **Not a gap** - Compose recomputes `runningTaxable`/`runningCgst`/etc. reactively on every state change already; this applies automatically to whatever gets added in items 2-4 above with no extra wiring. Listed here as a verification checkpoint, not a build step. |
| 8 | Explicit Post only | **Not a gap** - both `QuickInvoiceEntryScreen`'s Save button and `CreateVoucherDialog`'s Note-flow Save button already require an explicit enabled-only-when-ready tap; nothing auto-posts. Listed here as a verification checkpoint to hold future sub-phases to, not a build step. |

---

## 3. Sub-phase order (small, sequential)

```
Sub-phase A: Party GST identity display        (area 1)
        |
        v
Sub-phase B: Inclusive/Exclusive for Account-Only  (area 2)
        |
        v
Sub-phase C: Place of Supply + CGST/SGST/IGST/CESS for Account-Only  (area 3)
        |
        v
Sub-phase D: Live totals card for Account-Only   (area 4)  -- reuses TradingTotalsSummary as-is
        |
        v
Sub-phase E: Credit/Debit Notes inside the Invoice Dashboard  (area 5)
        |
        v
Sub-phase F: Inline field-level validation        (area 6)
        |
        v
Sub-phase G: Verification pass                    (areas 7 + 8, no new code expected)
```

### Sub-phase A - Party GST identity display - **DONE (2026-09-11)**
**Covers area 1.** Show the selected party's GSTIN and derived Registered/Unregistered status
inline, directly under the existing party `ExposedDropdownMenuBox` in `TradingForm.kt` (same
`errorContainer`/`surfaceVariant` `Surface` banner pattern already used for the Place-of-Supply
warning two lines below it - not a new visual language). Reuses the exact derivation
`CreatePartyDialog.kt` now uses (`ledger.gstin.isNotBlank() -> Registered`). No new state, no new
validation blocking - display only.
**Dependencies:** None - can start immediately.

### Sub-phase B - Inclusive/Exclusive for Account-Only - **DONE (2026-09-11, expanded scope - see note below)**
**Covers area 2.** Move the existing Inclusive/Exclusive `FilterChip` `Row` (currently only above
the item-mode `Items` section) so it also renders in the `!isInventoryEnabled` branch, before the
GST Rate chips. Reuses the exact same `pricingMode`/`onPricingModeChange` plumbing already
threaded through `TradingForm`'s parameters - no new parameter needed.
**Dependencies:** None technically, but naturally precedes C/D since the taxable value C computes
depends on which pricing mode is active.

### Sub-phase C - Place of Supply + CGST/SGST/IGST/CESS for Account-Only - **DONE (2026-09-11, CESS landed under its own instruction also labeled "Sub-phase C" - see note below)**
**Covers area 3.** CGST/SGST/IGST landed as part of the instruction that implemented Sub-phase B
(it explicitly required "live calculated values" alongside the Inclusive/Exclusive toggle): the
Account-Only branch calls `GSTRules.extractTaxableFromInclusive` (when pricing mode is Inclusive)
then `GstCalculationEngine.calculateDetailed` with the same `GstTransactionFacts` shape the
item-mode branch already builds, gated by a broadened `placeOfSupplyMissing` check (now also true
for Account-Only once `gstApplicable` and a non-zero rate are selected).
CESS then landed under a separate instruction that independently called itself "Sub-phase C" -
inspection found `TradingLineInput.cessRatePercent`/`GstTransactionFacts.cessRatePercent`/
`TaxBreakdown.cessAmount`/a real `gstLedgers.cess` duty ledger were **already fully wired end to
end** (calculation, posting, duty-ledger, `GstTransaction.cessPaise` storage) - only a UI rate
input was missing anywhere. Added: `LineFormState.cessRateInput` (item-mode, per line, same manual
shape as `discountInput` - no `StockItem.cessRatePercent` field exists to default it from) and a
matching `cessRateInput`/`onCessRateChange` pair on `TradingForm` for Account-Only. Both feed the
same `GstCalculationEngine.calculateDetailed` call already used for CGST/SGST/IGST - Cess is
computed on the same already-extracted taxable value, never backed out of an Inclusive amount a
second way (matching `TradingWorkflowEngine.lineAmounts`'s own order exactly). Threaded through to
posting via `TradingLineForm.cessRatePercent` (item-mode) and a new `cessRatePercent` parameter on
`postAccountOnlySale`/`postAccountOnlyPurchase` (Account-Only).
**Dependencies:** None remaining.

### Sub-phase D - Live totals card for Account-Only - **DONE (2026-09-11)**
**Covers area 4.** `TradingTotalsSummary` now takes a `cess: Money = Money.ZERO` parameter (shown
as its own row only when positive, folded into Total GST/Round Off/Grand Total exactly as
`TradingWorkflowEngine`'s own `rawTotal` already does) and is fed by the same breakdown both the
item-mode loop and the Account-Only branch compute.
**Dependencies:** None remaining.

### Sub-phase E - Credit/Debit Notes inside the Invoice Dashboard
**Covers area 5.** Reuse `CreateVoucherDialog.kt`'s existing `NoteForm` composable and its
`isCreditNoteFlow`/`eligibleOriginals`/`onPostCreditNote`/`onPostDebitNote` logic verbatim - no
new return-calculation logic (none exists anywhere in this codebase to build; Credit/Debit Notes
are, and stay, full reversals of an already-posted Sale/Purchase, GST inherited from the original
voucher's own `GstTransaction` rows, never recomputed). What changes is only reachability: add a
"Sales Return" / "Purchase Return" entry point alongside the existing Invoice/Purchase quick
actions that opens `NoteForm` inside `QuickInvoiceEntryScreen`-style full-screen chrome (reusing
that screen's header/footer/status-line pattern) instead of the cramped `CreateVoucherDialog`
modal. This needs a short design decision first (see below) - not a redesign of `NoteForm` itself.
**Dependencies:** None on B/C/D technically, but sequenced after them so the "what this note
reverses" display can reuse A's party-GST-identity display and C/D's breakdown-card pattern for
consistency, rather than inventing a different look for the reversal screen.
**Open design question for this sub-phase specifically (flagging now, not deciding for you):**
should the new entry point be a) a small addition to `QuickInvoiceEntryScreen` itself (an
`isReturn` flag), or b) `NoteForm` moved into its own file reusing the same Dialog/Surface/Column
chrome pattern `QuickInvoiceEntryScreen` establishes? Both are "reuse, not redesign" - differ only
in whether one file grows or a new, small, chrome-only file appears.

### Sub-phase F - Inline field-level validation
**Covers area 6.** Add `isError`/`supportingText` to `VoucherLineItemCard`'s Qty/Rate fields
(reusing the exact `isError = <condition>` pattern `CreatePartyDialog.kt` already uses for its
GSTIN/Phone/Email fields - same convention, not a new one), and a defensive GSTIN-format re-check
(`GSTRules.isValidGSTIN`) on the party's stored GSTIN, surfaced via Sub-phase A's display banner
if it fails (legacy/imported parties may predate the format-validation fix already applied in
`CreatePartyDialog`).
**Dependencies:** Needs A (the display surface to attach a warning to) and the item-line UI
unchanged from today (no new fields to validate beyond what already exists).

### Sub-phase G - Verification pass
**Covers areas 7 and 8.** No new code expected. Confirm: (a) every value added in B-D recomputes
live on every keystroke/selection change, same as the item-mode branch already does (inherent to
Compose state - a read-only check, not a build step); (b) no sub-phase above introduced any
auto-post path - every new field only feeds the existing, unchanged, explicit Save button's
already-computed `readyTotal`/`isReady` gate. This is the sub-phase where `./gradlew
testDebugUnitTest` + a live device pass happens across all four business configurations (Account-
Only/Account+Inventory x Trading/Service), mirroring Phase 8's own device-testing discipline.
**Dependencies:** All of A-F complete.

---

## 4. What this plan deliberately does NOT include

- No new screen, dialog, popup, Toast, or Snackbar - every sub-phase adds to `TradingForm.kt`'s
  existing branches or reuses `QuickInvoiceEntryScreen.kt`'s existing chrome.
- No second GST calculation path - every sub-phase calls `GstCalculationEngine`/`GSTRules`/
  `RoundOffEngine`, never a new formula.
- No new posting engine - Sub-phase E reuses `NoteForm`'s existing `onPostCreditNote`/
  `onPostDebitNote` wiring into `TradingWorkflowEngine` verbatim.
- No partial-amount Credit/Debit Note capability - that would be a genuinely new calculation this
  codebase has never had (today's Notes are always a full reversal); out of scope unless you
  explicitly ask for it as its own, separate, later phase.
- No `StockItem`-level Cess field/schema change - Sub-phase C's Cess field is Account-Only-only,
  the smallest change that closes the gap without touching the item catalog's schema.

## 5. Next exact step

Sub-phase A (party GST identity display) - the smallest, fully-independent first step, touching
only `TradingForm.kt`'s existing party-picker section with a read-only display, no new state, no
posting-path change.

**STOP - no code written. Awaiting confirmation before Sub-phase A begins.**
