# LedgerPrime - Double-Entry Accounting Engine (Android + Optional Cloud Sync)

LedgerPrime is an offline-first double-entry accounting engine for Android (Kotlin, Jetpack
Compose, Room), with an optional Python/PostgreSQL server (`server/`) for cloud sync. The app is
fully functional with zero internet connectivity and zero login - the server only exists to
receive what the Outbox pushes once a device is online and the user has opted into Cloud Sync.

## Project Status
Phases 0-6 (Part A) and Phase 7A-7I complete and frozen (7H limited to its Module 1 architecture -
see below) - see `docs/30_CHANGELOG.md`
for the full history: double-entry posting engine, financial statements (Trial Balance/P&L/Balance Sheet),
inventory & COGS, GST & statutory accounting (item-driven Sale/Purchase/Credit-Debit-Note/Settlement
allocation), the API/sync/server architecture (`server/`, `docs/32_SERVER_ARCHITECTURE.md`), the
Party + Invoice domain foundation (`docs/35_PARTY_INVOICE_DOMAIN.md`) - a Customer/Supplier Party
model and a pre-posting Invoice lifecycle (DRAFT -> POSTED -> PARTIALLY_PAID/PAID/OVERDUE/
CANCELLED, always derived, never stored) - the Document/Voucher Lifecycle Architecture
(`docs/36_DOCUMENT_LIFECYCLE.md`) - the remaining 6 trade-document types (Quotation, Proforma
Invoice, Sales/Purchase Order, Delivery/Receipt Note) as a non-posting `TradeDocument` lifecycle,
document numbering fully decoupled from voucher numbering, and document conversion - the Report
Management architecture (`docs/37_REPORT_ARCHITECTURE.md` through `docs/41_RATIO_ANALYSIS.md`) -
Trial Balance/P&L/Balance Sheet/GST/Ledger Statement ported to Python for the first time, plus
three new reports (Day Book, Outstanding/Receivables/Payables, Cash Flow, Ratio Analysis) - and the
Document Template & Rendering Architecture (`docs/42_DOCUMENT_TEMPLATE_ARCHITECTURE.md` through
`docs/45_DOCUMENT_BRANDING.md`) - a company-scoped, versioned document template model, a
Compose/PDF-library-independent `DocumentData` assembly layer, PDF (Android-native)/Print/Share/
JSON/CSV renderer adapters kept strictly behind the accounting boundary, and document-branding
(`BusinessProfile`/`IndividualProfile`/`DocumentAsset`) - and the Export Architecture & Data
Interchange (`docs/46_EXPORT_ARCHITECTURE.md` through `docs/49_GSTR_JSON_EXPORT.md`) - a versioned
JSON envelope, a generic RFC-4180 CSV engine, a GSTR JSON serializer built strictly from
`GstTransaction` facts, and Voucher/Party/Ledger/Invoice/report export DTOs, all read-map-serialize
only - all extending the frozen posting engine without modifying it.
Phase 7F - Automation Architecture (`docs/50_AUTOMATION_ARCHITECTURE.md`) - complete and frozen
(Invoice/Compliance Reminders; a draft-first Recurring Voucher Engine where automation only ever
generates a review-only draft with zero accounting effect, and a voucher is posted - through the
existing `postVoucher`/`DoubleEntryValidator` path, no automatic posting - only in direct response
to an explicit user Post action, with idempotent draft generation; and WorkManager-backed real
scheduling infrastructure with a deterministic daily/monthly/yearly run decision), passed two
independent freeze audits. Phase 7G - Business/Individual Profile branding - complete and frozen,
built across several discrete, tested tasks rather than one spec-then-audit cycle: Profile
Application Service & tenant-isolated data masking, Business Profile hardening (constitution
type/TAN/UDYAM, capital-account naming distinct from corporate share-capital structures),
`BankUpiProfile` + the generic `maskSensitiveData` utility, and `Phase7GTestSuite.kt`'s targeted
zero-ledger-leakage/cross-tenant/GST-classification verification. Phase 7H, Module 1 -
Sandbox.co.in Government/Tax API integration architecture (`domain/sandbox/README.md`) - complete
and frozen: a pure-Kotlin `SandboxProviderAdapter` contract (GSTIN verification, e-Invoice IRN
request, Form 26AS fetch - Assessment Year kept structurally separate from Financial Year and GST
filing period throughout), explicit TEST/LIVE environment separation with zero credentials modeled
anywhere in the contract, and five future sub-services (GST, Income Tax/26AS, TDS, e-Invoice,
e-Way Bill) documented as not-yet-implemented placeholders, each with its own scope/boundary
README - independently audited, FREEZE confirmed.

