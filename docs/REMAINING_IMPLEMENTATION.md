# LedgerPrime - Remaining Implementation Checklist

Single source of truth for what is still left to do, compiled by reading the project's own
history end to end: `README.md`'s "Project Status", the full `docs/30_CHANGELOG.md` (Phase 0
through the 2026-09-11 "Phase 7J - FREEZE" entry), `docs/CORRECTIONS_README.md` +
`docs/CORRECTIONS_LOG.md` (the informal post-7J-UI hardening track, including the 2026-09-09
"THIS APPLICATION IS NOT AN ERP" product-identity directive), the six `domain/sandbox/*/README.md`
scope docs, and `git log`.

**This file documents only what remains.** It does not re-describe or re-audit anything already
COMPLETE/FROZEN - see `docs/30_CHANGELOG.md` for that full history. Nothing below was invented:
every item traces to an explicit "not yet done"/"deferred"/"future scope" statement already on
record in one of the sources above. A companion, repo-wide search for a numbered "Phase 9" (or
later) roadmap document found none - no such document exists in this repository as of this
writing. If one exists outside the repo, it should be added here (or a copy of it committed)
before this checklist is treated as complete.

---

## 1. Phase 8 - GST Returns & Compliance (GSTR-1 -> GSTR-3B -> GSTR-9)

**Status: DEFERRED** (explicit user instruction, 2026-09-11 - "Phase 8 — DEFER temporarily. Do
not continue GSTR-1/3B/9 work.")

**What remains:**
- Live device test coverage across the remaining 3 business configurations - only Account-Only +
  Trading (company "Retest Co") has been exercised with a real posted GST Sale and GST Purchase;
  Account + Inventory, and Service-type businesses (both Account-Only and Account + Inventory)
  are untested for this phase's own GSTR-3B/9 code.
- A GSTR-9 live spot-check (prepare/validate/PDF/export) with real data - GSTR-3B was verified
  live this way; GSTR-9 was not reached before the defer instruction.
- The mandatory independent audit (fresh-context agent, no implementation history) this project
  requires before any phase is declared frozen - not yet run for Phase 8.
- Commit and freeze - nothing from this phase is committed; the working tree still carries all of
  it uncommitted, HEAD unchanged at `86fa7bd`.

**Already done, for context (not remaining):** GSTR-3B and GSTR-9 models/builder/validator/JSON
mapping, GST Return Dashboard wiring (period selection, Prepare/Validate/Proceed to File),
PDF preview/print/share/download and CSV/JSON/GSTR-JSON export for all three return types, full
Android (`./gradlew testDebugUnitTest` - 772 tests, only the same 5 pre-existing unrelated
Robolectric-environment failures) and Python test suites passing, debug APK builds clean. Three
real defects were found via live device testing and fixed (uncommitted): a GSTR-3B/9 PDF "Total"
row that double/triple-counted non-additive sections; an "Eligible ITC" figure inflated 3x by a
JSON-mapping key collision with the shared `sumDeep` display utility; and an "Estimated Cash
Liability" figure that didn't implement the real CGST/SGST<->IGST cross-utilization rule (Rule
88A) and so overstated cash liability by exactly the ITC amount that should have offset it.

**Dependencies:** None architecturally - GSTR-1 foundation (Phase 8A) is already committed
(`958a376`), and Phase 7J (the UI/posting layer this phase reads from) is complete and frozen.
Blocked only by the explicit defer instruction above.

**Next exact step:** When resumed - post one real GST-bearing Sale and Purchase on an Account +
Inventory company and on a Service-type company (mirroring what was already done for Account-Only
+ Trading), then run the independent audit, then commit and freeze.

---

## 2. Post-Phase-8 Queued Fixes (identified during Phase 8 device testing)

**Status: DONE** (implemented ahead of Phase 8's own resumption, per explicit user instruction
overriding the original sequencing)

