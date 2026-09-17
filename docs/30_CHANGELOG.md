# 30. Project Changelog

## Phase 0 - Architectural Foundation & Integrity Verification (2026-08-21)
- **Hardened Security**: Implemented `SecureStorage` with Keystore AES-256 GCM encryption and graceful sandbox fallback.
- **Authoritative Money Representation**: Defined single source of truth using 64-bit integer paise backed by `BigDecimal` for safe display.
- **Double-Entry Domain Engine**: Implemented `DoubleEntryValidator` enforcing $\sum \text{Debit} == \sum \text{Credit}$, non-zero amounts, period locking, and company scoping.
- **Room Database & Migration Policy**: Removed destructive fallback migration; configured explicit migration architecture across 11 core accounting entities.
- **Offline Outbox Processor**: Implemented FIFO mutation synchronization with cryptographic idempotency keys and exponential backoff retry policy.
- **Document & Voucher Classification**: Added formal classification enums (`FINANCIAL_POSTING`, `INVENTORY_ONLY`, `NON_POSTING`, `CONDITIONAL_POSTING`).
- **Comprehensive Documentation Suite**: Completed all 27 architectural specification documents.
- **Mandatory Invariant Test Suite**: Added automated tests for double-entry balance, locked periods, ledger deletion rules, suspense protection, and tenant isolation.

## Phase 2 - Production Double-Entry Voucher & Posting Engine (2026-08-21)
- **Atomic Posting Engine**: Extracted `VoucherPostingEngine` (Room-independent) wrapped by `DatabaseTransaction` for real atomic posting; fixed `AccountingRepository.postVoucher()`, which previously wrote sequentially with no transaction.
- **Compensating Reversal Cancellation**: Replaced the prior hard-delete voucher cancellation with an append-only compensating reversal, per the Deletion Policy.
- **Idempotency & Duplicate-Number Guards**: Replayed posting/cancellation requests are now safe no-ops; duplicate voucher numbers are rejected.
- **Audit Pass**: Added tenant-scoping and period-FY-consistency checks to `DoubleEntryValidator`; applied the period-lock rule to cancellation as well as posting.

