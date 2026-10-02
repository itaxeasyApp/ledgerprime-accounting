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
implemented. Not yet independently audited or frozen. Actual Phase 7J UI screens remain gated
behind that audit, same as every prior phase.

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

## Phase 8 Developer Checkpoint (after Crashlytics)

Current state, as of the Crashlytics checkpoint. Phase 8 was a step-gated audit/fix program; every step
audited first, changed only what was needed, and added regression tests.

**Completed (Steps 1-14, 17-20):** persistence fidelity; one posting gate (GST flag/facts, filed/locked
period gate, one valid Credit/Debit Note per original); books correctness (COGS/inventory links); GST
voucher filter; GSTR-1 (official GSTN serializer, validate-before-export); GSTR-3B (+ RCM liability);
GSTR-9 and GSTR-9C working paper; Bank balance sign consistency across Home/Money/Trial Balance/Balance
Sheet; GSTR-2B readiness audit and safety fix (unrecorded ITC rows are omitted from the 3B JSON, and
GSTR-3B cannot become READY while its Table 4 ITC is unreconciled with GSTR-2B); purchase document
identity (supplier GSTIN, supplier invoice number and date on the GST fact, duplicate-document guard,
DB v30, NULL = NOT_RECORDED, never backfilled); Supplier Invoice Date input; identity propagation to
sync/export; correction-flow prefill of the supplier identity and of the account-only GST rate/HSN;
Firebase Crashlytics.

**Stopped, awaiting an input (Steps 15-16):** real GSTR-2B input verification. No real GST Portal
GSTR-2B file has been supplied, so the file format has not been documented and no importer exists.

**Crashlytics**
- Integrated through the existing Firebase BOM / plugin catalog (crashes and non-fatals only; no Analytics).
- Firebase project: `ledgerprime-2418f` (project id as written in `app/google-services.json`).
- Android applicationId: `com.ledgerprime.app`.
- Sensitive-data redaction is implemented (`core/crash/CrashSanitizer.kt`): only exception class names and
  stack traces are sent, never message text (which can contain GSTIN, PAN, bank, voucher/amount data); no
  custom keys, user id or logs. `CrashReporter` is the only place that touches the Crashlytics API.
- Test crash: a DEBUG-only trigger fired a test crash on a real device, the app was reopened, and the
  report upload request returned HTTP 200. **Console confirmation is still awaiting** - the crash has not
  yet been seen in the Firebase console, so delivery is not claimed as confirmed.
- The DEBUG test-crash trigger has been removed; the final APK does not contain it.

**Test baseline:** full suite = 1000 tests, 1 deliberate known failure
(`BROKEN_G7_S7_gstr9AndGstr9c_mustBeAvailableForRegularTaxpayer`, GSTR-9/9C are deliberately not offered
until an annual-period screen exists). Run: `./gradlew.bat testDebugUnitTest` (JDK 21).

**Release signing (Phase 8 Step 24)**
- The upload keystore lives OUTSIDE this repository and outside OneDrive; there is no default keystore path or
  password anywhere in the build. `my-upload-key.jks` (committed in the initial commit) is a compromised, publicly
  exposed key and must not be used - the build refuses it.
- Release builds (`assembleRelease` / `bundleRelease`) need, as environment variables or Gradle properties
  (e.g. in `~/.gradle/gradle.properties`, never a file in the repo): `KEYSTORE_PATH`, `STORE_PASSWORD`, and
  optionally `KEY_PASSWORD` and `KEY_ALIAS` (default `upload`). They fail with a clear message when missing, or
  when the keystore is inside the repo or OneDrive. Debug builds and tests need none of them.
- `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `*.pem` and `keystore.properties` are gitignored.
**Known open items**
- GSTR-2B remains blocked pending a real GST Portal GSTR-2B file (importer, matcher, GSTR-3B READY,
  GSTR-9 Table 8 all depend on it).
- GSTR-9/9C annual-period UI is pending.
- The permissions / Google Play audit (e.g. `READ_CONTACTS`, Data Safety, privacy policy) has not been started.
- Not yet covered by the supplier identity: the supplier date on already-posted purchases (stays NOT_RECORDED),
  a Debit Note's own supplier credit-note number/date, and later reference edits (the GST fact keeps the
  number as posted).

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