| # | What remains | Where | Status |
|---|---|---|---|
| a | Show the state **name** alongside the state **code** in every State Code field | Party/Ledger/Company state-code fields | **DONE** - `CreatePartyDialog.kt`/`CreateLedgerDialog.kt` already had this (pre-existing); added the two gaps found: `CreateCompanyDialog.kt` and `SettingsAndSyncScreen.kt`'s GST Details step now also show `Constants.GST_STATE_CODES[stateCode]` as supporting text |
| b | Make an existing ledger/party easy to find and fix when GST posting is blocked | `AccountingViewModel.kt` (both Sale/Purchase posting paths) + `MainAppScreen.kt` | **DONE** - a new `gstBlockedLedgerFixTarget` StateFlow is set the moment a Sale/Purchase is blocked for a missing State (alongside the existing snackbar message); `MainAppScreen` collects it and opens that exact party's ledger straight into the existing edit dialog (`CreateLedgerDialog`, already reachable via Chart of Accounts/Parties screens' pencil icon - reused, not rebuilt) |
| c | Make GST Registration status binary, derived from GSTIN presence, no "Unknown" reachable | `CreatePartyDialog.kt` (Add Customer/Add Supplier) | **DONE** - the Registered/Unregistered/Unknown three-way toggle is removed; `gstRegistrationStatus` is now a derived value (`gstin.isNotBlank() -> REGISTERED, else -> UNREGISTERED`, `null` only for Individual, unchanged) - the GSTIN field is always visible for a Business party now, not gated behind a "Registered" selection |
| d | Owe-direction should follow transaction type, not a free manual toggle | `TradingWorkflowEngine` / posting engine | **Already correct, no code change made** - confirmed by reading `TradingWorkflowEngine.buildAccountOnly`'s `partyLineType = if (isSale) DEBIT else CREDIT`, and the 2026-09-09 Corrections-track live test of a real Credit Note (`Dr Sales Account / Cr Customer` - the customer is correctly credited, i.e. "I owe them," on a sale return). The Opening Balance "They owe me"/"I owe them" toggle in `CreatePartyDialog.kt` is a separate, one-time manual figure entered at party creation (already correctly labeled, already defaults to "They owe me") - not the same thing as transaction-driven owe-direction, which was already right |

**Verification:** `./gradlew compileDebugKotlin`/`testDebugUnitTest` clean after (a)-(c) - 772
tests, same 5 pre-existing unrelated failures, zero new regressions. `assembleDebug` succeeds.
**Not yet verified live on-device** - no device was connected at the time these changes were
made; needs a fresh install + on-device walkthrough (create a Business party with/without a
GSTIN and confirm Registered/Unregistered follows correctly; trigger the State-blocked posting
path and confirm the ledger-edit dialog opens automatically; check the two newly-fixed state-name
fields).

**Dependencies:** None.

**Next exact step:** Live device verification of the four changes above, once a device is
connected.

---

## 2a. Phase 7J - Invoice + GST Integration

**Status: PENDING** (audit + sub-phase plan done, 2026-09-11; no implementation started -
explicitly "DO NOT CODE YET" per the instruction that produced it)

**What remains:** Bring full GST coverage (party Registered/Unregistered + GSTIN display,
Inclusive/Exclusive pricing, Place of Supply + CGST/SGST/IGST/CESS, live totals, Credit/Debit
Notes, inline validation) inside the existing Invoice Dashboard/Form (`QuickInvoiceEntryScreen.kt`
+ `TradingForm.kt`) - no popup/Toast/Snackbar/new screen. Full audit, gap list, and a 7-step
sub-phase plan (A-G, each small and sequential with explicit dependencies) are in
`docs/PHASE_7J_INVOICE_GST_INTEGRATION_PLAN.md`. Headline gaps found: Account-Only Sale/Purchase
entry has no Inclusive/Exclusive toggle, no live CGST/SGST/IGST breakdown before Save, and no Cess
input anywhere (the calculation engine already supports Cess end-to-end, nothing feeds it); Credit/
Debit Notes exist only in the older `CreateVoucherDialog` modal, not reachable from the Invoice
Dashboard.

**Dependencies:** None blocking - independent of Phase 8 and every other item in this file.