## Phase 3 - Financial Statements Engine (2026-08-21)
- **Group-Hierarchy Classification**: Replaced name/`contains()`-based classification in `generateProfitAndLoss`/`generateBalanceSheet` with exact groupId-ancestry resolution (`GroupAggregationEngine`) - the accounts hierarchy is now walked (`Ledger -> groupId -> Group -> PrimaryGroup`), never text-matched.
- **Recursive Group Aggregation**: New `GroupAggregationEngine` computes bottom-up group totals with cycle detection (`AppError.GroupHierarchyInvalid`).
- **P&L Opening-Balance Fix**: `generateProfitAndLoss` now sums period-only transaction movement, not closing balance (which could have leaked an Income/Expense ledger's opening balance into Net Profit).
- **Structured Statement Errors**: Added `TrialBalanceNotBalanced`, `BalanceSheetNotBalanced`, `InvalidFinancialYear`, `InvalidDateRange`, `GroupHierarchyInvalid`, `AccountingDataCorrupted` to `AppError`.
- **Date Range & Zero-Balance Controls**: Trial Balance/P&L/Balance Sheet accept an optional custom date range (validated against the financial year) and an `includeZeroBalance` flag.
- **Balance Sheet completeness**: Added distinct Reserves & Surplus, Investments, Misc. Expenses (Asset), and Branch/Divisions lines.

## Phase 4 - Inventory & COGS (2026-08-22)
- **Parallel inventory system**: New `VoucherStockLineEntity`/`StockMovementEntity` (immutable movement ledger) alongside the unchanged journal/ledger posting path - periodic inventory, not perpetual; Purchase/Sales journal postings are byte-for-byte unchanged from pre-Phase-4.
- **`InventoryEngine`/`StockValuationEngine`/`CogsEngine`**: Room-independent, mirroring `VoucherPostingEngine`'s design. Weighted-average costing (FIFO deliberately deferred, not built speculatively). `VoucherPostingEngine.post()`/`cancel()` gained an additive `stockLines` step (empty by default - zero behavior change for non-inventory callers).
- **`AccountingMode` (ACCOUNT_ONLY / ACCOUNT_WITH_INVENTORY)** and **`BusinessType` (TRADING / SERVICE)** added to `Company`/`CompanyEntity`. Switching modes never deletes underlying data (`AccountingRepository.updateAccountingConfiguration`).
- **P&L/Balance Sheet inventory awareness**: `generateProfitAndLoss` computes COGS (Opening Stock + Purchases - Purchase Returns - Closing Stock) in place of raw Purchases when inventory-aware; `generateBalanceSheet` folds in Stock-in-Hand as a computed figure (no new ledger, same pattern as `netProfitForYear`).
- **`generateIncomeAndExpenditure`**: new statement for SERVICE-type companies, reusing the same group-hierarchy engine as P&L without a Trading/COGS section.
- **`GstCalculationEngine`**: extracted GST math out of the ViewModel; fixed the tax-ledger lookup from name-matching to exact system ledger ID (`GstLedgerIds`).
- **Migration 1->2**: additive only (2 new tables, 2 new `companies` columns, 1 new `stock_items` column), each with a default reproducing exact pre-Phase-4 behavior.

## Phase 4.5 - Accounting UX & Domain-Consistency (2026-08-22, moderate scope implemented)
- Read-only audit of the existing UI (`CreateVoucherDialog`, `ChartOfAccountsScreen`, `VoucherDetailDialog`) against the intended guided-workflow/GST/presentation model - see `docs/31_PHASE_4_5_UX_DOMAIN_CONSISTENCY.md` for the full consolidated specification and freeze checklist.
- Confirmed gaps: no dedicated Purchase/Credit-Note/Debit-Note workflow exists in the UI; Payment/Receipt/Contra/Journal share one generic raw Debit/Credit ledger picker; Contra is not restricted to Cash/Bank; GST ledgers conflate Input and Output into one ledger per tax type; the Sales GST form uses a hardcoded rate-chip list disconnected from the Phase 4 item model; "Double Entry Balance: Balanced" and "SYSTEM" badges are shown in normal UI.
- New rules frozen for the eventual implementation pass: GST filing-period governance separate from accounting-period governance; a permanent system `Round Off` ledger distinct from `Suspense`; no `₹`/Indian-grouping in accounting number displays; zero-balance/no-entry ledgers hidden from normal views by default (never auto-deleted); internal architecture (audit trail, tenant ID, invariants) stays enforced but hidden from normal users.
- **Implemented this pass (moderate scope)**: `AccountingMode`/`BusinessType` toggle wired into `SettingsAndSyncScreen` (previously engine-complete since Phase 4 but never surfaced in UI); Reports screen now renders COGS/Opening-Closing Stock lines when inventory-aware and the `IncomeExpenditureReport` view when `BusinessType.SERVICE`; guided Purchase-with-GST workflow added alongside the existing Sales-with-GST workflow, both routing through a shared `postGstVoucher` resolver; GST ledgers split from 3 conflated ledgers into 6 (`GstLedgerIds.OUTPUT_*`/`INPUT_*` x CGST/SGST/IGST) under one Duties & Taxes group, with an idempotent `AccountingRepository.ensureGstLedgersExist` backfill for already-seeded companies; Contra voucher ledger picker restricted to Cash/Bank via exact groupId-prefix match; "Double Entry Balance: Balanced" banners removed from voucher creation, voucher detail, Trial Balance, and Balance Sheet in favor of plain totals; `Money.formatPlain()` added (no `₹`, no Indian digit grouping) and applied across Reports/Ledgers/Voucher-detail; zero-balance ledgers hidden by default in Chart of Accounts with a "Show unused" toggle; "SYSTEM" badges removed from normal ledger/group browsing; audit log actions mapped to friendly labels ("Posted", "Cancelled", ...) instead of raw enum names.
- **Explicitly deferred** (not this pass, not silently dropped): Credit Note/Debit Note dedicated workflows; the permanent `Round Off` system ledger; Receipt/Payment outstanding-invoice allocation matching; GST filing-period governance entity (Phase 5 territory); Individual-vs-Business capital-account naming; app-wide `Money.format()` -> `formatPlain()` sweep beyond the screens touched this pass.
- Verification: `compileDebugKotlin` clean; `testDebugUnitTest` 133/136 passing (only the pre-existing, unrelated Robolectric `DefaultSdkProvider` trio fails) - 6 new JVM tests added in `Phase4TestSuite.kt` covering `ensureGstLedgersExist` (creates all six, idempotent, backfill-only-missing-preserves-existing-balance) and Output/Input ledger-ID/tax-split correctness. UI changes could not be visually verified/screenshotted in this environment.
- Still gates Phase 5 (GST/statutory) - the deferred items above remain open before that gate is considered satisfied.

## Phase 5 - GST & Statutory Accounting Engine (2026-08-22, full scope implemented)
- Read-only audit first (per the user's own required discipline): confirmed GST math was solid and tested but everything downstream was incomplete - GST Sales/Purchase never moved inventory despite `affectsInventory=true`; `generateGSTSummary` reconstructed facts via `ledgerName.contains("Output CGST")`; Credit/Debit Note had reserved `VoucherType` slots and an `InventoryEngine` mapping but zero reversal logic; Contra was UI-filtered only, not domain-enforced; Receipt/Payment had no invoice-allocation model; Round Off and CESS did not exist.
- **Schema (Migration 2->3, additive only)**: new `gst_transactions` table (the dedicated per-line GST fact record - party, place of supply, HSN/SAC, rate, CGST/SGST/IGST/CESS, Output/Input direction - replacing the old ledger-name scan entirely), new `settlement_allocations` table (Receipt/Payment invoice allocation, outstanding always computed at query time, never stored redundantly), new `gst_filing_periods` table (deliberately unreferenced by any accounting-period or posting-engine code), and two new nullable/defaulted columns on `vouchers` (`referenceVoucherId` for Credit/Debit Note -> original invoice linkage, `paymentMode` for Cash/Bank/UPI metadata).
- **`TradingWorkflowEngine`** (new, Room-independent, mirrors `VoucherPostingEngine`'s design): builds real item-line Sale/Purchase documents - GST rate/HSN sourced from the selected `StockItem`, never hardcoded; produces `VoucherStockLine`s so Sale/Purchase now actually move inventory and create COGS-eligible movements; produces `GstTransaction` rows per line; computes Round Off via a separate `RoundOffEngine` and appends it as a journal line, never typed in by the UI. `buildNote()` builds Credit Note (against a Sale) / Debit Note (against a Purchase) as a full reversal - opposite journal/stock lines, negated GST-transaction rows at the same direction as the original (the standard GST-return representation) - the original voucher is only ever read, never modified.
- **GST engine refinement** (additive, `calculate()`/`calculateWithFallback()` untouched): `TaxBreakdown` gained per-component rates and CESS; new `GstSupplyNature` (Export/Exempt/Nil-rated now actually reachable, previously dead branches) and `calculateDetailed()` deriving intra/inter-state from Place of Supply vs supplier state specifically, not just "party's ledger state."
- **Round Off**: new `LED_SYS_ROUND_OFF` ledger, protected identically to Suspense (same four `isSystem`/exact-ID-prefix guards in `AccountingRepository`) but conceptually distinct and never combined with it.
- **Contra enforcement moved to the domain layer**: `VoucherPostingEngine.post()` now rejects any Contra voucher touching a non-Cash/Bank ledger (`AppError.InvalidContraLedger`) - previously only the UI dialog filtered this, so a direct repository/API call could bypass it entirely.
- **Settlement allocation**: `AccountingRepository.allocateSettlement`/`getOutstandingInvoices` support full/partial/multi-invoice/advance receipt and payment allocation through one rule (`Σ allocations + unallocated == settlement total`); Receipt/Payment structurally cannot reach `GstCalculationEngine`.
- **`generateGSTSummary` rewritten** off `gst_transactions` - zero `ledgerName.contains(...)` left; gained CESS totals. Same name-matching anti-pattern fixed in `ScheduledReportTasks.kt` (`groupId.contains("BANK"/"DEBTORS"/"CREDITORS")` -> exact `StandardSystemGroups` prefix checks).
- **UI**: item-line Sale/Purchase forms (item/qty/rate picker reading GST rate from the item), a new Credit/Debit Note original-invoice picker, a Receipt/Payment allocation screen (outstanding invoices with per-invoice allocate amounts, Cash/Bank/UPI payment-mode chips), a new "Items" tab in Chart of Accounts (stock items had no creation UI at all before this phase - added as a necessary prerequisite for item-driven GST), and the two remaining "(₹)" column headers Phase 4.5 missed.
- Verification: `compileDebugKotlin`/`compileDebugUnitTestKotlin` clean throughout. New `Phase5TestSuite.kt` (33 tests) covers every category in the user's 5.21 checklist: GST math (CESS, per-rate, item-driven, Export/Exempt, company/FY isolation), Sale/Purchase (receivable/payable, inventory movement, COGS-eligible cost basis, invoice-number uniqueness), Credit/Debit Note (reversal correctness, GST netting to zero, original-voucher immutability), Settlement (full/partial/multi/advance allocation, zero GST reachable), Contra (accepted vs. engine-level rejection), Round Off (rounding math, voucher-balance preservation, Suspense-identical protection, distinctness from Suspense), and GST filing-period isolation from accounting-period locking. 3 pre-existing tests (`Phase0TestSuite` migration-count assertions, `Phase4TestSuite` GST-ledger-count assertion) had their hardcoded counts updated to reflect the new migration and CESS ledger - not weakened, just updated for the intentional scope growth. UI changes could not be visually verified/screenshotted in this environment.
- **Explicitly out of scope** (per the user's own boundary): Python API, PostgreSQL, public API auth, PDF/CSV/JSON export, Print/Share, final UI redesign. `gst_transactions`/`GSTSummaryReport` are structured so those future layers can consume clean domain output without further accounting-engine changes.

### Post-implementation independent audit (2026-08-22) - 2 real gaps found and fixed before freeze
Per the user's explicit instruction not to freeze on test-count alone, a second, independent read-only audit (fresh agent, no implementation context) verified 13 specific invariants against the actual source rather than trusting the test suite. 11 of 13 checked out clean (no GST math in the UI beyond a cosmetic preview, item lines genuinely reach posting, Note reversals never touch the original, Receipt/Payment structurally cannot reach GST, Round Off/Suspense stay distinct and unconflated, Contra is rejected at `VoucherPostingEngine` regardless of caller, GST filing periods have zero cross-reference into accounting-period/posting code, zero-balance ledgers hide uniformly including the new CESS/Round Off ledgers, no leaked internal terminology, the pre-existing dialog structure and Contra/Journal code paths were left untouched, and the GST transaction/report models are complete enough for a future export layer with no UI-side reconstruction needed). Two real gaps were found and fixed:
- **`allocateSettlement` could silently double-pay an invoice**: it validated only that a settlement voucher's own total balanced against its allocations - it never checked the target invoice's remaining outstanding. A second Receipt allocated in full against an already-fully-paid invoice would succeed with no error. Fixed by extracting a shared `computeOutstandingPaise(companyId, invoiceVoucherId)` (used by both `getOutstandingInvoices` and a new per-allocation guard in `allocateSettlement`) that rejects any allocation exceeding the invoice's actual remaining outstanding. New regression test `Phase5TestSuite.d7_Receipt_CannotOverAllocate_BeyondInvoiceOutstanding`.
- **`ComplianceCheckers.kt`'s monthly GST-readiness check still used name-matching** (`groupId.contains("DEBTORS"/"CREDITORS")`, `name.contains("GST", ...)`) - the exact anti-pattern the phase's own commit messages claimed was eliminated everywhere. Fixed to exact `StandardSystemGroups` groupId-prefix checks and exact `GstLedgerIds` ledger-ID matching. Low operational risk (an automation notification, not posting or return figures) but brought in line with the rest of the codebase.
- Also cleaned up one flagged dead-code redundancy in `ChartOfAccountsScreen.kt` (a `name.contains("Suspense", ...)` guard made redundant by the preceding `!ledger.isSystem` check - harmless but inconsistent with the identity-not-name discipline).
- Final verification after fixes: 170 tests, 167 passing (only the same pre-existing Robolectric trio fails), `Phase5TestSuite` now 34/34.

## Phase 6 - API, Online/Offline Synchronization & Server Architecture (2026-08-22, Part A implemented)
6.0 audit first (two passes): found the Android sync scaffolding more built than expected but disconnected (`OutboxProcessor` called a real Retrofit client but never `SyncEngine`'s retry-cap logic; `ApiClient` had Bearer/API-key/idempotency interceptors wired but pointed at an unresolvable placeholder host; `SecureStorage` supported token storage but nothing populated it); confirmed `OutboxSyncEntity.payloadJson` was exactly as narrow as suspected (4 hand-built ad-hoc strings, none carrying Phase 4/5 data); found the only backend artifact on this machine was an unrelated, mostly-empty, broken Node/Express/Prisma project (by explicit user decision, ignored entirely - Phase 6 builds a fresh Python backend). A second audit pass against the user's amended, much larger spec (Invoice-status/Document-Template-Engine/PDF/Print/Share/GSTR-JSON/Business-Branding) found none of that exists at all; by explicit user decision **Phase 6 = the API/Outbox/sync/Python/PostgreSQL/security architecture only ("Part A") - Part B becomes Phase 7.**

- **Android sync envelope rebuilt** (`domain/sync/SyncEvent.kt`, new): a versioned command/event (`schemaVersion`, `eventId`, `idempotencyKey`, `operation`, embedded `voucher`/`journalLines`/`stockLines`/`gstTransactions`/`settlements`/`ledger`) replaces the old bare `{"voucherNumber":...,"amount":...}` string at all 4 outbox-enqueue call sites in `DatabaseTransaction.kt`/`AccountingRepository.kt` - posting/validation/balance logic in those files is otherwise untouched, this only enriches what goes into `payloadJson`.
- **`SyncEngine`/`OutboxProcessor` reconnected**: the processor now calls `SyncEngine.recordFailure()` (previously duplicated the retry-increment logic itself with no cap, so a permanently-broken item retried forever) and gained a real timed exponential backoff (`2^n x 1000ms`, capped 60s, matching what `docs/21_SYNC.md` already claimed but no code implemented). Deleted the confirmed-dead `SyncModels.kt` (`OutboxItem`/`OutboxSyncStatus`/`SyncEngineResult` - zero real construction sites anywhere; one frozen Phase 0 test that used them was mechanically adapted to the real `OutboxSyncEntity`/`SyncState` types it was actually testing the intent of).
- **Auth, sync-gated not app-gated**: `IAuthService` got its first real implementation (`AuthRepository`) calling new `/api/v1/auth/{token,refresh,logout}` endpoints; tokens stored via the existing `SecureStorage`. A new optional "Cloud Sync" section in Settings lets the user log in - the app keeps working fully offline whether or not this is ever used. `ApiClient`'s placeholder unresolvable base URL replaced with the Android emulator's real loopback alias (`http://10.0.2.2:8000/api/v1/`), with a new `network_security_config.xml` permitting cleartext only to that specific alias.
- **`server/` (new)**: FastAPI + SQLAlchemy 2.0 async + Pydantic v2 + JWT + bcrypt + Alembic, layered exactly per spec (`api/{routes,dependencies}`, `domain/{errors,accounting}`, `application/{commands,queries,services}`, `infrastructure/{database,security}`, `schemas/`). PostgreSQL schema mirrors Room table-for-table plus server-only tables (`users`, `user_company_roles`, `refresh_tokens`, `idempotency_keys`); `0001_initial` Alembic migration. **Environment note**: no Docker/PostgreSQL available on this machine - the schema/migrations are Postgres-correct via SQLAlchemy's dialects, but automated tests run against SQLite (`StaticPool`-shared in-memory), documented the same way Android UI screenshots have been every phase.
- **Server-side accounting principles re-implemented independently** (`domain/accounting/`): double-entry balance, Contra Cash/Bank-only restriction, settlement over-allocation guard (mirroring `computeOutstandingPaise`), accounting-period lock - a deliberate Python re-implementation of the same rules, not a port of the Kotlin and not a redesign; the server never trusts a client-submitted total.
- **`/sync/outbox/batch` is the sole mutation path** Android's client code calls (`docs/27_SYNC_PROTOCOL.md`) - dispatched server-side by `operation` to command handlers (`voucher_commands.py` covers every `POST_*`/`CANCEL_VOUCHER` operation, since they share one shape; `ledger_commands.py` covers `CREATE/UPDATE/DELETE_LEDGER`, with the same Suspense/Round-Off/System-ledger protection Android enforces). One bad/conflicting item in a batch never blocks the rest - each item is independently transacted and independently reported back (`processedSyncIds` / `rejections`). Named business-level resources (`/sales-invoices`, `/purchase-bills`, etc.) exist as thin GET-only filters over `vouchers` by type - deliberately no POST variant, since Android never creates these directly (6.2/6.3's "never UI -> API -> wait -> accounting entry").
- **Structured error contract**: one `AppError` hierarchy mapping 1:1 to the user's code list (`TENANT_MISMATCH`, `PERIOD_LOCKED`, `DOUBLE_ENTRY_NOT_BALANCED`, `DUPLICATE_VOUCHER`, `OVER_ALLOCATION`, `INVALID_CONTRA`, `SYSTEM_LEDGER_PROTECTED`, `SUSPENSE_PROTECTED`, etc.), one FastAPI exception handler turning any of them into `{"code","message"}` at the right HTTP status.
- **Tenant isolation**: `require_company_access` never trusts a client-supplied `companyId` - checked against `user_company_roles` on every request; a brand-new `companyId` auto-grants `OWNER` to the first user who ever syncs it (bootstrap), after which every other user is `403 TENANT_MISMATCH`.
- **Docs**: new `docs/27_SYNC_PROTOCOL.md`, `docs/32_SERVER_ARCHITECTURE.md`, `docs/33_POSTGRESQL.md`, `docs/34_API_CONTRACTS.md` (numbered 34, not 31 - `docs/31_PHASE_4_5_UX_DOMAIN_CONSISTENCY.md` already occupies that slot); `docs/25_API_ARCHITECTURE.md`/`26_API_SECURITY.md`/`29_DEPLOYMENT.md` rewritten in place to describe what Part A actually built (their prior content was aspirational and never matched any code - Celery/Redis/Kubernetes/RBAC roles were never built).
- **Verification**: Android - `compileDebugKotlin`/`testDebugUnitTest` clean, 167/170 passing (same pre-existing Robolectric trio). Python - `pytest`, 27/27 passing, covering the user's full 6.17 checklist: auth (valid/expired/invalid/missing token, refresh rotation, logout revocation), tenant isolation (cannot read or mutate another company's data), idempotency (duplicate key doesn't reprocess, duplicate voucher number rejected), offline-sync batch semantics (multi-item batch acknowledgement, one bad item doesn't block the rest, acknowledged items are queryable), accounting rejections (unbalanced, locked period, invalid Contra, over-allocation, duplicate voucher, cancelled-voucher-can't-recancel), and security (unauthorized/forbidden/malformed payload/replay/missing idempotency key).
- **Explicitly out of scope** (Phase 7): Invoice/Voucher lifecycle status (Draft/Partially Paid/Paid), Document Template Engine, PDF/Print/Share, GSTR JSON export, Business/Individual Profile separation, Invoice Customization - none of it exists yet, confirmed by audit, none of it was built in this pass.

### Post-implementation independent audit (2026-08-22) - clean, Phase 6 (Part A) frozen
A second, independent read-only audit (fresh agent, no implementation context, mirroring the Phase 5 final-freeze audit) re-ran both test suites from scratch (didn't trust the prior report) and independently confirmed the exact same counts: Android 167/170, Python 27/27. All 15 checked invariants came back EXISTS-CORRECTLY with file:line evidence: no accounting logic was redesigned (only the outbox-enqueue payload changed), `SyncEvent` is built correctly at all 4 call sites, `SyncEngine`/`OutboxProcessor` are genuinely reconnected with `SyncModels.kt` fully gone, auth is structurally sync-gated (no code path anywhere gates a core accounting operation on `isLoggedIn`/network state), the server never trusts a client-submitted total, the `CANCEL_VOUCHER` fix is correct and complete, the tenant-isolation bootstrap rule cannot be exploited to hijack an existing company, idempotency is enforced at both the DB-constraint and application layers independently of the duplicate-voucher guard, no secrets are committed, passwords/refresh tokens are properly hashed (never plaintext), the Contra/Suspense/Round-Off prefix constants match byte-for-byte between the Kotlin and Python implementations, the new docs have no numbering collisions, and offline-first posting is structurally guaranteed (no network dependency anywhere in the posting path). Two non-blocking notes: 4 of 16 structured error codes (`FORBIDDEN`, `FINANCIAL_YEAR_MISMATCH`, `INVALID_GST`, `DUPLICATE_IDEMPOTENCY_KEY`) are declared but not yet raised anywhere (dead code until Phase 7's GST/multi-role features exist to trigger them); the repository has no git commit history yet, so this and future audits can only verify against the changelog narrative rather than a real diff until an initial commit exists.
- APK built (`assembleDebug`) and installed on a physically connected USB debug device.

## Phase 7A - Party + Invoice Domain Foundation (2026-08-22)

Read-only Phase 7 structural audit first (fresh agent, no implementation context, avoiding the
coordinator's own confirmation bias): confirmed Party/Customer/Supplier does not exist as a domain
concept (counterparties are plain `Ledger` rows), Invoice lifecycle status does not exist beyond
`isPosted`/`isCancelled`, and no payment-terms/due-date concept exists anywhere - but also found
unexpectedly mature reusable infrastructure: `VoucherType` already has 6 non-posting document types
pre-modeled (Quotation, Proforma, Sales/Purchase Order, Delivery/Receipt Note), `computeOutstandingPaise`/
`allocateSettlement` already compute exactly the outstanding-balance data an invoice-status
derivation needs, and the server's `vouchers.py` GET-only precedent gave a ready template for
business-level read APIs. The user's own architecture decision then sequenced the full remaining
scope as 7A (this phase) through 7J (UI, explicitly last, gated on 7A-7I being complete/tested/
documented/independently audited first) - see `docs/35_PARTY_INVOICE_DOMAIN.md` for the full design.

- **`Party`** (new, both sides): a thin 1:1 extension of an existing `Ledger` (never a
  replacement) - GSTIN/PAN/address/bank fields stay on `Ledger`; `Party` adds only `role`
  (`CUSTOMER`/`SUPPLIER`), `entityType` (`INDIVIDUAL`/`BUSINESS`), `creditLimitPaise`, and
  `paymentTerms`. A counterparty that is genuinely both a customer and a supplier gets two `Party`
  rows (one per role, each its own ledger), matching standard double-entry practice of not netting
  debtor/creditor tracking together.
- **`PaymentTerms`** (new, Android `domain/party/PaymentTerms.kt`): a small pure value type
  (`DUE_ON_RECEIPT`/`NET_7`/`NET_15`/`NET_30`/`NET_45`/`NET_60`/`CUSTOM`) with a single pure
  `dueDate(invoiceDate)` function. Every `Invoice` snapshots its own resolved due date at creation
  time so a later change to a party's terms never retroactively alters an already-issued invoice.
- **`Invoice`/`InvoiceLine`** (new, both sides): a genuinely separate pre-posting concept from
  `Voucher` - `VoucherPostingEngine` has no "draft" state, so a Sales Invoice/Purchase Bill/Credit
  Note/Debit Note now exists as an editable `DRAFT` (zero Ledger/JournalItem/Trial-Balance/P&L
  effect) before `postInvoice()` calls the **existing, unmodified** `TradingWorkflowEngine`/
  `postVoucher()` to create the real `Voucher`, then links `Invoice.voucherId` and copies the
  Voucher's own `voucherNumber` onto `Invoice.invoiceNumber` - no separate invoice-numbering
  sequence, so GST-required sequential numbering can never gap from an abandoned draft.
- **`InvoiceStatusEngine`** (new, Android `domain/invoice/InvoiceStatusEngine.kt`; server
  `domain/invoice/status.py` as an independent Python port, not a shared library, per the same
  "duplicate the principle, not the code" rule already applied to double-entry/Contra/allocation):
  `DRAFT`/`POSTED`/`PARTIALLY_PAID`/`PAID`/`OVERDUE`/`CANCELLED` are **always derived**, never a
  stored/mutable field - composed purely from the linked Voucher's `isCancelled`, the existing
  `computeOutstandingPaise`, and the Invoice's own snapshotted `dueDate`. No UI can ever
  "arbitrarily change accounting status" because no such settable field exists.
- **`LINK_INVOICE_VOUCHER` never touches the frozen posting path**: rather than threading an
  `invoiceId` through the existing voucher-posting sync event, `postInvoice()` enqueues a second,
  independent sync event carrying only `{invoiceId, voucherId, invoiceNumber}`, handled entirely by
  a new `invoice_commands.py` that only ever touches the `invoices` table. `apply_voucher_event`/
  `VoucherPostingEngine.post()`/`.cancel()` are byte-for-byte unmodified by this phase.
- **Sync/Outbox (purely additive)**: new `SyncOperation` entries (`CREATE_PARTY`, `UPDATE_PARTY`,
  `CREATE_DRAFT_INVOICE`, `CANCEL_DRAFT_INVOICE`, `LINK_INVOICE_VOUCHER`) and `SyncAggregateType`
  entries (`PARTY`, `INVOICE`); two new optional fields (`party`, `invoice`) on the `SyncEvent`
  envelope, both sides. Posting a Sales Invoice/Purchase Bill/Credit/Debit Note still produces
  exactly the `POST_SALES_INVOICE`/`POST_PURCHASE_BILL`/`POST_CREDIT_NOTE`/`POST_DEBIT_NOTE` event
  it already did via `VoucherType.toPostOperation()` - unchanged.
- **API (read-only, business-level, matching the existing `vouchers.py` precedent)**:
  `GET /api/v1/parties`, `GET /api/v1/invoices` (status computed inline via `derive_status`, never
  a stored field). No POST route added for either - mutation stays exclusively via the Outbox
  batch endpoint, consistent with the whole app's offline-first design.
- **Database (both sides purely additive)**: Android Room schema version 3 -> 4 (`MIGRATION_3_4`:
  three new tables - `parties`, `invoices`, `invoice_lines` - no existing table altered, dropped,
  or renamed); Python Alembic revision `0002` (`down_revision = "0001"`) mirrors the same three
  tables table-for-table.
- **Verification**: Android - `compileDebugKotlin`/`testDebugUnitTest` clean, 183/186 passing
  (same pre-existing Robolectric trio; +16 new tests in `Phase7ATestSuite.kt` covering every
  `InvoiceStatusEngine` state transition including OVERDUE/CANCELLED precedence, and every
  `PaymentTerms` due-date computation). Two pre-existing `Phase0TestSuite` migration-count
  assertions were updated (2 -> 3 migrations, version 3 -> 4) to reflect the intentional new
  migration - not weakened, just updated for the real scope growth, matching the same precedent
  Phase 5 already set for this exact kind of test. Python - `pytest`, 36/36 passing (27 existing
  Phase 6A tests unchanged + 9 new in `test_party_invoice.py` covering party creation as a thin
  ledger extension, a draft invoice's zero accounting effect, invoice<->voucher linking, partial/full
  settlement allocation moving status to `PAID`, voucher cancellation moving status to `CANCELLED`,
  an overdue-and-unpaid invoice, draft deletion, rejection of re-linking an already-posted invoice,
  and role-filtered party listing).
- **Explicitly out of scope** (later Phase 7 sub-phases, per the user's own sequencing): Document
  Templates/PDF/Print/Share (7D), Export/GSTR JSON (7E), Automation wiring for invoice reminders
  (7F, though the existing `AutomationTask` extension point was confirmed reusable by the audit),
  Business/Individual Profile branding (7G) - and no UI of any kind (7H/7J gate, not yet reached).

## Phase 7B - Document/Voucher Lifecycle Architecture (2026-08-22)

Full architecture-decision spec provided directly by the user (Party+Invoice foundation already
frozen from 7A). Extends 7A's Invoice with the remaining 6 non-posting trade-document types, a
typed document-relationship model, document conversion, and voucher/document numbering decoupling
- with zero UI and zero changes to `VoucherPostingEngine`/`apply_voucher_event`/
`TradingWorkflowEngine`, exactly as in 7A. See `docs/36_DOCUMENT_LIFECYCLE.md` for the full design.

- **`TradeDocument`/`TradeDocumentLine`** (new, both sides) - Quotation/Proforma Invoice/Sales
  Order/Purchase Order/Delivery Note/Receipt Note as a genuinely separate table from `invoices`
  (a rename/unification was considered and rejected - see the doc for why). Never creates any
  Ledger/JournalItem/Voucher row merely by existing. `DocumentStatus` (`DRAFT -> ISSUED ->
  CONVERTED -> CANCELLED`) is a real stored column here (unlike `InvoiceStatus`, always derived) -
  legitimate because a TradeDocument never touches accounting, so there's no risk of drifting from
  ledger reality.
- **Document relationships as reverse pointers**: every `TradeDocument`/`Invoice` carries one
  nullable `sourceTradeDocumentId` (no declared FK, same convention as
  `referenceVoucherId`/`referenceInvoiceId`). "What did X convert into" is answered by a reverse
  query, never a forward-pointing field that would need to stay in sync.
- **Document conversion never re-implements posting**: `convertTradeDocument` (TradeDocument ->
  TradeDocument) is a plain header+lines copy; `convertTradeDocumentToInvoice` (TradeDocument ->
  Invoice) calls the *existing, unmodified* `createDraftInvoice` (7A). Either way the source's
  status flips to `CONVERTED` via a second, independent event (`CONVERT_TRADE_DOCUMENT`) - the same
  "never thread new fields through the frozen path" discipline 7A established for
  `LINK_INVOICE_VOUCHER`.
- **One deliberate, explicit retrofit to already-frozen 7A code** (directed by this phase's own
  numbering requirement, not scope creep): `Invoice.invoiceNumber` used to be copied verbatim from
  the linked Voucher's own `voucherNumber` at posting time. Per the user's explicit instruction
  ("the accounting voucher number and user-facing invoice number should not be assumed to be the
  same identifier"), it is now assigned once, at DRAFT-creation time, via the new
  `generateNextDocumentNumber` (mirrors `generateNextVoucherNumber`'s exact shape, its own
  independent counter) - `postInvoice`'s link step now only ever sets `voucherId`. New,
  intentionally distinct prefixes for the 4 posting-document types (`SI-`/`PB-`/`CN-`/`DN-` vs. the
  Voucher's own `INV-`/`PUR-`/`CRN-`/`DRN-`); the 6 non-posting prefixes reuse `VoucherType`'s
  existing values verbatim (established in Phase 5, never used for an actual number until now).
- **Sync/Outbox (purely additive)**: new `SyncOperation` entries (`CREATE_TRADE_DOCUMENT`,
  `ISSUE_TRADE_DOCUMENT`, `CONVERT_TRADE_DOCUMENT`, `CANCEL_TRADE_DOCUMENT`), new
  `SyncAggregateType.TRADE_DOCUMENT`, new optional `tradeDocument` field on `SyncEvent` plus a new
  optional `sourceTradeDocumentId` on the existing `invoice` field, both sides. Every 7A/6A
  operation, branch, and field is unchanged.
- **API**: `GET /api/v1/trade-documents` (mirrors `parties.py`'s filtered-list pattern) plus real
  mutating `POST /api/v1/{quotations,proforma-invoices,sales-orders,purchase-orders,delivery-notes,receipt-notes}`
  routes, each calling `apply_trade_document_event` directly with its own idempotency-key handling
  - matching Phase 6A's original design intent (business-level routes for future non-Android
  callers, calling the *same* command handlers Outbox dispatch uses). Android's own client
  continues to exclusively use the Outbox batch endpoint for every mutation - offline-first
  guarantee unchanged. Invoice/Sales-Invoice/Purchase-Bill/Credit-Debit-Note stay GET-only
  (unchanged from 7A).
- **Database (both sides purely additive)**: Android Room schema version 4 -> 5 (`MIGRATION_4_5`:
  `trade_documents`/`trade_document_lines` tables + one new nullable column on `invoices`); Python
  Alembic revision `0003` (`down_revision = "0002"`) mirrors the same.
- **Verification**: Android - `compileDebugKotlin`/`testDebugUnitTest` clean, 196/199 passing (same
  pre-existing Robolectric trio; +13 new tests in `Phase7BTestSuite.kt`, zero regressions in
  `Phase7ATestSuite.kt`). Python - `pytest`, 45/45 passing (36 existing + 9 new in
  `test_trade_documents.py`); one pre-existing Phase 7A test (`test_party_invoice.py`'s
  `test_post_invoice_and_link_produces_posted_status`) had its stale assertion updated to match the
  new decoupled-numbering behavior - not weakened, just updated for the intentional scope growth,
  the same precedent already set twice before in this project (Phase 5's/Phase 7A's migration-count
  assertions).
- **Explicitly out of scope** (later Phase 7 sub-phases): Report Management (7C), Document Template
  Engine/PDF/Print/Share (7D), Export/GSTR JSON (7E), Automation wiring (7F), Business/Individual
  Profile branding (7G) - and no UI of any kind (7H/7J gate, not yet reached).

## Phase 7C - Report Management (2026-08-22)

Full architecture-decision spec and a detailed elaboration prompt provided directly by the user
(Party+Invoice/Document lifecycle already frozen from 7A/7B). Builds Trial Balance/P&L/Balance
Sheet/GST Summary/Ledger Statement (already correct on Android, ported to Python for the first
time), plus three genuinely new reports - Day Book, Outstanding/Receivables/Payables, Cash Flow,
Ratio Analysis - with zero UI and zero changes to any posting/GST/inventory/settlement engine. See
`docs/37_REPORT_ARCHITECTURE.md` (shared rules), `docs/38_REPORTS.md`,
`docs/39_OUTSTANDING_REPORTS.md`, `docs/40_CASH_FLOW.md`, `docs/41_RATIO_ANALYSIS.md`.

- **Group hierarchy ported to Python** (`server/app/domain/reports/group_aggregation.py`, new) - a
  faithful port of Android's `GroupAggregationEngine` (same cycle-detection, same bottom-up
  algorithm) - unlocking correct `profit_and_loss`/`balance_sheet` on the server for the first
  time, since P&L/Balance Sheet structurally require group-hierarchy classification.
- **Trial Balance/GST Summary extended additively only** - every pre-existing field keeps its exact
  prior meaning (existing `test_idempotency.py` assertion on `row["debitPaise"]` untouched). New:
  per-row `groupId`/opening-and-closing debit-credit splits; top-level opening/closing totals and
  `groupHierarchy` (Trial Balance); `netTaxPayablePaise`/`netCessPayablePaise` (GST Summary).
- **Day Book** becomes a real domain report (`DayBookReport`/`DayBookRow`,
  `generateDayBook`/`day_book()`), extracted from `DayBookScreen.kt`'s ad-hoc client-side filtering
  without touching the screen - explicitly surfaces `POSTED`/`CANCELLED` status per row.
- **Outstanding/Receivables/Payables** - one shared, Party-aware report
  (`generateOutstandingReport`/`outstanding_report()`) reusing 7A's frozen
  `computeOutstandingPaise`/`InvoiceStatusEngine.deriveStatus` verbatim - zero new ledger math.
  Aging buckets (`CURRENT`/`1-30`/`31-60`/`61-90`/`90+`) computed once in the domain/query layer.
  Python DRY cleanup: `invoices.py`'s locally-duplicated `_compute_outstanding_paise` now imports
  the shared `compute_outstanding_paise` from `application/queries/reports.py` (behavior-preserving,
  does not touch `voucher_commands.py`).
- **Cash Flow** (`generateCashFlow`/`cash_flow()`) - Operating Activities only via the standard
  indirect method (Net Profit adjusted for period change in Current Assets excl. cash and Current
  Liabilities, both from `generateBalanceSheet`/`balance_sheet()` at the period's start/end).
  Investing/Financing are explicit, documented `null` extension points, not fabricated - no
  Fixed-Asset/Loan transaction categorization exists in the domain to attribute cash movement
  correctly. See `docs/40_CASH_FLOW.md`.
- **Ratio Analysis** (`RatioAnalysisEngine.compute`/`ratio_analysis()`) - Current/Quick/Debt-Equity
  Ratio, Gross/Net Profit Ratio %, Operating Ratio %, Return on Capital Employed % - a pure function
  over already-generated `BalanceSheetReport`/`ProfitAndLossReport`, safe division by zero. Actual
  vs. future Projected/Estimated (CMA) is documented as an extension point, not implemented.
- **Two real calculation bugs found and fixed during testing** (both stemmed from the same root
  cause): `BalanceSheetReport.currentAssets`/`currentLiabilities` are *residual* buckets (the named
  line items - Debtors/Bank/Cash/Stock, Duties & Taxes - are subtracted out for separate display),
  not true totals. Ratio Analysis's Current/Quick Ratio and Cash Flow's working-capital-change both
  initially used them as if they were totals, silently producing wrong numbers with no error caught
  by hand-computed tests. Both fixed, on both platforms, to reconstruct true totals by adding the
  named buckets back in - see `docs/40_CASH_FLOW.md`/`docs/41_RATIO_ANALYSIS.md` for the exact
  formulas and in-code comments documenting the reasoning for future consumers of
  `BalanceSheetReport`.
- **One test-infrastructure gap found and fixed** (Android only, no production code affected):
  `Phase7CTestSuite`'s fake DAO added real backing for `settlement_allocations` but not `Group` -
  the base `FakeAccountingDao`'s group methods are permanent no-op stubs by design (each phase's
  suite only pays for the entities it needs) - so the Cash Flow test initially saw an empty group
  hierarchy and computed a `0` net profit. Fixed by adding the same `GroupAwareDao`-style overrides
  `Phase3TestSuite` already established. Python's equivalent tests always seeded `Group` rows
  explicitly and were unaffected.
- **API**: `GET /api/v1/reports/{profit-loss,balance-sheet,ledger,day-book,outstanding,receivables,
  payables,cash-flow,ratios}` added; `/trial-balance` and `/gst` gained optional
  `startDate`/`endDate` params. All GET-only, all `require_company_access`-gated, all read-only -
  reports carry no mutation risk, so no offline-first tension applies here.
- **Verification**: Android - `compileDebugKotlin`/`testDebugUnitTest` clean, 207 tests completed
  (same pre-existing Robolectric trio; +8 new tests in `Phase7CTestSuite.kt`, zero regressions
  elsewhere). Python - `pytest`, 56/56 passing (45 existing + 10 new in `test_reports_7c.py` + 1 new
  in `test_tenant_isolation.py`). `test_reports_7c.py`'s own report-route test is a plain
  401-without-auth-header check across all 9 new routes (confirming each route actually declared
  its auth dependency); direct cross-tenant (403 `TENANT_MISMATCH`) coverage for all 9 new routes
  was added as `test_company_a_cannot_read_company_b_via_new_report_routes` in the project's
  canonical `test_tenant_isolation.py`, alongside its pre-existing `/trial-balance` case.
- **Post-freeze-audit cleanup** (found by the independent 7C audit, fixed before final freeze):
  removed a Kotlin-only `financialYearId` parameter from `generateOutstandingReport`/
  `generateReceivablesReport`/`generatePayablesReport` that was never read by the function body -
  a leftover, not an intentional FY-scope (outstanding is deliberately lifetime/company-wide on
  both platforms; Python's `outstanding_report()` never had this parameter). Corrected
  `docs/39_OUTSTANDING_REPORTS.md` to accurately describe the actual `daysOutstanding = 0`
  (bucketed `CURRENT`) behavior when an invoice has no due date, rather than changing the
  calculation to match an earlier, inaccurate doc claim of an invoice-date fallback. Added the
  direct cross-tenant test described above. All three fixes independently re-verified by a second
  audit pass; no calculation behavior changed as a result of any of them.
- **Explicitly out of scope** (later Phase 7 sub-phases): Document Template Engine/PDF/Print/Share
  (7D), Export/GSTR JSON (7E), Automation wiring (7F), Business/Individual Profile branding (7G) -
  and no UI of any kind (7J gate, not yet reached).

## Phase 7D - Document Template & Rendering Architecture (2026-08-22)

Full master implementation spec provided directly by the user (Report Management already frozen
from 7C). Builds the foundation for invoice/document templates, PDF, print, share, and branding -
zero UI, zero changes to any posting/GST/inventory/settlement engine. See
`docs/42_DOCUMENT_TEMPLATE_ARCHITECTURE.md`, `docs/43_DOCUMENT_RENDERING.md`,
`docs/44_PDF_PRINT_SHARE.md`, `docs/45_DOCUMENT_BRANDING.md`.

- **`DocumentData`/`DocumentLineData`/`DocumentTotals`/etc.** (`domain/rendering/DocumentData.kt`,
  new) - a Compose/PDF-library-independent representation, assembled entirely by
  `AccountingRepository.assembleDocumentData` (Android) / `document_service.assemble_document_data`
  (Python), the only functions in this phase that read Invoice/TradeDocument/GstTransaction/Party/
  Ledger/Company/BusinessProfile data. A posted Invoice's tax/total figures come straight from the
  already-persisted `GstTransaction`/`Voucher` rows; a draft Invoice or non-posting TradeDocument
  (neither ever has a `GstTransaction`) calls the existing, unmodified
  `GstCalculationEngine.calculateDetailed` - never reimplemented.
- **Python's assembly is deliberately narrower than Android's** - the server has no GST calculation
  engine of its own (GST math has always been Android-only; `voucher_commands.py` only ever
  assigns already-computed tax figures straight through). `assemble_document_data` supports POSTED
  invoices only; a draft Invoice or non-posting TradeDocument raises an explicit
  `DOCUMENT_PREVIEW_NOT_AVAILABLE` (409) rather than duplicating GST math server-side or
  fabricating a result - a documented extension point, not a silent gap.
- **`DocumentTemplate`** (new, both sides) - company-scoped, versioned (`templateId` groups every
  version of one lineage; editing creates a new version and archives the previous one, which is
  never mutated again - `TemplateVisualConfig` persisted as one JSON blob, same convention as
  `OutboxSyncEntity.payloadJson`). `RenderedDocumentRecord`/`rendered_document_records` (new) logs
  which exact template version rendered a document, without adding any field to the frozen
  `Invoice`/`TradeDocument` entities - what keeps a historical render reproducible after a later
  template edit. `DocumentTemplate.builtinDefault` guarantees a document is always renderable
  before a company configures its own template.
- **`BusinessProfile`/`IndividualProfile`/`DocumentAsset`** (new, both sides) - document-branding
  identity kept structurally separate from `Company`'s authoritative statutory fields; a
  `DocumentAsset` stores only a storage reference to a logo/signature/QR image, never the binary
  bytes themselves (Section 8). The 7D-vs-7G ownership boundary for these fields, and whether
  `narration` or a distinct terms field should carry a document's "Terms & Conditions," are both
  documented open questions (`docs/45_DOCUMENT_BRANDING.md`), not silently decided.
- **Renderer boundary (Section 14)**: `domain/rendering/JsonDocumentRenderer.kt`/`CsvExporter.kt`
  are pure Kotlin (no Android/PDF dependency); `data/rendering/PdfDocumentRenderer.kt` (Android's
  built-in `android.graphics.pdf.PdfDocument`, no new library added)/`PrintAdapter.kt`/
  `ShareAdapter.kt` live in the `data` layer specifically because they need an Android `Context` -
  the accounting/rendering domain never imports a PDF/Android-framework type. None of these classes
  are wired into any screen. PDF/Print byte-generation itself is not exercised by
  `Phase7DTestSuite` (a documented Robolectric-environment limitation, same root cause as the 3
  pre-existing known failures) - everything upstream of it (assembly, template resolution, output
  path) is fully unit-tested.
- **New `FileProvider`** (`AndroidManifest.xml`/`res/xml/file_paths.xml`, new) - lets `ShareAdapter`
  expose a generated PDF via a `content://` URI, never a raw `file://` path. Manifest/resource
  infrastructure only, not a UI change.
- **Scope cut, explicitly documented**: templates/profiles/assets are NOT synced via the Outbox in
  this phase - Android creates/edits them locally; Python independently exposes its own persistence
  + routes (for company-isolation testing and a future non-Android caller). Full Outbox wiring is a
  documented future extension, following the exact pattern 7A/7B/7C already established.
- **API**: `GET /api/v1/documents/{id}`, `GET /api/v1/documents/{id}/json`, `GET
  /api/v1/documents/{id}/pdf` (501 - not available server-side), `GET/POST/PUT /api/v1/templates`,
  `GET/PUT /api/v1/business-profile`, `GET/PUT /api/v1/individual-profile`, `POST/GET
  /api/v1/document-assets` - all thin, all `require_company_access`-gated, none going through
  Outbox/SyncEvent (see scope cut above).
- **Database (both sides purely additive)**: Android Room schema version 5 -> 6 (`MIGRATION_5_6`:
  five new tables, no existing column/table altered); Python Alembic revision `0004`
  (`down_revision = "0003"`) mirrors the same five tables.
- **Verification**: Android - `compileDebugKotlin`/`testDebugUnitTest` clean, 225 tests completed
  (same pre-existing Robolectric trio; +18 new tests in `Phase7DTestSuite.kt`, zero regressions
  elsewhere). Two pre-existing `Phase0TestSuite` migration-count assertions were updated for the
  version 5->6 bump (same precedent already set at every prior schema-version phase). Python -
  `pytest`, 66/66 passing (56 existing + 10 new in `test_documents_7d.py`) - two of the new tests
  initially asserted the wrong HTTP status for a cross-tenant scenario (expected 403
  `TENANT_MISMATCH` for a case that correctly returns 400 `VALIDATION_ERROR`, since the lookup is
  already company-scoped) and were corrected to test the real `TENANT_MISMATCH` path separately
  from the scoped-miss path, not weakened.
- **Post-freeze-audit hardening** (found by the independent 7D audit as a minor, non-blocking
  observation, fixed before final freeze): `updateBusinessProfile`/`updateIndividualProfile`
  (`AccountingDao.kt`) used Room's plain `@Update` (keyed only by primary key) rather than an
  explicit `companyId`-scoped query - not a live vulnerability (the repository always resolves the
  row via a `companyId`-filtered read first), but less defense-in-depth than every other new query
  in this phase. Both now use an explicit `@Query` with individually-named parameters and `WHERE
  companyId = :companyId AND ...ProfileId = :...ProfileId` (Room in this project's version doesn't
  support `:entity.field` POJO-property binding, so the whole-entity parameter was replaced with
  one parameter per column rather than that shorthand).
- **Explicitly out of scope** (later Phase 7 sub-phases): Export/GSTR JSON (7E - confirmed
  structurally distinct from this phase's PDF/document concern, no existing code conflates them),
  Automation wiring (7F), Business/Individual Profile branding (7G, boundary with this phase's
  profiles left as an open question) - and no UI of any kind (7J gate, not yet reached).

## Phase 7E - Export Architecture & Data Interchange (2026-08-23)

Full master implementation spec provided directly by the user (Document Template & Rendering
already frozen from 7D). A read-only structural audit ran first and found Phase 7C's report models
and Phase 7D's `DocumentData` already close to export-DTO shape - this phase built thin wrappers
over that existing output plus the genuinely new pieces (envelope, generic CSV engine, GSTR JSON,
Voucher/Party/Ledger export). Zero UI, zero changes to any posting/GST/report-generation engine.
See `docs/46_EXPORT_ARCHITECTURE.md` through `docs/49_GSTR_JSON_EXPORT.md`.

- **`domain/export/` (Android, new)** / **`application/services/export_service.py` +
  `domain/export/` (Python, new)** - `ExportFormat`/`ExportType`/`ExportFormatSupport`/
  `ExportMetadata`/`ExportRequest`/`ExportResult`, ten export DTOs (`VoucherExportDto`,
  `PartyExportDto`, `LedgerExportDto`, `InvoiceExportDto`, `TrialBalanceExportDto`,
  `ProfitAndLossExportDto`, `BalanceSheetExportDto`, `OutstandingExportDto`, `GSTSummaryExportDto`,
  `GSTTransactionExportDto`), the versioned JSON envelope (`ExportJsonSerializer`/
  `json_envelope.py`), a generic RFC-4180 CSV engine (`CsvEngine`/`csv_engine.py`, distinct from and
  never modifying Phase 7D's frozen, document-line-specific `CsvExporter`), and a GSTR JSON
  serializer (`GstrJsonSerializer`/`gstr_json.py`) built strictly from `GstTransaction` facts -
  never `ledgerName.contains("CGST")`-style string guessing, confirmed absent everywhere in this
  codebase by the pre-implementation audit.
- **Every DTO mapper is read-map-serialize only** - `AccountingRepository.kt`'s `toExportDto()`
  extensions and `export_service.py`'s re-shaping functions call the existing, unmodified
  `generateTrialBalance`/`generateProfitAndLoss`/`generateBalanceSheet`/`generateOutstandingReport`/
  `generateGSTSummary`/`assembleDocumentData` (and their Python `report_queries`/`document_service`
  equivalents) - no export function recomputes a single figure.
- **Exact paise-integer monetary precision everywhere** - every monetary field is named `...Paise`
  and is always a `Long`/`int`, never `.toRupeesDouble()`/a float, in JSON, CSV, or GSTR JSON alike.
- **HSN vs. SAC (goods vs. service) is a documented extension point, not a guess** -
  `GSTTransactionExportDto.isService: Boolean? = null` always `null` today, since no domain model
  anywhere (`StockItem`/`InvoiceLine`/`TradeDocumentLine`/`GstTransaction`/`Ledger`, confirmed by
  the pre-implementation audit) stores a goods/service classification - never fabricated from the
  HSN/SAC code string itself.
- **New `AppError`/`errors.py` codes**: `EXPORT_FORMAT_UNSUPPORTED`, `EXPORT_SCHEMA_UNSUPPORTED`,
  `RESOURCE_NOT_FOUND` (both platforms); `INVALID_FINANCIAL_YEAR` (Python only - Kotlin already had
  an equivalent `AppError.InvalidFinancialYear`, a platform asymmetry the audit surfaced).
- **API** (Python): `GET /api/v1/exports/{vouchers,parties,ledgers,invoices}/{id}`, `GET
  /api/v1/exports/reports/{trial-balance,profit-loss,balance-sheet,outstanding}`, `GET
  /api/v1/exports/gst`, `GET /api/v1/exports/gst/transactions` - all thin, all GET-only, all
  `require_company_access`-gated, `?format=json|csv|gstr-json` (an unsupported combination for a
  given export type returns `EXPORT_FORMAT_UNSUPPORTED`, never a silently-wrong serialization).
- **Real bug found and fixed during testing**: Moshi's default `Map<String, Any?>` JSON adapter
  silently omits a key entirely when its value is `null`, rather than writing `"key":null` - caught
  by a test asserting `isService` appears (as `null`) in GSTR JSON output. Fixed by adding
  `.serializeNulls()` to both `ExportJsonSerializer` and `GstrJsonSerializer` - every export DTO's
  JSON now carries a stable, complete key set regardless of which fields are null. (Python's JSON
  serialization was unaffected - `None` already serializes to `null` by default there.)
- **Test-only fixes found and applied along the way**: a Kotlin JVM-signature clash (`List<T>`
  extension functions on `PartyExportDto`/`LedgerExportDto`/`GSTTransactionExportDto` erasing to the
  same JVM method signature) was resolved by renaming to distinct function names
  (`toPartyCsvHeaders`, `toLedgerCsvHeaders`, `toGstTransactionCsvHeaders`, etc.) - a compile-time
  fix, no behavior change. A latent Phase 7D test gap was also found and fixed while building this
  phase's own GST-transaction-backed test fixtures: `Phase7DTestSuite`'s fake DAO never gave real
  backing to `insertGstTransactions`/`getGstTransactionsForVoucher` (the base `FakeAccountingDao`'s
  versions are permanent no-op/empty-list stubs), so its posted-invoice test was accidentally
  passing by coincidence (the hand-picked fixture values happened to match what
  `GstCalculationEngine` would have computed anyway) rather than genuinely exercising the
  "read from `GstTransaction`, never recompute" code path it claimed to test. Fixed by adding real
  `GstTransaction` backing to `Phase7DTestSuite`'s fake DAO - test-only, no production code changed,
  and the existing assertions still pass unmodified (now for the right reason).
- **Verification**: Android - `compileDebugKotlin`/`testDebugUnitTest` clean, 241 tests completed
  (same pre-existing Robolectric trio; +16 new tests in `Phase7ETestSuite.kt`, zero regressions
  elsewhere). Python - `pytest`, 81/81 passing (66 existing + 15 new in `test_exports_7e.py`).
- **Explicitly out of scope** (later Phase 7 sub-phases): PDF/XML/Excel/Tally/bank/CMA export
  formats (documented `ExportFormat` extension points, Section 28), OCR (Section 29 - never becomes
  direct accounting posting), Automation wiring (7F), Business/Individual Profile branding (7G) -
  and no UI of any kind (7J gate, not yet reached).

## Profile Application Service & Data Masking (2026-08-23)

A discrete, user-requested hardening task on top of Phase 7D's frozen `BusinessProfile`/
`IndividualProfile` domain models (`domain/rendering/BusinessProfile.kt`, untouched) - not a
numbered phase, no independent freeze audit, but built and tested with the same rigor.

- **`application/profile/ProfileApplicationService.kt`** (new) - an application-service layer over
  `AccountingRepository`'s existing `getBusinessProfile`/`getIndividualProfile`/
  `upsertBusinessProfile`/`upsertIndividualProfile`. Every method takes an explicit
  `contextCompanyId` and asserts it matches the resource's own `companyId`, throwing a new
  `TenantMismatchException` (`application/profile/TenantMismatchException.kt`) on any mismatch -
  defense-in-depth above the DAO's own company-scoped `WHERE` clauses, fail-loud rather than a
  silent null/wrong result.
- **`application/profile/SensitiveDataMasker.kt`** (new) - masks PAN (`"ABCDE1234F"` ->
  `"ABCDE****F"`), GSTIN (`"27AAAAA0000A1Z5"` -> `"27AAA********Z5"`), and bank account numbers
  (reveals only the last 4 digits, the standard banking convention) for any log line or generic
  summary/list endpoint. `MaskedBusinessProfileSummary`/`MaskedIndividualProfileSummary` DTOs and
  `toLogSafeString()` extensions give callers a masked-by-construction alternative to ever
  string-interpolating the raw sensitive fields.
- **Zero accounting side effects, verified directly**: a test posts several profile mutations and
  asserts the company's ledger rows (every field, not just balance) are byte-identical before and
  after - profile mutations touch only the `business_profiles`/`individual_profiles` tables.
- **Verification**: Android - `compileDebugKotlin`/`testDebugUnitTest` clean, 254 tests completed
  (same pre-existing Robolectric trio; +13 new tests in `ProfileApplicationServiceTestSuite.kt`,
  zero regressions elsewhere). Python untouched - this was a Kotlin-only request.

## Business Profile Hardening: Constitution Type, Structured Identifiers, Capital Account Naming (2026-08-23)

Another discrete, user-requested extension of Phase 7D's frozen `BusinessProfile`/
`IndividualProfile` - purely additive, no existing field renamed/removed/repurposed.

- **`ConstitutionType`** (new enum, `domain/rendering/BusinessProfile.kt`) -
  PROPRIETORSHIP/PARTNERSHIP/LLP/PRIVATE_LIMITED/PUBLIC_LIMITED/HUF/TRUST/SOCIETY/OTHER. New
  `BusinessProfile.constitutionType` (defaults to `PROPRIETORSHIP`, so every profile row created
  before this change keeps behaving identically), plus new `tan`/`udyam` fields alongside the
  existing `gstin`/`pan`. `businessName`/`legalName` are now explicitly documented as the trade
  name / legal name pair the user asked for (no redundant new field needed - `businessName` already
  carried exactly that meaning since its Phase 7D introduction).
- **`domain/rendering/BusinessIdentifiers.kt`** (new) - structured, validated wrapper types
  (`Pan`, `Gstin`, `Tan`, `Udyam`), each with a `from(raw)` factory (returns `null`, never throws,
  on a malformed/blank input) and an `isValid` predicate. Constructed on demand from the plain,
  authoritative stored strings - never a second, independently-persisted copy of an identifier, and
  the existing `pan: String`/`gstin: String` fields keep their exact prior shape so every Phase
  7D/7E consumer (`assembleDocumentData`, the export DTOs) that already reads them as plain strings
  is completely unaffected.
- **`domain/rendering/CapitalAccountNaming.kt`** (new) - `resolveCapitalAccountName(constitutionType,
  personName)`: a Proprietorship's capital account is always exactly `"Capital - <personName>"`
  (throws `IllegalArgumentException` if the name is blank - a proprietorship's capital account is
  meaningless without one); Private/Public Limited (the only constitutions with real issued share
  capital) get `"Share Capital Account"`; every other constitution gets the generic
  `"Capital Account"` - never "Share Capital" outside the two corporate types. Pure naming logic
  only - it does not create, rename, or post to any ledger; wiring it into an actual Chart-of-
  Accounts setup flow is an explicit, documented future integration point, not built now (would
  touch Phase 0's frozen `createLedger` path, out of scope for this change).
- **Database (additive only)**: Android Room schema version 6 -> 7 (`MIGRATION_6_7`: three new
  columns on `business_profiles` - `constitutionType` defaulted to `'PROPRIETORSHIP'`, `tan`/`udyam`
  defaulted to `''` - no existing column altered/dropped/renamed, no existing row rewritten).
- **Verification**: Android - `compileDebugKotlin`/`testDebugUnitTest` clean, 265 tests completed
  (same pre-existing Robolectric trio; +11 new tests in `BusinessProfileHardeningTestSuite.kt`,
  zero regressions elsewhere - including the pre-existing migration-count assertions, updated for
  the version 6->7 bump per the same precedent every prior schema-version phase already set).
  Python untouched - this was a Kotlin-only request.

## Phase7GTestSuite.kt - Targeted Profile-Layer Verification (2026-08-23)

A consolidated, user-requested JUnit 4 suite exercising the profile layer with realistic mock data
(real GST state codes, ordinary B2B invoice amounts) rather than round test placeholders - no
production code changed, test-only.

- **Zero ledger leakage from a profile edit**: posts a realistic ₹85,000 cash sale (Dr Cash/Cr
  Sales), snapshots every ledger and the voucher's journal items, edits only `BusinessProfile`
  display fields (trade name/address/phone/email/website), and asserts every ledger row and every
  journal item is byte-identical before and after - plus a direct sanity check that Cash correctly
  reflects the posted sale (confirming the "before" snapshot wasn't itself a no-op).
- **GST supply-type classification accuracy**: `GSTRules.determineSupplyType`/`GstCalculationEngine.calculate`
  exercised directly with real state codes (Maharashtra `"27"`, Karnataka `"29"`, Delhi `"07"`) -
  matching codes -> `INTRA_STATE` (equal CGST+SGST split), mismatching -> `INTER_STATE` (single
  IGST line), verified against the exact paise figures a ₹85,000-at-18% invoice produces
  (₹7,650 CGST + ₹7,650 SGST intra-state; ₹15,300 IGST inter-state).
- **Cross-tenant failure is immediate**: a second company ("Deccan Traders") attempting to write
  "Bharat Textiles"'s profile gets `TenantMismatchException` before any write lands - confirmed by
  checking the attacking company still has no profile row afterward - plus a read-isolation test
  confirming each company only ever sees its own profile.
- **Verification**: Android - `compileDebugKotlin`/`testDebugUnitTest` clean, 272 tests completed
  (same pre-existing Robolectric trio; +7 new tests in `Phase7GTestSuite.kt`, zero regressions
  elsewhere).

## Phase 7F - Automation Architecture (2026-08-23)

Full scope decision provided directly by the user: build A (Invoice & Compliance Reminders), B
(Recurring Voucher Engine), C (Real Scheduling Infrastructure); defer D (Auto-Categorization); both
safety items resolved via "Option X" (FY closing rejected from automation entirely; bank
reconciliation quarantined to read-only suggestions). Android-only - the scope prompt is entirely
Kotlin/Android (`SchedulerPort`, `VoucherPostingEngine`, `DoubleEntryValidator`). Extends the
pre-existing `automation/` package additively. Zero UI. See `docs/50_AUTOMATION_ARCHITECTURE.md`.

- **Two live safety violations fixed, not just avoided going forward**: `YearlyOpeningBalanceGenerationTask`
  called `AccountingRepository.closeFinancialYear` directly from automation - removed entirely and
  replaced with `YearlyClosingReminderTask` (`automation/jobs/YearlyJobs.kt`), which depends only on
  `AccountingDao`, never `AccountingRepository`, so it is structurally incapable of reaching
  `closeFinancialYear`; it only emits a `YEAR_END_ALERT` reminder once every period is locked.
  `DailyUnreconciledCheckTask`'s name/doc comment claimed to detect bank/cash vouchers missing a
  reference number but actually read unrelated outbox-sync items - rewritten to genuinely do what
  it claims (Payment/Receipt/Contra vouchers with a blank `referenceNumber`), strictly read-only/
  notification-only; no bank-feed-import or auto-match capability exists anywhere in this codebase.
- **A - `InvoiceReminderChecker`** (`automation/compliance/InvoiceReminders.kt`) + `DailyInvoiceReminderTask`
  - calls the existing, unmodified `generateOutstandingReport` (Phase 7C) and classifies rows as
  overdue or due-within-3-days; recalculates nothing, wired into the daily cycle.
- **B - Recurring Voucher Engine, draft-first** - `domain/recurring/RecurringVoucherSchedule.kt`
  (schedule/line models, `RecurringVoucherPeriod.periodKeyFor`/`isDue` pure logic), four new
  additive Room tables (`recurring_voucher_schedules`, `recurring_voucher_lines`,
  `recurring_voucher_drafts`, `recurring_voucher_draft_lines`, migration 7->8, schema version
  7->8). **Corrected mid-phase**: the user identified that auto-posting a recurring voucher is
  unsafe for LedgerPrime's target users, who may not understand Dr/Cr well enough to catch a
  mistake after the fact - so `AccountingRepository.generateRecurringVoucherIfDue` now only ever
  produces a review-only `RecurringVoucherDraft` (Generated Draft), never a posted voucher; it
  never calls `postVoucher` and a generated draft has zero journal/ledger/balance/GST/inventory
  effect. The user then Reviews / Edits (`updateRecurringVoucherDraft`) / Discards
  (`discardRecurringVoucherDraft`, terminal, no accounting effect) / or Posts
  (`postRecurringVoucherDraft` - the **only** function anywhere that turns a draft into an actual
  voucher, callable only in direct response to an explicit user action) - which builds a plain
  `Voucher` and calls the existing, unmodified `postVoucher` (same `DoubleEntryValidator`/
  period-lock/atomic-transaction path as every other voucher - no second posting mechanism).
  **Idempotency**: checks the `(scheduleId, periodKey)` unique composite index on
  `recurring_voucher_drafts` before ever inserting a draft - a monthly cycle run twice (or
  retried) cannot propose the same rent/depreciation voucher twice, and since draft rows are never
  deleted (only status-transitioned to POSTED/DISCARDED), a period already decided on is never
  re-proposed either. `MonthlyRecurringVoucherGenerationTask` wires draft generation into the
  monthly cycle - it never posts.
- **C - Real Scheduling Infrastructure** - before this phase, `AccountingScheduler`'s job runners
  existed but nothing ever called them. New `SchedulerPort` interface + `WorkManagerSchedulerPort`
  (`androidx.work:work-runtime-ktx`, new dependency) enqueue one unique daily `PeriodicWorkRequest`
  per company (idempotent scheduling via `ExistingPeriodicWorkPolicy.KEEP`). Since WorkManager's
  fixed-interval periodic APIs cannot express "once per calendar month/year," a pure, unit-tested
  `AutomationRunGate.decide` function - given today's date and each cycle's last-run date - decides
  which of Daily/Monthly/Yearly are actually due; `AutomationCycleWorker` is the thin adapter that
  reads that decision and calls the existing, unmodified `AccountingScheduler.runDailyJobs`/
  `runMonthlyJobs`/`runYearlyJobs`. Triggered from `AccountingViewModel.observeFinancialYearData`
  (the same place the app already establishes its active company + financial year).
- **Database (additive only)**: Android Room schema version 7 -> 8 (`MIGRATION_7_8`: four new
  tables only, no existing table altered).
- **Verification**: Android - `compileDebugKotlin`/`compileDebugUnitTestKotlin`/`testDebugUnitTest`
  clean, 302 tests completed (same pre-existing 3 Robolectric infrastructure failures + 1 new one
  in `Phase7FRecurringVoucherPostingTest.kt`, which needs a real Room database and is blocked by
  the same environment-level Robolectric SDK issue, not a code defect; +30 new tests in
  `Phase7FTestSuite.kt` all passing, zero regressions elsewhere). Python - `pytest`, 81/81 passing
  (untouched, Android-only scope; re-run to confirm zero regressions).
- **Explicitly out of scope**: UI, automation settings screens, AI/auto-categorization (Item D,
  deferred to its own controlled phase), automatic FY closing, automatic bank reconciliation, direct
  ledger mutation from the scheduler, **automatic posting of a recurring voucher** (generation is
  draft-only; posting requires an explicit user action), a second posting engine, a second
  synchronization engine.
- **Frozen**: two independent, fresh-context read-only structural audits both returned FREEZE
  AS-IS (the second specifically re-verifying the draft-first correction above) - zero violations
  found in either pass. User freeze confirmed.

## Phase 7G - Business/Individual Profile Branding, Consolidated (2026-08-23)

Unlike 7A-7F/7H, this phase's scope was delivered across several discrete, individually-tested
user requests rather than one master-spec-then-audit cycle - this entry consolidates them under
the phase letter the roadmap always reserved for "Business/Individual Profile branding"
(docs 35/36/37/42/45 all reference it as 7G) so the docs/README accurately reflect it as frozen
before Phase 7I begins.

- **Profile Application Service & Data Masking** - `application/profile/ProfileApplicationService.kt`
  (tenant-isolated read/write over the existing, frozen `BusinessProfile`/`IndividualProfile`
  domain models, explicit `TenantMismatchException` on cross-company access - never a silent
  null/wrong result) and `application/profile/SensitiveDataMasker.kt` (`maskPan`/`maskGstin`/
  `maskBankAccountNumber`, `*`-masked, format-specific).
- **Business Profile Hardening** - `ConstitutionType` (Proprietorship/Partnership/LLP/Private
  Limited/Public Limited/HUF/Trust/Society/Other) added to `BusinessProfile`; `tan`/`udyam`
  columns (migration 6->7, additive only); `CapitalAccountNaming.resolveCapitalAccountName` - a
  pure, unwired naming rule (Proprietorship -> `"Capital - <name>"`, Private/Public Limited ->
  `"Share Capital Account"`, everything else -> generic `"Capital Account"` - never share capital
  outside the two corporate types).
- **`BankUpiProfile`/`UpiMetadata`** (`domain/banking/BankUpiProfile.kt`) - a standalone,
  multi-instance bank/UPI settlement-details model, deliberately separate from
  `BusinessProfile`'s single company-level bank identity and from the double-entry accounting
  stream entirely (no DAO/repository access in the file at all). Supports either the company's own
  profile or a specific Party's (`partyId` nullable).
- **`SensitiveDataMasker.maskSensitiveData(input, visibleSuffixLength)`** - the generic,
  parameterized counterpart to the three format-specific maskers above (`'X'`-masked, e.g.
  `"123456781234"` -> `"XXXXXXXX1234"`), for any sensitive identifier that just needs "reveal the
  last N characters."
- **Verification**: `Phase7GTestSuite.kt` (zero-ledger-leakage from a profile edit, GST supply-type
  classification with real state codes, cross-tenant read/write isolation),
  `BusinessProfileHardeningTestSuite.kt` (`CapitalAccountNaming` exhaustive per-constitution
  coverage), `ProfileApplicationServiceTestSuite.kt` (masking correctness including the generic
  `maskSensitiveData`, `BankUpiProfile` field/default checks) - all passing, no regressions.
- **Frozen**: consolidated retroactively; no new audit run (every component task was already
  compiled, tested, and verified individually at the time it was built).

## Phase 7H, Module 1 - Sandbox.co.in Integration Architecture (2026-08-23)

Architecture and contracts only, per the explicit user principle adopted for all remaining
structural phases: *"First create the correct architecture, folders, interfaces, contracts and
documentation. Implement the actual business capability only when its dedicated phase arrives."*
No HTTP client, no live API call, no UI, no implementation of the adapter interface. See
`domain/sandbox/README.md` for the full architecture/boundary writeup.

- **`domain/sandbox/SandboxEnvironment.kt`** - `SandboxEnvironment` (TEST/LIVE), passed explicitly
  on every adapter call, never stored as implicit state.
- **`domain/sandbox/SandboxIntegrationConfiguration.kt`** - `PricingType`, `SandboxServiceStatus`,
  `SandboxIntegrationConfiguration` - a company's per-service enablement record, with **no
  credential field**, enforced by a reflection-based test that scans for credential-like field
  names.
- **`domain/sandbox/SandboxProviderAdapter.kt`** - the core deliverable: a pure Kotlin interface
  (`verifyGstin`, `requestEInvoiceIrn`, `fetchForm26As`) plus the minimal typed models each needs
  (`AssessmentYear`, `GstinStatus`/`GstinVerificationResult`, `EInvoiceIrnStatus`/`EInvoiceIrnResult`,
  `Form26AsEntry`/`Form26AsResult`). Reuses the existing `domain.rendering.BusinessProfile` as the
  tenant/caller context on every call (never a bespoke `BusinessGstProfile` type - confirmed absent
  codebase-wide) and `core.common.AccountingResult<T>` as the return wrapper, matching every
  repository function's existing convention. `AssessmentYear` is a new, deliberately separate type
  from `FinancialYear` (accounting-period locking) and `GstFilingPeriod` (GST return tracking) -
  three different calendars for three different authorities.
- **`domain/sandbox/{gst,income_tax,tds,einvoice,ewaybill}/README.md`** - five future-service
  placeholder folders, each containing only a README (current status, future scope, non-mutation
  boundary) - zero Kotlin files, zero fake implementations.
- **26AS/AIS/TIS boundary documented explicitly**: ITR/tax information, not accounting, not GST
  accounting, not Ledger, not Voucher. The `fetchForm26As` contract shape can exist now; actual
  26AS processing/reconciliation is deferred to a future, dedicated ITR/Tax phase.
- **Verification**: `SandboxIntegrationTestSuite.kt` - structural-contract tests only (interface
  shape via reflection, credential-field-name scan, `AssessmentYear`/`FinancialYear`/`GstFilingPeriod`
  separation, no `AccountingDao`/`AccountingRepository`/`PostingEngine`/`DoubleEntryValidator`
  reachable from any adapter method's parameter/return types) - all passing.
- **Frozen**: independent, fresh-context, read-only audit covering TEST/LIVE separation, credential
  boundary, adapter contract shape, all five service boundaries, 26AS/AIS/TIS classification,
  Assessment-Year/FY/GST-period separation, tenant isolation, reuse of existing Phase 6
  API/security/Outbox architecture (no duplicate engines), and confirmation of zero fake
  implementations - verdict **FREEZE**, zero findings. User freeze confirmed.

## Phase 7I - Advanced Input & Reporting Architecture (2026-08-23)

Architecture and contracts only, same discipline as 7H Module 1. Scope synthesized from
already-named-but-homeless signals (the user's own "GST/ITR/OCR/CMA" phrasing, Phase 7E's
pre-agreed OCR boundary, Phase 7F's bank-reconciliation quarantine diagram) - not a rediscovery of
a written "7I = X" line, since none existed; see `docs/51_ADVANCED_INPUT_REPORTING_ARCHITECTURE.md`
for the full reasoning and writeup.

- **`domain/ocr/OcrIngestionAdapter.kt`** - `extractFromDocument`, given an already-uploaded
  `DocumentAsset` (by id), returns a confidence-scored `OcrExtractionResult` (document type,
  vendor/GSTIN/date/total guesses, line items) - a pre-fill suggestion for the existing
  voucher-entry flow, never a direct posting path.
- **`domain/reconciliation/ReconciliationAdapter.kt`** - `suggestMatches` takes parsed
  `BankStatementLine`s plus an explicit `candidateVouchers: List<Voucher>` (zero DAO access) and
  returns proposed `SuggestedVoucherMatch`es with confidence + reason - the concrete contract for
  the architecture Phase 7F's safety diagram already promised but did not build. `BankLineDirection`
  (`DEPOSIT`/`WITHDRAWAL`) is deliberately its own type, not a reuse of `DrCr` - a bank statement's
  "credit" and this app's ledger `DrCr.CREDIT` are opposite-perspective concepts for the same
  event.
- **`domain/cma/CmaReportGenerator.kt`** - `generate` takes only the already-computed
  `TrialBalanceReport`/`ProfitAndLossReport`/`BalanceSheetReport` as input, following Phase 7C's
  exact "reports consume engine output, they never recalculate" rule structurally, not just by
  convention.
- **Five future-service placeholder folders under `domain/sandbox/`** from 7H
  (`gst/income_tax/tds/einvoice/ewaybill`) are unaffected by this phase - 7I is a sibling scope,
  not an extension of 7H.
- **Verification**: `Phase7ITestSuite.kt` (12 structural-contract tests: each interface confirmed
  a genuine interface with exactly one operation and zero implementations; reflection-based
  no-mutation-path check on every method; `BankLineDirection`/`DrCr` vocabulary separation;
  `CmaReportGenerator.generate`'s parameter types confirmed to be only already-computed reports) -
  all passing, zero regressions elsewhere.
- **Explicitly out of scope**: any HTTP/OCR-library/Android dependency, any implementation of the
  three interfaces, any UI, any wiring into `AccountingRepository` or an existing screen, the full
  multi-year CMA format (only a minimal illustrative subset is modeled).
- **Frozen**: independent, fresh-context, read-only audit covering all three contracts' shape,
  the OCR/DocumentAsset reference pattern, `ReconciliationAdapter`'s zero-DAO-access proof,
  `BankLineDirection`/`DrCr` vocabulary separation, `CmaReportGenerator`'s report-only-input
  guarantee, absence of any HTTP/OCR-library/Android dependency, and confirmation of zero fake
  implementations - verdict **FREEZE**, zero findings. User freeze confirmed.
- **Consolidated 7A-7I audit**: a final, independent, fresh-context read-only audit re-confirmed
  the full chain - full test suite (330 tests, same 4 pre-existing Robolectric infra failures),
  Python suite (81/81), frozen-engine integrity (`VoucherPostingEngine`/`DoubleEntryValidator`/
  `GroupAggregationEngine`/`GstCalculationEngine` unchanged, zero references from 7G-7I back into
  them), zero duplicate engines, zero UI leakage beyond the documented 7F `AccountingViewModel.kt`
  line, DB/schema integrity (version 8, 7 migrations), documentation-vs-code consistency, tenant
  isolation, and safety invariants (no `closeFinancialYear` call from automation, no bank
  auto-match capability) - verdict **CLEARED FOR PHASE 7J**.

## Phase 7J - Management + Subscription Architecture (2026-08-23)

Structure only - no UI, no real implementation. Phase 7J's own first installment, applying the
same "architecture and contracts before implementation" discipline used for 7H/7I one more time
before any screen is built - "Phase 7J = UI, explicitly last" still holds; this is the audit and
structural prep that has to exist first. Scope expanded mid-phase from the original Invoice/
Voucher/.../OCR list to also cover QR/Barcode, Business/Profession master, HSN/SAC/GST item
structure, and Subscription/Entitlement. See `docs/52_MANAGEMENT_ARCHITECTURE.md` for the full
audit and writeup.

- **Audit result**: nearly every named business area (Invoice, Voucher, Receipt, Payment,
  Cash/Bank, Party/Ledger/Item, Settlement/Outstanding, Reports, PDF/CSV/JSON Export, Preview/
  Print/Share, OCR extraction) already exists as a real, frozen, tested implementation from
  Phase 0-7I - building a second "management" interface over any of them would itself be the
  duplicate engine/API layer this phase was explicitly told to avoid. Nothing was rebuilt; the doc
  maps each area to its existing owner instead.
- **Five genuine gaps, five new contracts**:
  - `domain/dataimport/DataImportAdapter.kt` - CSV/JSON/Excel import, `parseFile` mirrors
    `OcrIngestionAdapter`/`ReconciliationAdapter`'s exact shape (references a `DocumentAsset` by
    id, returns `ImportResult` - suggestions, never created records; `ImportSuggestionType`
    deliberately covers only `PARTY`/`LEDGER`/`STOCK_ITEM`, Voucher/transaction bulk-import
    excluded).
  - `domain/qrbarcode/QrBarcodeAdapter.kt` - `generateForStockItem`/`scanImage`, same
    zero-DAO-access shape; `scanImage` takes caller-supplied `candidateStockItems` and returns a
    nullable match suggestion, never creates a `StockItem`.
  - `domain/profession/BusinessProfession.kt` - an open, extensible data record (not a closed
    enum) + an illustrative `StandardBusinessProfessions` catalog (Retailer, Wholesaler, Doctor,
    Engineer, Goldsmith, Contractor). No GST rate field anywhere - classification context only.
  - `domain/itemclassification/HsnSacCode.kt` - `HsnSacCode(code, description, isService)`,
    finally giving a real type to the goods-vs-service fact `GSTTransactionExportDto.isService`
    (Phase 7E) could only ever leave `null` for lack of one. No GST rate field, same reasoning as
    `BusinessProfession` - rates are per-transaction facts on the existing, frozen
    `GstTransaction.gstRatePercent`, never a lookup.
  - `domain/subscription/CompanySubscription.kt` - `SubscriptionPlanType` (FREE/PAID),
    `EntitlementFeature` (Accounting/GSTR/E-Invoice/ITR/Audit Report/CMA/OCR/Inventory/Advanced
    Reports/API Access), `CompanySubscription` (one row per company per financial year, keyed by
    `financialYearId` - never a raw date range), and `SubscriptionEntitlementChecker` - a pure,
    non-suspend `hasEntitlement(subscription, feature): Boolean`, zero
    `AccountingDao`/`AccountingRepository` reference, structurally incapable of touching
    accounting data regardless of subscription state.
- **Excel export explicitly deferred, not built**: `domain.export.ExportFormat` (frozen, Phase 7E)
  already documented itself as having Excel as a pre-planned additive extension point - left
  untouched here per the "never touch a frozen file" discipline, rather than edited even
  additively.
- **Play Store publishing compliance documented for future OCR/gallery/contacts features** - no
  manifest change, no permission request, no consent UI (an unused Android permission is itself a
  Play Store policy risk before the feature justifying it exists). Recorded as a checklist for
  whichever future phase implements OCR/import for real: Photo Picker preferred over broad media
  permissions (avoids a runtime permission entirely), `READ_CONTACTS` as a Play Console Restricted
  Permission requiring its own justification form, mandatory Privacy Policy URL and Data Safety
  form once any of this ships, and a (non-blocking, not Play-mandatory) recommendation to have a
  Terms of Service for a financial-data app regardless.
- **UI rule recorded for the future UI phase (not acted on now)**: no Toast/Toastify notifications
  - the existing `AccountingViewModel.snackbarEvents`/`emitMessage` Snackbar pattern is the only
  UI feedback mechanism to use once real UI work begins; no new Toast library or competing
  notification system.
- **Requirement recorded for future implementation: inventory features must be `AccountingMode`-gated**
  - researched while auditing Invoice/Cash Flow's existing APIs: `ChartOfAccountsScreen.kt`'s Items
  tab and stock-item creation, plus every `server/app/api/routes/` endpoint, currently show/expose
  inventory features unconditionally regardless of `AccountingMode` - a real gap, documented as a
  requirement for whichever future phase touches that UI/API surface, not fixed now.
- **`domain/recurringinvoice/RecurringInvoiceSchedule.kt`** - recurring invoice generation,
  requested after a scoping round-trip on what "dynamic Invoice Management" meant.
  `RecurringInvoiceSchedule`/`RecurringInvoicePeriod`/`RecurringInvoiceGenerationOutcome` mirror
  Phase 7F's draft-first recurring voucher shape, with one simplification: `Invoice` already is its
  own draft (Phase 7A), so no new draft table is needed, only a future thin
  `generateInvoiceIfDue`-style function reusing the existing `createDraftInvoice`.
  `periodKeyFor` delegates directly to the existing, frozen `RecurringVoucherPeriod.periodKeyFor`
  rather than duplicating it (verified byte-identical by a test).
- **Document architecture confirmed to already cover "dynamic" template customization** - the rest
  of what was described (fonts/colors/logo, signature panel, seller/buyer details, the full GST
  line-item table with the correct 50:50-same-state-else-IGST split, PDF/print/A4) was found to
  already exist as real, frozen Phase 7D architecture (`TemplateModels.kt`/`DocumentData.kt`) -
  nothing duplicated. One genuine, narrow gap found and added additively: `DocumentData` gained
  `shipDate`/`dispatchDate: LocalDate? = null` (always `null` today - neither `Invoice` nor
  `TradeDocument` has an underlying source field yet, same documented-extension-point pattern
  `docs/40_CASH_FLOW.md` already used for Investing/Financing Activities). Zero change to either
  `DocumentData(...)` construction call site (both already use named arguments). Barcode-scan-to-
  invoice-line is explicitly a future UI/workflow concern, not new architecture - the pieces
  (`QrBarcodeAdapter`, `StockItem`, `GstCalculationEngine`) already all exist.
- **Verification**: `Phase7JArchitectureTestSuite.kt` (25 structural-contract tests covering all
  seven new contracts/additions: interface shape, zero accounting-mutation path, zero concrete
  implementation, `ImportSuggestionType`/`EntitlementFeature` value-set checks, no-rate-field scans
  on `BusinessProfession`/`HsnSacCode`, `CompanySubscription`'s FY-keyed-not-date-range check,
  `hasEntitlement`'s active/inactive behavior, `RecurringInvoicePeriod`'s due-date logic (including
  the YEARLY-anniversary branch and short-month day clamping, added after an independent audit
  flagged the initial coverage as thinner than ideal there) and `periodKeyFor` reuse, `DocumentData`'s
  new fields) - all passing, zero regressions elsewhere.
- **Frozen**: a third independent, fresh-context, read-only audit covering this final round
  (Recurring Invoice architecture + the `DocumentData` ship/dispatch date addition) re-verified the
  `isDue`/`periodKeyFor` logic against the frozen `RecurringVoucherPeriod` original, confirmed the
  `DocumentData` change was genuinely additive (both existing construction call sites unmodified,
  compiling against new defaults), confirmed no Room entity/migration/wiring was added, and
  confirmed the `AccountingMode`-gating and barcode-to-invoice-line items remain documentation-only
  - verdict **FREEZE**, one non-blocking test-coverage nitpick (since addressed). User freeze
  confirmed.
- **Gap-analysis re-audit found one orphaned entitlement, closed**: the user explicitly asked
  whether anything named across this phase's history had been forgotten. Cross-referencing all 10
  `EntitlementFeature` values against actual capabilities found `AUDIT_REPORT` gated nothing -
  every other value traced to a real contract or engine. Fixed with a sixth
  `domain/sandbox/audit_report/README.md` placeholder, matching the existing five exactly
  (documentation only, no code) - a statutory Audit Report (Form 3CA/3CB/3CD-type) belongs in the
  same government-compliance family as GSTR/ITR, explicitly distinct from `domain.audit.AuditLog`
  (Phase 0's internal edit-history trail).
- **Explicitly out of scope**: any UI, any real CSV/Excel-parsing or barcode/QR library, any
  implementation of any of the five new interfaces, any manifest/permission change, any accounting
  calculation outside the existing frozen engines, any auto-posting/auto-ledger/auto-party
  creation, OCR/Sandbox/ITR/GST-filing implementation, automatic bank reconciliation posting, any
  billing/payment integration for Subscription.

### Phase 7J-A - Application-Service Contracts (Invoice, Voucher, Numbering)

A further sub-step, adding the application-service layer contracts a future UI's ViewModels would
depend on - interfaces only, no implementation, same discipline.

- **`application/invoice/InvoiceManagementService.kt`** - `createDraft`/`updateDraft`/
  `duplicateInvoice`/`cancelInvoice`/`search` (+ `InvoiceFilter`: query/`InvoiceStatus`/
  `ClosedRange<LocalDate>`/partyId, all reusing existing frozen types). Not a gap-fill like the
  rest of 7J - `createDraftInvoice`/`postInvoice`/`cancelInvoice` already exist; this is a facade
  contract for a future delegation. `updateDraft`/`duplicateInvoice` are the two genuinely new
  operations. Absolute rule: never calculates an accounting fact itself - a future implementation
  routes through the existing, unmodified `TradingWorkflowEngine`, then `AccountingRepository`.
- **`application/voucher/VoucherManagementService.kt`** - `createDraft`/`editDraft`/`postDraft`/
  `attachDocumentReference`. Explicitly flagged: unlike `Invoice`, a generic `Voucher` has no draft
  concept today - `VoucherPostingEngine` posts atomically and immediately, exactly why Phase 7F
  built `RecurringVoucherDraftEntity` from scratch rather than reusing anything Voucher provided. A
  future implementation needs its own new draft entity mirroring that pattern - not built here.
  `postDraft` mirrors `AccountingRepository.postVoucher`'s exact parameter shape (verified by a
  structural test) so a future implementation is a direct delegation, never a second posting
  mechanism. `attachDocumentReference` links a `DocumentAsset` by id (never duplicated) as
  supporting evidence - metadata only.
- **`application/numbering/DocumentNumberConfig.kt`** - strictly read-only domain logic as
  requested: a data holder plus one pure `formatted()` method, no generation/mutation method
  anywhere (verified by a structural test that filters out Kotlin's own generated `copy`/getter/
  `$default`-bridge methods and asserts nothing else remains). Real, verified gap:
  `generateNextVoucherNumber`/`generateNextDocumentNumber` both hardcode their number format today
  (`VoucherType.prefix` + fixed 4-digit padding) - no per-company customization exists; this is the
  shape a future configurable scheme would need, without changing either existing function.
- **Architecture coverage question answered directly, verified before building**: Sundry
  Debtors/Creditors (ordinary `Ledger` rows, Phase 0), Sale/Purchase/Sales-Return(Credit Note)/
  Purchase-Return(Debit Note) (`TradingWorkflowEngine`, Phase 5), and Opening/Closing Stock in
  reports (Phase 4, inventory-aware `generateBalanceSheet`/`generateProfitAndLoss`) are all real
  and frozen. **GSTR-1/3B/4/9/9C return filing is explicitly NOT built** - only GSTIN verification
  exists as a contract; actual return generation/submission remains exactly where
  `domain/sandbox/gst/README.md` already documented it as deferred.
- **Verification**: `Phase7JArchitectureTestSuite.kt` extended to 39 tests (14 new, covering all
  three additions) - all passing, zero regressions elsewhere. One test-authoring correction made
  along the way: two new tests initially failed on Kotlin-generated `$default` bridge methods
  (from `postDraft`'s default parameters and the data class's own `copy`) and plain property
  getters - fixed by filtering those out explicitly rather than weakening what the tests actually
  check.
- **Explicitly out of scope**: any UI, any implementation of any of the three new interfaces, any
  new draft entity/Room migration for Voucher drafts, any actual number-sequence generation logic,
  any change to `generateNextVoucherNumber`/`generateNextDocumentNumber`'s current behavior.

## Phase 7J-B - Management Layer (2026-08-24)

Full scope decision provided directly by the user: "Structure/services only. NO UI and NO new
accounting engine" - turn the still-interface-only or still-nonexistent 7J/7J-A application-service
contracts into real, compiled, tested implementations covering Invoice, Voucher, Receipt, Payment,
Cash/Bank, Party/Ledger/Item, Settlement/Outstanding, Reports, Import/Export, Preview/Print/Share,
OCR suggestions, QR/Barcode, Business/Profession, Subscription/Entitlements. Every new service takes
explicit `companyId`/`financialYearId` context rather than an implicit current company/FY (a
cross-cutting requirement added by the user before implementation began). A read-only design pass
ran first (per this project's own established discipline), producing a file-by-file implementation
plan the user reviewed and approved before any code was written. See `docs/53_MANAGEMENT_SERVICES.md`
for the full per-area writeup.

- **Invoice**: `InvoiceManagementServiceImpl` (real implementation of the frozen 7J-A interface) -
  `createDraft`/`cancelInvoice` are direct delegations; `updateDraft` needed one new, narrow,
  additive `AccountingRepository.updateDraftInvoice` function (mirrors `createDraftInvoice`'s exact
  shape, rejects an already-posted invoice); `duplicateInvoice`/`search` needed two more small,
  read-only repository additions (`getInvoicesForCompany`, `getInvoiceLines`) - three total
  additions to `AccountingRepository.kt`, every other call in this phase is a read, not an edit.
- **Voucher**: new `VoucherDraft`/`VoucherDraftLine`/`VoucherDraftStatus` (`application/voucher/`),
  structurally mirroring `RecurringVoucherDraft`'s exact shape (Phase 7F) - deliberately not a real
  `Voucher`, never stored in `vouchers`/`journal_items`. New Room tables `voucher_drafts`/
  `voucher_draft_lines`/`voucher_document_references`. `VoucherManagementServiceImpl` implements the
  frozen 7J-A interface; `postDraft` is a direct delegation to the existing, unmodified
  `AccountingRepository.postVoucher` - never a second posting mechanism. Receipt/Payment ride on
  this same service (posting) plus Settlement (allocation) - not a separate concept anywhere in this
  codebase, stated explicitly rather than silently assumed.
- **Cash/Bank, Party/Ledger/Item, Settlement/Outstanding, Reports**: `CashBankLedgerService`
  (read-only Cash/Bank system-group ledger query, reusing an existing filter already duplicated 3x
  elsewhere), `PartyManagementService`/`LedgerManagementService`/`StockItemManagementService`,
  `SettlementManagementService`, `ReportManagementService` (one method per existing `generate*`
  function) - all thin, pure-delegation facades, zero new capability.
- **Import/Export**: real CSV/JSON parsing - `CsvJsonDataImportAdapter` (hand-rolled RFC-4180-ish
  CSV + Moshi JSON, no new library, `data/dataimport/`) implements the frozen `DataImportAdapter`
  interface; `DataImportManagementService.reviewAndCreate` is the only function that ever calls
  `createParty`/`createLedger`/`createStockItem`, always per-suggestion, never for a whole file.
  `ImportFileFormat.EXCEL` stays unimplemented (structured failure, not a silent no-op).
  `ExportManagementService` (Android-only) wraps the existing Phase 7E `export*As` functions -
  `server/app/api/routes/exports.py` is untouched (confirmed via `git status`).
- **Preview/Print/Share**: `DocumentPreviewService` (Android-only) - direct delegations to
  `assembleDocumentData`/`renderDocumentAsJson` plus the existing, unmodified `PdfDocumentRenderer`/
  `PrintAdapter`/`ShareAdapter` (Phase 7D).
- **OCR suggestions**: `OcrSuggestionService` - orchestration only; `OcrIngestionAdapter` itself
  stays deliberately unimplemented this phase (no vision/ML library was in scope - flagged
  explicitly). `reviewAndPrefillVoucherDraft` builds a `PENDING_REVIEW` voucher draft with
  deliberately **zero lines** (OCR never identifies a ledger id - fabricating one would be an
  accounting decision this service may not make); a human adds real lines via
  `VoucherManagementServiceImpl.editDraft` before the draft can ever be posted.
- **QR/Barcode**: real generation/decoding via `ZxingQrBarcodeAdapter` (new dependency
  `com.google.zxing:core` only - no camera/Android-embedded module, no new manifest permission).
  `generateForStockItem` builds a deterministic payload and performs a real ZXing encode as a
  correctness check; `BarcodePayload` still carries only the raw string, never an image, matching
  the frozen domain type. `scanImage`'s `android.graphics.BitmapFactory`-dependent decode path is a
  documented environment limitation (see Testing below); its non-Bitmap paths and the full
  `generateForStockItem` ZXing round-trip are genuinely tested (ZXing `core` has zero Android
  dependency).
- **Business/Profession**: `BusinessProfessionService` - thin, read-only catalog facade, no
  persistence, no company attachment (an explicitly open question, not resolved here).
- **Subscription/Entitlements**: real persistence, both platforms - new `company_subscriptions`
  table (Android Room + Python), unique `(companyId, financialYearId)` index. **Paid validity is
  always derived from the referenced `FinancialYear`'s own stored dates - never a hardcoded
  "1 Apr-31 Mar" literal anywhere in either `SubscriptionManagementService` (Kotlin) or
  `subscription_service.py` (Python)**; neither table stores a date column at all. Python gets an
  independent domain re-implementation (`domain/subscription/subscription.py`, "duplicate the
  principle, not the code," matching `domain/invoice/status.py`'s existing precedent). Mutation path
  is a direct `POST/GET /api/v1/subscriptions` route, **not** Outbox/SyncEvent (a documented
  judgment call, mirroring the 7D Business-Profile precedent - administrative metadata, no
  offline-conflict risk).
- **Bank/UPI Profile** (Cash/Bank settlement metadata, distinct from the Ledger-based Cash/Bank
  facade above): real persistence, both platforms, for the previously-unpersisted Phase 7G
  `BankUpiProfile` domain model - `BankUpiProfileService` (Android, tenant-asserted via the existing,
  reused `TenantMismatchException`) and `banking_service.py`/`api/routes/banking.py` (Python),
  mirroring the 7D Profile precedent exactly (own table, own routes, not Outbox-synced).
- **Automation/Recurring**: `RecurringVoucherManagementService` - thin facade over the four
  existing, unmodified Phase 7F draft functions.
- **Database**: Android Room schema version 8 → 9 (`MIGRATION_8_9`: five new tables, no existing
  table/column altered); Python Alembic revision `0004` → `0005` (`company_subscriptions`,
  `bank_upi_profiles`). Two pre-existing `Phase0TestSuite` migration-count assertions updated for
  the version bump - the same precedent every prior schema-version phase already set.
- **New error code**: Python `SUBSCRIPTION_ALREADY_EXISTS` (409).
- **Verification**: Android - `compileDebugKotlin`/`compileDebugUnitTestKotlin` clean,
  `testDebugUnitTest` 439 tests completed (same pre-existing 4 Robolectric `DefaultSdkProvider`
  failures - `ExampleRobolectricTest`, `GreetingScreenshotTest`, `Phase7FRecurringVoucherPostingTest`,
  `SuspenseControlArchitectureTest` - plus one new, identically-caused, documented failure in the
  new `Phase7JBVoucherPostingTest.kt`, which mirrors `Phase7FRecurringVoucherPostingTest.kt`'s exact
  setup for `VoucherManagementServiceImpl.postDraft`'s real posting path; +70 new passing tests
  across 12 new suites, zero regressions elsewhere). Python - `pytest`, 95/95 passing (81 existing +
  14 new across `test_subscriptions_7jb.py`/`test_banking_7jb.py`). `git status` confirms zero files
  under `presentation/` and zero lines in any frozen engine file were touched.
- **Judgment calls made and documented, not silently assumed**: Subscription/Bank-UPI mutation path
  is direct-route rather than Outbox-synced (both mirror the 7D precedent for the same
  "administrative metadata, not offline-conflict-prone" reasoning); three small, additive
  `AccountingRepository` read/update functions were needed to back `InvoiceManagementServiceImpl`
  beyond the plan's original single-function estimate, each mirroring an existing function's exact
  shape rather than introducing new logic.
- **Explicitly out of scope**: real OCR/ML implementation (still contract-only, Phase 7I unchanged),
  Excel import, GSTR/ITR filing, any UI, any change to a frozen engine, Outbox/SyncEvent sync for
  Subscription or Bank/UPI Profile, an HSN/SAC facade (not named in this phase's scope).

## Phase 7J UI - Business-Action UI over the Frozen 7J-B Service Layer (2026-08-24)

The first real UI installment since Phase 0 - every prior phase built and froze the accounting
engine plus (7J-B) a full application-service layer with almost no UI on top of it (5 pre-existing
screens: Dashboard, Day Book, Chart of Accounts, Reports, Settings/Sync). Governed by a
user-supplied UX/architecture spec (`LedgerPrime_Phase_7J_UI_UX_Architecture.md`), reconciled
against the frozen 7J-B service layer in a dedicated read-only design pass before any code was
written - see `docs/54_UI_UX_ARCHITECTURE.md` for the full writeup. No accounting logic added; the
three narrow backend additions below (all pre-approved before implementation) are the only new
code outside `presentation/`/`ui/theme/`, each a pure delegation or pure aggregation.

- **Royal Purple + Off-White theme** (`ui/theme/{Color,Theme}.kt`) - confirmed by direct read
  before touching anything that all 5 pre-existing screens + 6 dialogs consume
  `MaterialTheme.colorScheme.*` exclusively, zero hardcoded hex, so the new palette recolors 100%
  of existing UI with **zero screen-file edits**. Both a light and a symmetric Royal-Purple-based
  dark `ColorScheme` were built (the UX spec was silent on dark mode - built rather than left
  mismatched). Status accents (`EmeraldCredit`/`CrimsonDebit`/`AmberWarning`/`IndigoTax`) kept
  unchanged; `PrimaryBlue*`/`Navy900`/`Slate*` retired (confirmed unreferenced outside `theme/`).
- **5-item bottom nav** (`NavigationTab { HOME, SALES, PURCHASES, MONEY, REPORTS }`,
  `AccountingViewModel.kt`) replacing the old `{DASHBOARD, DAYBOOK, CHART_OF_ACCOUNTS, REPORTS,
  SETTINGS_SYNC}`; `HashRouter.kt`'s `AppRoute` grew additively (`Sales`, `Purchases`, `Money`,
  `Parties`, `Profile`, `Subscription`, `DataTools`, `Search`). `MainAppScreen.kt` dispatches on
  `uiState.currentRoute` (not just `selectedTab`) since Profile/Subscription/DataTools/Search never
  own a bottom-nav tab - reached via two new `AppTopBar.kt` icons (Search, Profile) or from within
  another screen, per the spec's "secondary features reached through their respective sections"
  rule.
- **Home rebuilt as a "Business Cockpit"** (`DashboardScreen.kt`, rebuilt not replaced) - a Quick
  Actions row (Sale/Purchase/Receive/Pay/Transfer/Add Customer/Add Supplier/Add Item, business-
  action framed) and a Business Snapshot widget grid (Cash/Bank/Receivables/Payables/Sales/
  Purchases/Profit-or-Surplus/GST/Outstanding), every figure a direct read of an already-loaded
  `AccountingUiState` field - zero summation performed in this file.
- **Sales/Purchases** (`SalesScreen.kt`/`PurchasesScreen.kt`, new) - voucher creation is **not**
  reimplemented: reuses the existing, tested `CreateVoucherDialog(defaultVoucherType =
  VoucherType.SALES/PURCHASE)`, posted via the existing `postTradingDocument`/`buildSale`/
  `buildPurchase` path already wired in `AccountingViewModel.kt` - zero new posting logic.
  `PartiesScreen.kt` (new, shared via a `role` param) lists/creates Customers or Suppliers through
  `PartyManagementService`; Party edit stays out of scope, matching the frozen service layer's own
  create/list-only surface.
- **Money** (`MoneyHomeScreen.kt`/`MoneyTabContent`, new) - a sub-menu per the spec's exact list
  (Receive Money/Pay Money/Transfer/Cash/Bank/UPI), a Pending Reviews tile, and Journal moved to an
  explicit "Advanced" section (never a primary action, Section 15). Receive/Pay/Transfer all reuse
  the same `CreateVoucherDialog` with `VoucherType.RECEIPT`/`PAYMENT`/`CONTRA` preselected,
  including its existing settlement-allocation UI - never a second allocation UI.
  `VoucherDraftReviewScreen.kt`/`VoucherDraftEditorScreen` (new) implement the Draft/Review/Post
  queue: list -> ledger-line editor -> Post (direct delegation to
  `VoucherManagementServiceImpl.postDraft` -> the existing, unmodified
  `AccountingRepository.postVoucher`, never a second posting path) or Discard.
  `CashOrBankLedgerListScreen`/`UpiProfilesScreen.kt` (new) are read-only/settlement-metadata-only,
  reusing the existing ledger-statement flow and `BankUpiProfileService` respectively.
- **Reports Center** (`ReportsCenterScreen.kt`, new, replaces the old flat 4-tab `ReportsScreen.kt`
  as the routed destination) - a category-selector landing page per the spec's 5 categories
  (Financial/Sales-Purchase/Accounts/GST/Analysis). The existing report-rendering composables
  (`TrialBalanceView`, `ProfitAndLossView`/`IncomeAndExpenditureView`, `BalanceSheetView`,
  `GSTCenterView`, still in `ReportsScreen.kt`) are reused verbatim. New views (`CashFlowView`,
  `RatioAnalysisView`, `OutstandingList`, `HsnSacSummaryView`) read already-loaded
  `AccountingUiState` fields populated by a new `AccountingViewModel.refreshReportsCenterExtras()`
  calling `ReportManagementService`'s existing pure-delegation methods. Sales/Purchase Register and
  Cash/Bank Book/Receipt/Payment Register are explicit UI-side *filters* over already-fetched
  `uiState.vouchers` - never a new calculation. **Fund Flow and CMA render as visible, disabled
  "Coming soon" tiles** - both have zero backend implementation and this phase does not write new
  calculation logic to fill them.
- **Profile/Subscription/Import & Scan** - `ProfileScreen.kt` (new) shows Business and Individual
  profile sections unconditionally, both at once (no field distinguishes which applies; the frozen
  `ProfileApplicationService` enforces no exclusivity either); hosts entry points to Import & Scan,
  Subscription, and the existing `SettingsAndSyncScreen.kt` (relocated out of the bottom nav, file
  itself untouched). `SubscriptionScreen.kt` (new) - plan status + entitlement checklist +
  upgrade/renew; **no other new screen this phase checks an entitlement before offering its
  action**, per the resolved decision to defer gating. `DataToolsScreen.kt` (new) - CSV/JSON import
  and "Scan Receipt" (OCR), both strictly File -> Parser -> Validation -> Draft/Suggestion -> User
  Review -> Explicit Create/Post; OCR's entry point always renders a graceful "not yet available"
  state today (`OcrIngestionAdapter` has no real implementation) - expected, documented behavior,
  deliberately left enabled rather than hidden. File picking uses SAF (`OpenDocument`, CSV/JSON)
  and Android's Photo Picker (`PickVisualMedia`, receipt photos) exclusively - confirmed zero new
  `AndroidManifest.xml` entries needed.
- **Search** (`SearchScreen.kt`, new, persistent top-bar icon) - a composite, in-memory filter over
  `uiState.parties`/`ledgers`/`vouchers`/`stockItems`; never recomputes anything, every result
  routes into an existing detail screen.
- **Account Mode gating fix** (the one confirmed pre-existing UI gap this phase closes) -
  `isInventoryEnabled(uiState)` (new, pure function, `AccountingViewModel.kt`) is the single point
  every Items-related call site now reads; `ChartOfAccountsScreen`'s `CoaTab` list filters `ITEMS`
  out entirely when `AccountingMode != ACCOUNT_WITH_INVENTORY`, closing the gap 7J-B's own audit
  flagged (this tab previously rendered unconditionally regardless of mode).
- **Shared component**: `presentation/components/SectionCard.kt` (new) - one reusable rectangular
  container used by every new screen this phase, colored entirely through `MaterialTheme.colorScheme`
  so it inherits the new theme automatically; the 5 pre-existing screens' own inline card usage is
  completely untouched.
- **Backend additions (the only three, all pre-approved)**: `VoucherManagementServiceImpl.listDrafts`
  (additive, not part of the frozen interface, mirrors `RecurringVoucherManagementService.getDrafts`);
  `DocumentAssetType` gains `IMPORT_SOURCE_FILE`/`OCR_SOURCE_IMAGE` (additive enum values, so
  Import/OCR can persist a picked file/photo as a checksummed `DocumentAsset` before parsing);
  `ReportManagementService.hsnSacSummary` + one additive `AccountingRepository.getGstTransactionsForCompanyFY`
  read-only helper - pure grouping of already-computed `GstTransaction` rows by HSN/SAC code, never
  a new tax calculation.
- **New tests**: `Phase7JUITestSuite.kt` - `isInventoryEnabled` (Account-Mode-with-inventory/
  account-only/no-current-company), `VoucherManagementServiceImpl.listDrafts` (status filtering,
  entity-to-domain line mapping), `ReportManagementService.hsnSacSummary` (grouping/aggregation
  correctness across multiple HSN codes and Output/Input direction, empty-state).
- **Verification**: Android - `compileDebugKotlin`/`compileDebugUnitTestKotlin`/`testDebugUnitTest`
  clean throughout implementation, re-confirmed at 439/444 (same pre-existing 5 Robolectric
  `DefaultSdkProvider` environment failures - `ExampleRobolectricTest`, `GreetingScreenshotTest`,
  `Phase7FRecurringVoucherPostingTest`, `SuspenseControlArchitectureTest`,
  `Phase7JBVoucherPostingTest` - zero regressions) before the new test file was added; final count
  with the new suite included verified after. Real visual/screenshot verification is not achievable
  in this environment (the repo's only Compose-test file is already among the pre-existing
  failures) - matching every prior UI-adjacent phase's own documented disclosure.
- **Explicitly out of scope**: Party/Ledger/Item edit (no `updateParty`/`updateLedger`/
  `updateStockItem` exists anywhere in the frozen service layer), entitlement gating beyond the
  Subscription screen itself, a real OCR implementation, Fund Flow, CMA, live-camera QR/barcode
  scanning (item barcode *generation* was added; a dedicated scan-to-match screen was not), Excel
  import, GSTR/ITR filing, any change to a frozen engine.

## Phase 7J - FREEZE (2026-09-11)

Phase 7J UI (above) shipped without the independent audit this project requires before a phase is
considered complete - real, substantial work continued on top of it across several sessions (an
informal "Corrections" track: `docs/CORRECTIONS_README.md`/`docs/CORRECTIONS_LOG.md` - business
identity display, single-navigation layout, contextual OCR entry points, a plain-Bill default
voucher view, contacts import/favorites, and a 2026-09-09 "THIS APPLICATION IS NOT AN ERP"
product-identity directive of 18 numbered sections) plus a large `a53870d` "Phase 7J audit"
hardening commit (voucher soft-cancel, Groups Tree hierarchy rendering, OCR/import display fixes) -
none of it threaded back into this changelog's own phase-freeze discipline until now. Reconciled in
this session:

- **Working-tree audit**: every file modified/untracked at reconciliation time was traced to its
  origin. None belonged to Phase 7J or to the separately-already-committed GSTR-1/Phase 8A work
  (`958a376` onward) - both were fully committed already. The uncommitted pile was entirely later,
  unrelated "Week 1-3" Play Store-readiness work (release engineering, PDF/Excel/CSV export polish,
  dashboard UI redesign, a Business-Setup-Wizard company-creation bug fix) - left untouched
  throughout this reconciliation, per explicit instruction.
- **First independent audit (fresh-context agent, no implementation history) - verdict NOT-YET.**
  Tests and spot-checked 7J UI screens were solid, but two sections of the 2026-09-09 directive's
  own execution plan - the only two touching financial correctness rather than cosmetics - were
  confirmed still unexecuted, exactly as the log's own text had warned ("do not assume any of
  section 1-18's fixes are already in place"):
  - **Section 2 - voucher/invoice duplication audit.** Real, confirmed defect found on
    investigation (not hypothetical): `AccountingRepository.postVoucher`'s `idempotencyKey`
    defaulted to a fresh random UUID on every call, and most callers (`postVoucherDraft` included -
    the same path OCR-sourced drafts post through) never overrode it, so `VoucherPostingEngine.post`'s
    own idempotent-replay guard never actually triggered on a retry. Since each retry also rebuilt
    its `JournalItemEntity` list with blank-then-fresh-UUID `itemId`s, `OnConflictStrategy.REPLACE`
    on the voucher header masked nothing - a retried post (dialog dismissed and reopened mid-write,
    not just a double-tap the UI's existing `isSubmitting` flags already caught) silently doubled the
    real ledger-balance impact behind what still looked like one voucher row.
  - **Section 9 - GST-mismatch UX audit.** `Gstr1Validator` already computed a real, specific
    message per issue (exact GSTIN/ledger/voucher id and why it's wrong), but
    `GstReturnDashboardScreen`'s `GstErrorDetailsScreen` discarded it in favor of a generic per-code
    label - worst for a return-level issue (`voucherId == null`, e.g. a Composition-scheme company
    that can't file GSTR-1 at all), which additionally got no "Fix Now" button, leaving the bare
    label as the user's only information.
- **Fixes** (confirmed defects only, no redesign, per explicit instruction): a new
  `postVoucherMutex` (`kotlinx.coroutines.sync.Mutex`) now wraps `postVoucher`'s entire body,
  serializing overlapping calls to the single authoritative posting path;
  `AccountingViewModel.postVoucherDraft` now posts with a stable, draft-id-derived
  `"DRAFT_${draftId}"` key instead of the previous keyless default - together closing both the
  concurrent-retry and the sequential-retry-after-reopen cases for the draft/OCR path.
  `GstErrorDetailsScreen` now renders `issue.message` beneath the existing code label for every
  issue, per-voucher or return-level alike - no new navigation, no new screen. New regression tests:
  `Phase2TestSuite.testPostVoucherAtomic_IdempotentReplay_WithFreshItemIds_DoesNotDuplicateJournalItemsOrLedgerEffect`
  (same idempotencyKey, freshly-regenerated itemIds - the exact pre-fix failure mode - asserts
  neither the ledger balance nor the journal-item count doubles) and
  `Gstr1FoundationTestSuite.t29_Validator_CompositionScheme_MessageExplainsWhyNotJustTheCode`/
  `t30_Validator_InvalidPlaceOfSupply_MessageNamesTheExactBadValueAndVoucher` (assert the validator's
  messages are real, specific prose, not blank or code-only). Manual one-shot entry
  (`postQuickVoucher` and similar) was deliberately left on its existing UI-level `isSubmitting`
  guard only - it has no persisted draft/source id to derive a stable key from without inventing new
  persisted state, which would be a redesign, not a fix; documented as an accepted, narrower residual
  risk rather than silently left unmentioned.
- **Second independent audit (fresh-context agent, no context from the fix work) - verdict FREEZE.**
  Independently confirmed: the mutex wraps the true full critical section with no early-return
  bypass and no re-entrancy/deadlock path (`postVoucher`'s only internal callers - recurring-voucher
  and invoice posting - never call it from within itself); `postVoucherDraft` is genuinely the sole
  production call site depending on the fixed default; both new duplication-audit assertions pass;
  CSV/JSON import reconfirmed to create only Party/Ledger/StockItem, never a Voucher, so it was
  correctly out of scope all along, not silently ignored. `Gstr1Validation.kt`'s messages
  independently confirmed specific and non-generic; both new GST-message tests confirmed to pass and
  to test what they claim. One minor, non-blocking residual both audits agree on: a GSTIN-type
  error's new message explains *why* it's wrong but not explicitly *where* to fix it (the Party's own
  ledger, not the voucher) - noted, not treated as a fresh blocker.
- **Verification**: `testDebugUnitTest` - 751 tests, 5 failed (the same accepted baseline:
  `ExampleRobolectricTest`, `GreetingScreenshotTest`, `Phase7FRecurringVoucherPostingTest`,
  `Phase7JBVoucherPostingTest`, `SuspenseControlArchitectureTest`, all
  `UnsupportedOperationException at DefaultSdkProvider.java:170` - a sandbox limitation, not a code
  regression), zero new failures; 3 new tests included and passing. `assembleDebug`: BUILD
  SUCCESSFUL. Live device verification was not possible this session (no device connected at
  reconciliation time) - recorded honestly rather than skipped silently, matching this project's own
  established disclosure convention for every prior UI-adjacent phase.
- **Verdict: Phase 7J is COMPLETE and FROZEN.** No further Phase 7J code changes are in scope. The
  GSTR-1/Phase 8A work already committed (`958a376` onward) was independently reconfirmed cleanly
  separable from Phase 7J's own UI throughout this reconciliation and is unaffected.