Phase 7I - Advanced Input & Reporting Architecture (`docs/51_ADVANCED_INPUT_REPORTING_ARCHITECTURE.md`)
- complete and frozen: OCR ingestion, bank-statement-import reconciliation suggestions, and CMA
report generation, all contracts only, same discipline as 7H Module 1 (no HTTP/OCR-library
dependency, no implementation, no UI) - independently audited, FREEZE confirmed, zero findings.

7H's five sub-service real implementations (`domain/sandbox/{gst,income_tax,tds,einvoice,ewaybill}/`)
remain deferred past the UI gate - they are not required for Phase 7J to begin. A final,
consolidated, independent audit re-confirmed the entire 7A-7I chain as a whole (full test suite,
frozen-engine integrity, zero duplicate engines, zero UI leakage, schema integrity,
documentation-vs-code consistency) - **cleared for Phase 7J**.

Phase 7J - Management + Subscription Architecture (`docs/52_MANAGEMENT_ARCHITECTURE.md`) -
Phase 7J's own first installment, same architecture-before-implementation discipline as 7H/7I,
structure only, no UI. An audit found nearly every business area Phase 7J's eventual screens will
need (Invoice, Voucher, Receipt, Payment, Cash/Bank, Party/Ledger/Item, Settlement/Outstanding,
Reports, PDF/CSV/JSON Export, Preview/Print/Share, OCR extraction) already exists as a real,
frozen implementation - mapped, not rebuilt. Five genuine gaps got new contracts, all
suggestion/classification-only, same shape as 7I's OCR/reconciliation contracts: CSV/JSON/Excel
data import (`domain/dataimport/`), QR/barcode generation and scanning (`domain/qrbarcode/`), an
extensible Business/Profession master with no hardcoded GST rate (`domain/profession/`), a
structured HSN/SAC classification with no hardcoded GST rate (`domain/itemclassification/`), and
Subscription/Entitlement (`domain/subscription/` - FY-bound `CompanySubscription` +
`SubscriptionEntitlementChecker`, a pure function that controls feature access only and cannot
touch accounting data), and recurring invoice generation (`domain/recurringinvoice/` - mirrors
Phase 7F's draft-first recurring voucher shape, but needs no new draft table since `Invoice`
already provides its own draft state). Excel export itself stays deferred as 7E's own pre-planned,
untouched extension point. A document-architecture audit confirmed "dynamic" template
customization (fonts/colors/logo/signature panel/GST line-item table) already exists as real,
frozen Phase 7D architecture; the one genuine gap found - ship/dispatch date - was added
additively to `DocumentData` (`shipDate`/`dispatchDate`, always `null` today, same
documented-extension-point pattern as Cash Flow's Investing/Financing Activities). Google Play
Store publishing requirements, a UI rule (no Toast, use the existing Snackbar pattern), and a
requirement that inventory features be gated by the existing `AccountingMode` (found unconditional
today on both the Android UI and Python API) are all recorded as forward-looking notes, not
implemented.

**Phase 7J - COMPLETE + FROZEN (2026-09-11).** The 7J UI installment (Business Cockpit Dashboard,
Sales/Purchases/Money/Reports Center/Profile/Subscription/Data Tools/Search - see
`docs/30_CHANGELOG.md`'s "Phase 7J UI" entry) sat unaudited for several sessions while a separate,
informal "Corrections" effort (`docs/CORRECTIONS_LOG.md`/`CORRECTIONS_README.md`) hardened it -
business identity display, navigation layout, OCR entry points, a plain-Bill default voucher view,
and a 2026-09-09 "not an ERP" product-identity directive. Two independent, fresh-context audits ran
against that combined state: the first (verdict NOT-YET) found the directive's own Section 2
(voucher/invoice duplication risk across create/OCR/import/retry paths) and Section 9 (GST-mismatch
UX - what/why/how-to-fix) still open, unexecuted despite the log itself warning not to assume they
were done. Both were then closed as real, confirmed-defect fixes (not a redesign): `AccountingRepository.postVoucher`
now serializes through a `postVoucherMutex`, and `postVoucherDraft` now posts with a stable,
draft-id-derived idempotency key instead of a fresh-random-UUID-per-call default - closing a real
gap where a retried post (dialog dismissed and reopened mid-write, not just a double-tap) could
silently double the ledger balance impact behind what looked like one, REPLACEd voucher row.
`GstErrorDetailsScreen` now surfaces `Gstr1Validator`'s own specific per-issue message (exact
GSTIN/ledger/voucher and why it's wrong) instead of discarding it for a generic per-code label -
closing the gap where a return-level issue (no voucherId, e.g. Composition-scheme-can't-file-GSTR-1)
showed no guidance at all. A second independent audit re-verified both fixes against the real code
and a fresh test run and returned **verdict FREEZE** - see `docs/30_CHANGELOG.md`'s "Phase 7J -
FREEZE" entry for the full evidence trail.

Phase 8A, Part 1 & 2 - GSTR-1 Foundation: the real, statutorily-named GSTR-1 tables
(`domain/taxation/gstreturn/Gstr1Models.kt`, `Gstr1ReturnBuilder.kt`, `Gstr1Validation.kt`,
`Gstr1JsonMapping.kt`), built strictly as a regroup/aggregate of already-persisted
`GstTransaction`/`Voucher` facts - never a second GST calculation. Adds GSTIN checksum validation
(beyond the pre-existing format-only regex check), both a readable JSON tree and the real GST
Network portal JSON field names, an explicit (never inferred) Nil-return flag on `GstReturnEntity`,
and read/prepare/validate-only automation for GSTR-1 draft preparation and filing-due-date
reminders - filing itself stays a human action, same discipline as every other automation checker
in this codebase. Alongside it, a Group Hierarchy audit fix: `createCompany()` was seeding only a
flat 10-group subset instead of the canonical 28-group hierarchy, so every company created before
this fix was missing 17 System Groups outright (most importantly Loans/Bank OD/Secured/Unsecured
Loans); backfilled per-company via an idempotent, purely additive migration.

## Getting Started

This repo has two independently runnable halves - see the Claude Code skills below for verified,
step-by-step build/run/drive instructions for each (prerequisites, exact commands, and known
gotchas on a Windows dev machine):

- **Android app** (`app/`): `app/.claude/skills/run-app/SKILL.md` - build the debug APK
  (`./gradlew.bat assembleDebug`) and drive it over `adb` against a real USB-connected device (the
  emulator needs hardware virtualization this dev machine doesn't have).
- **Cloud-sync server** (`server/`): `server/.claude/skills/run-server/SKILL.md` - create a
  venv, run Alembic migrations against the default local SQLite DB (no Docker/Postgres needed),
  and boot it with `uvicorn`.

## Architectural Highlights
- **Authoritative Integer Precision**: Zero floating-point arithmetic. All monetary values are maintained in 64-bit integer minor units (`Long paise`).
- **Strict Multi-Tenant Isolation**: Every entity, query, transaction, and repository explicitly requires and validates `companyId`.
- **Clean Architecture**: Decoupled Domain (pure Kotlin), Data (Room SQLite, DAOs, Outbox), Presentation (M3 Jetpack Compose), and Core Infrastructure.
- **Explicit Database Migrations**: Fallback to destructive migrations is strictly removed; all migrations are registered deterministically.
- **Offline-First Synchronization**: FIFO mutation queueing with idempotent tracking (`idempotencyKey`) and exponential backoff retry policies.
- **Hardware-Backed Secure Storage**: AES-256 GCM encrypted preferences via `ISecureStorage`.

## Phase 0 Foundations Completed
1. Company & Tenant Foundation with GSTIN/PAN validation
2. Indian Financial Year (Apr 1 – Mar 31) & Monthly Accounting Periods
3. Room Database v1 with Foreign Key Integrity & Type Converters
4. Explicit Database Migration Architecture
5. Offline Storage & FIFO Outbox Sync Entities
6. Hardware-Backed Encrypted Security Layer
7. Repository Boundaries with Explicit Tenant Context
8. Comprehensive Phase 0 Test Suite (12 test vectors verified)