**Next exact step:** Sub-phase A (party GST identity display) per
`docs/PHASE_7J_INVOICE_GST_INTEGRATION_PLAN.md` section 3 - awaiting go-ahead to start coding.

---

## 3. Product Identity Corrections Directive ("THIS APPLICATION IS NOT AN ERP", 2026-09-09)

**Status: PARTIAL** - the 18-section directive's own execution plan (`docs/CORRECTIONS_README.md`
+ the 2026-09-09 "Product Identity Audit" entry in `docs/CORRECTIONS_LOG.md`) has some steps done
and some explicitly still open. The directive's own text warns: "do not assume any of section
1-18's fixes are already in place" - the breakdown below is what the log actually confirms one
way or the other.

**Done (not remaining, listed for traceability only):**
- Section 16 (repo-wide grep sweep for Dr/Cr/Staff-management/hard-coded-GSTIN/forced-inventory
  strings in user-facing UI) - completed, one real fix applied (see item 5 below), everything
  else swept was already correct.
- Section 1 (voucher/invoice duplication audit) - closed via the Phase 7J FREEZE's
  `postVoucherMutex` + stable draft idempotency key fix.
- Sections 2-3 (Invoice/Voucher UI must not show Dr/Cr by default) - closed:
  `VoucherDetailDialog` now shows a plain Bill view by default, with the Dr/Cr table gated behind
  an explicit "Advanced -> View Accounting Entries" toggle, verified live against all 9 voucher
  types (Sale/Purchase/Receipt/Payment/Contra/Credit Note/Debit Note/Journal).
- Section 5 (GSTIN single-source audit) - completed, zero hard-coded/duplicated GSTIN found.
- Section 6 (ERP-style Staff/Department UI removal audit) - completed, no working ERP admin
  feature exists (see item 5 below for the one borderline copy issue left open).
- Sections 13-14 (GST-mismatch UX: what/why/how-to-fix) - closed via the Phase 7J FREEZE's
  `GstErrorDetailsScreen` fix (now surfaces `Gstr1Validator`'s real per-issue message).

**Remaining:**
- **Section 4 - backend single-calculation-path audit.** Confirm `InvoiceSummaryCalculator`/
  `GstCalculationEngine`/`TradingWorkflowEngine` are still the *only* figures every consuming
  screen reads (the plan called this "already largely true - verify, don't assume"). Not yet
  explicitly re-confirmed in the log.
- **Sections 7-8 - exact-field validation messaging, revisited.** The plan flagged this session's
  own `emitMessage("Set up your business first.")` fix as the right *shape* but too generic (not
  field-exact) - explicitly noted as "likely needs revisiting under this rule," not yet done.
- **Sections 9-12 - inventory/non-inventory audit, final confirmation.** Spot-verified live
  during the plan (`isInventoryEnabled` gate, HSN/SAC genuinely optional in the non-inventory
  path) but never formally closed out as a checklist item the way sections 1/2-3/5/6/13-14 were.
- **Section 18 - the mandatory test matrix A-H + 10-point completion report.** Explicitly
  scheduled to run only "once the above audits/fixes land, on a real device" - has not run.
- One unresolved **product decision** (not a code fix): `SettingsAndSyncScreen.kt`'s
  `UsersStaffStep` placeholder card ("Staff accounts and permission roles aren't available yet...")
  telegraphs a future multi-staff roadmap that's arguably in tension with "not an ERP," even
  though it exposes no working feature today. Left as-is pending an explicit decision: remove the
  forward-looking promise, or leave it (low-exposure, tucked inside Settings).

**Dependencies:** None blocking - can resume independently of Phase 8. Section 18's test matrix
depends on sections 4 and 7-8 being closed first (per the plan's own stated order).

**Next exact step:** Section 4 (backend single-calculation-path audit) is the next unclosed step
in the plan's own original order (grep sweep -> duplication -> Dr/Cr UI -> **backend calc audit**
-> GSTIN -> ERP-UI -> profile/messaging -> inventory -> GST-mismatch -> test matrix).

---

## 4. Corrections Track - Smaller Open Items

**Status: PENDING / NEEDS VERIFICATION**

- **Live device re-verification of several already-built UI changes.** As of the last
  `CORRECTIONS_LOG.md` entry (2026-09-09, "MainAppScreen.kt recovered..."), the build was
  confirmed clean (`compileDebugKotlin`/`assembleDebug`) but explicitly **not yet re-verified live
  on a device** ("the user intends to check it themselves next"). This covers: the Favorite
  star flag on Customers/Suppliers, the "Rate on Play Store"/"Log Out" drawer additions, the
  rebuilt Dashboard (brand card + Quick Report tiles), and the Contacts-import flow.
- **`ReportUiModels.kt` (Trial Balance tree/Day Book selection/ratio-status wrapper types) is
  built and compiles but is not wired into any actual report screen yet.** Explicitly logged as
  "the next real step whenever that's wanted" - `ReportsCenterScreen.kt`'s Trial Balance/P&L/
  Balance Sheet/Day Book views still render without it.
- **"CMA Data" and "Project Report" Dashboard tiles were deliberately not added.** `CmaReportGenerator`
  has real generation logic with zero ViewModel/UI wiring; "Project Report" doesn't exist at any
  layer. Flagged to the user rather than faked - remains unwired until a decision is made.

**Dependencies:** None. Independent of every other item in this file.

**Next exact step:** A device-connected verification pass over the four already-built items in
the first bullet above, since no code changes are believed to be needed there - only confirmation.

---

## 5. Sandbox.co.in Sub-Service Real Implementations (Phase 7H's deferred half)

**Status: DEFERRED** (Phase 7H Module 1 - the architecture/contracts - is COMPLETE and FROZEN;
none of the six real sub-service implementations below have been started)

Per `domain/sandbox/README.md`: "No Kotlin file exists in any of them yet, and none should be
added until that service's own dedicated phase begins." Each has its own scope doc:

| Sub-service | Folder | What exists today | What remains |
|---|---|---|---|
| GST | `domain/sandbox/gst/` | `SandboxProviderAdapter.verifyGstin` contract only | GSTR-1/3B filing-status read-only checks; actual filing is a separate, deliberately unscoped write-side decision |
| Income Tax / 26AS | `domain/sandbox/income_tax/` | `fetchForm26As` contract only | Fetching/caching 26AS/AIS/TIS, TDS-ledger reconciliation (informational only), ITR pre-fill assistance |
| TDS/TCS | `domain/sandbox/tds/` | `Form26AsEntry` shape only, no dedicated adapter method | Deductor/TAN verification, 26Q/27Q filing-status checks, section-code (194C/194J/194Q) reference lookups |
| e-Invoice | `domain/sandbox/einvoice/` | `requestEInvoiceIrn` contract only | Real IRP payload assembly, persisting the returned IRN/QR against its Invoice, feeding the QR into the existing Phase 7D rendering pipeline, IRN cancellation |
| e-Way Bill | `domain/sandbox/ewaybill/` | Nothing - no contract exists yet | e-Way Bill generation, Part-B updates, cancellation/validity-extension |
| Statutory Audit Report | `domain/sandbox/audit_report/` | Nothing - no contract exists yet | A Form 3CA/3CB/3CD-style report, most likely mirroring `CmaReportGenerator`'s consume-the-already-computed-statements pattern |

**Dependencies:** Real Sandbox.co.in TEST/LIVE API credentials (explicitly never modeled in code -
would be stored via the existing `SecureStorage`), and for GST/e-Invoice/e-Way Bill specifically,
a written safety-scope decision for any write-side (filing/generation) action, matching the
discipline `docs/50_AUTOMATION_ARCHITECTURE.md` already established for FY-closing and bank
reconciliation, before any code is written.

**Next exact step:** None of the six is sequenced ahead of the others in any source document.
`verifyGstin` (GST sub-service) is the only operation whose full contract already exists with
nothing further to design, making it the smallest possible first real implementation if this
track is picked up.

---

## 6. Phase 7E Extension Point - Bulk Excel Export

**Status: DEFERRED** ("7E's own pre-planned, untouched extension point" - README, Phase 7J entry)

**What remains:** A bulk Excel export DTO/engine alongside the existing bulk JSON envelope and
RFC-4180 CSV engine (`docs/46_EXPORT_ARCHITECTURE.md` through `docs/49_GSTR_JSON_EXPORT.md`) for
Voucher/Party/Ledger/Invoice/report data. **Not to be confused with** the per-document Invoice
Preview CSV/Excel buttons already built and working (`ExcelExporter.kt`, a genuine hand-rolled
`.xlsx`, wired into `InvoicePreviewScreen.kt`'s toolbar) - that is a single-document export, not
the bulk data-interchange feature this item refers to.

**Dependencies:** None - the CSV/JSON bulk export architecture it would extend is already frozen.

**Next exact step:** Not scheduled; pick up only when explicitly requested, per 7E's own
documented "untouched extension point" framing.

---

## 7. Release Engineering ("Week 1" Play-Store-readiness track)

**Status: PARTIAL / PENDING** - referenced in this project's own session history as an
in-progress "one-month plan" running alongside the numbered Phases, but **no document defining
its full Week 1/2/3 scope exists anywhere in this repository** (searched `docs/`, `README.md`,
and `git log` - none found). The only evidence of its content is three commits, all labeled
"Week 1":

- `7110990` Week 1: phone/OTP login with SMS auto-read/auto-fill
- `f79c2ca` Week 1: confirm before Cloud Sync sign-out
- `86fa7bd` Week 1: glossy 3D animated splash screen with company intro

Several other, unlabeled commits sit in the same part of the history and look related to Play
Store readiness specifically (`068b727` rename `applicationId` off a placeholder package name,
`13dc543` fill in real publisher/contact details in Legal/Support, `8b060e9`/`189f28a`/`1b13747`
CI setup, `78197f7` Robolectric SDK-36/JDK-21 sandbox fix) but are not themselves labeled "Week N,"
so they are not claimed as part of this track here - listed only as adjacent, possibly-related
work for whoever picks this up.

**What remains:** Unknown/undocumented - Week 2 and Week 3 have no commits, no doc, and no
in-repo description of what they cover.

**Dependencies:** Whatever the original "one-month plan" specified - not recoverable from this
repository alone.

**Next exact step:** Get the original Week 1-3 plan (or its equivalent) from the user directly,
since it is not stored here, before resuming this track. This mirrors the same gap already
reported back to the user regarding a "Phase 9": the roadmap that names this track's full scope
lives outside the repository.

---

## Summary Table

| # | Item | Status |
|---|---|---|
| 1 | Phase 8 - GST Returns & Compliance | DEFERRED |
| 2 | Post-Phase-8 queued fixes (state name display, party findability, GST Registration binary, owe-direction) | DONE (code) - live device verification still pending |
| 2a | Phase 7J - Invoice + GST Integration (Account-Only Inclusive/Exclusive, live tax breakup, Cess, Credit/Debit Notes in Invoice Dashboard) | IN PROGRESS - Sub-phases A+B+C+D done (party GST identity, Inclusive/Exclusive, live CGST/SGST/IGST/CESS breakup for both Account-Only and item-mode, both Sale and Purchase); Credit/Debit Notes + broader inline field validation still pending |
| 3 | Product Identity Corrections directive (sections 4, 7-8, 9-12 close-out, 18) | PARTIAL |
| 4 | Corrections track - device re-verification + `ReportUiModels.kt` wiring + CMA/Project Report tiles | PENDING |
| 5 | Sandbox.co.in sub-service real implementations (GST/Income-Tax/TDS/e-Invoice/e-Way-Bill/Audit-Report) | DEFERRED |
| 6 | Phase 7E bulk Excel export | DEFERRED |
| 7 | Release engineering ("Week 1-3") | PARTIAL / PENDING, scope undocumented |
