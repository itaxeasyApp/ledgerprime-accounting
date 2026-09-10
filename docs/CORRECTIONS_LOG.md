# LedgerPrime Corrections - Running Log

Append-only. See `CORRECTIONS_README.md` in this same folder for the summary/index and standing
rules. New entries go at the bottom, dated. Don't rewrite or delete prior entries except to fix a
factual error.

## 2026-09-09

**Business identity display staleness (reported by user: "stale things are still didnot changed
after updating the profile").** Root cause: `Company` (statutory record, edited via Settings) and
`BusinessProfile` (document-branding, edited via Profile & Business Setup) are two separate
entities; several display-only UI locations read `Company` only, so a Profile edit appeared to
silently fail there. Fixed: Dashboard header (`AppTopBar.kt`), hamburger drawer header
(`AppDrawer.kt`), Settings root "My Business" row (`SettingsAndSyncScreen.kt`) - all now prefer
Business Profile, falling back to Company. Audited and confirmed correct-as-is (no change):
UPI payee name, GST Return Dashboard eligibility check, GST Declaration card, invoice QR
`SELLER_GSTIN`, and the invoice-rendering `sellerSnapshot` used by Invoice Preview - all either
already did this correctly or correctly must stay Company-only (statutory contexts). Full detail:
`57_BUSINESS_IDENTITY_DISPLAY.md`.

**Tablet navigation layout ("its not responsive on tablet the bottom bar is moved from bottom to
side agin... correct its padding layout etc to run on both").** Removed the `NavigationRail`
tablet-width switch entirely - `MainAppScreen` now always renders the bottom `NavigationBar`, on
phone or tablet. Removed the now-dead `AdaptiveNavigationType`/`getAdaptiveNavigationType`
(`HashRouter.kt`), `Breakpoints.kt` (deleted), and the `windowsizeclass` Gradle dependency
(commented out per this repo's existing convention). Verified live on the Samsung SM_T225 tablet:
bottom bar renders correctly at the bottom. Full detail: `58_SINGLE_NAVIGATION_LAYOUT.md`.

**Contextual OCR entry points.** Per explicit user direction, moved several scan types off the one
generic "Scan a Document" popup onto the screen where that document belongs: Sales Invoice -> Sales
screen (new FAB), PAN/Aadhaar/GST Certificate -> Profile & Business Setup (new card + its own
3-option dialog), Bank Statement -> Bank ledger list + Receive/Pay voucher entry (new header
icons). Expense Receipt was removed from OCR entirely (no contextual home was requested). Added a
new `GST_CERTIFICATE` document type (extraction reuses the existing identity-document heuristic;
applying it updates Business Profile's trade name/GSTIN only, never Company's statutory GSTIN -
same precedence rule as the business-identity fix above). Found and fixed a real defect during
verification: the generic center scan FAB was visually overlapping and partially covering the
"Receive Money"/"Pay Money" submit button on Money's voucher entry screen - fixed by hiding that
generic FAB on every route that now has its own contextual scan entry (Sales, Profile, Money).
Verified live on the tablet: Sales tab FAB, Profile card, Bank ledger icon, and the fixed
Receive Money screen (submit button no longer obscured) all confirmed working. Full detail:
`59_CONTEXTUAL_OCR_ENTRY_POINTS.md`.

**Drawer additions.** Added "Rate on Play Store" (real `market://details?id=<applicationId>`
intent with a web-URL fallback if the Play Store app isn't installed) and "Log Out" (delegates to
the existing `AccountingViewModel.logoutCloudSync()` - shown only when `isCloudSyncLoggedIn` is
true, since there's nothing to log out of otherwise). Not yet verified on-device (user asked to
hold off testing until told to).

**Favorite flag on Customers/Suppliers - done.** Added real, persisted `Party.isFavorite`
(domain model, `PartyEntity` column, `MIGRATION_28_29`, repository mapper +
`toggleFavoriteParty`, `AccountingViewModel.toggleFavoriteParty`). `PartiesScreen` now shows a
star icon per Customer/Supplier (tap to toggle) and sorts favorites to the top of the list;
wired through `SalesScreen`/`PurchasesScreen`/the standalone `AppRoute.Parties` route. Not yet
verified on-device (holding per the user's "don't test until I ask" instruction).

**Full unit test suite run - found and fixed 2 real regressions, found 2 pre-existing/unrelated
failures.** Running the full suite (not just the OCR-specific ones) for the first time in a while
surfaced:
- **Fixed (caused by this session's own earlier work):** forgot to bump `@Database(version = ...)`
  from 28 to 29 after adding `MIGRATION_28_29` - fixed. `Phase0TestSuite`'s hardcoded
  migration-count/description assertions were also already 2 migrations stale before today
  (missing 26->27 GST Settings refactor and 27->28 stock_movements CASCADE fix, neither added when
  those migrations were originally written) - caught up to all 28 migrations now. `Phase7ITestSuite`'s
  `testOcrIngestionAdapter_isAPureInterface_withExactlyOneOperation` broke when `documentTypeHint`'s
  default parameter value was added to `OcrIngestionAdapter.extractFromDocument` earlier this
  session (Kotlin emits a synthetic `extractFromDocument$default` bridge method) - fixed by
  filtering compiler-synthesized `$default` methods before the assertion.
- **Found, NOT fixed (pre-existing, unrelated to anything touched this session):**
  `D1aAccountOnlyTradingTestSuite.t7_ModeChange_NeverModifiesHistoricalVoucherOrJournalItems` fails
  on accounting-mode/historical-voucher logic nothing in this session's work touches; and 3 tests
  (`Phase7FRecurringVoucherPostingTest`, `Phase7JBVoucherPostingTest`,
  `SuspenseControlArchitectureTest`) crash with `DefaultSdkProvider`
  `UnsupportedOperationException`, a Robolectric/SDK environment issue, not a code defect. Flagging
  for separate investigation rather than fixing here, per "keep the commit focused."

**Contacts import on Add Customer/Supplier - blocked pending a policy decision, not yet built.**
The user asked for a real "import from device Contacts" action on the Add Customer/Supplier
dialog, plus the `READ_CONTACTS` permission it needs. Checked this project's own prior research
(`docs/30_CHANGELOG.md` line ~774, `docs/52_MANAGEMENT_ARCHITECTURE.md` line ~199) before building
anything, per the user's "check also in old relevant readme file" instruction. That research
already concluded: unlike the Photo Picker (zero permission needed), there is **no equivalent
permission-free picker for Contacts** - `READ_CONTACTS` is a Play Console **Restricted Permission**
requiring its own justification form, a mandatory Privacy Policy URL, and a Data Safety form
update once shipped. This is a real publishing-process decision, not just code, so it's not being
implemented until the user confirms they still want it knowing that. See the question posed back
to the user in-conversation.

## 2026-09-09 (continued) - Contacts, field validation, UPI payment QR

**Contacts import - done, user accepted the Play Store Restricted Permission requirement.**
Added `READ_CONTACTS` to the manifest (with an explanatory comment), a proper in-app rationale
dialog shown before the OS permission prompt (never requested cold), and a real contact picker
(`ActivityResultContracts.PickContact()` + a `ContactsContract` query resolving the picked
contact's real display name and phone number - `MainAppScreen.resolveContactNameAndPhone`).
Wired into `CreatePartyDialog` via a new "Import from Contacts" icon in its header; only fills
Name/Phone fields that are still blank, never overwrites what the user already typed.

**Field validation - real gaps found and fixed.** Audited every form collecting Phone/Email/PAN/
GSTIN; before this, only GSTIN had any format validation, and only in `CreatePartyDialog`. Added
one shared, pure validator (`core/common/ContactFieldValidation.kt`: `isValidIndianMobile`,
`isValidEmail`, `isValidPan`) and applied it - plus GSTIN validation via the existing
`GSTRules.isValidGSTIN` - everywhere these fields are collected: `CreatePartyDialog` (Add
Customer/Supplier), `CreateCompanyDialog` (the very first data the app ever collects),
`CreateLedgerDialog`, `ProfileScreen`'s Business Profile and Individual Profile sections, and
Settings' `GstDetailsStep`/`ContactDetailsStep` (the *authoritative* statutory GSTIN/PAN/phone/
email edit path - the one place validation matters most, GST filing and e-invoice QR both read
these fields directly). Every field stays optional where it already was; only a non-blank value
that isn't shaped like a real one is now rejected (red border + explanatory text + submit
disabled), matching the existing GSTIN pattern.

**UPI payment QR on invoice PDFs - real gap found and fixed.** The PDF renderer
(`PdfDocumentRenderer.kt`) drew bank details as plain text but never checked
`DocumentPaymentInfo.upiId` at all - a business with a UPI ID on file got no QR code on its
invoices, ever, regardless (the only QR path that existed, `DocumentBrandingSnapshot.qrCodeStorageReference`,
required manually uploading a QR *image*, never auto-generating one). Fixed: when
`upiId` is non-blank, the renderer now auto-generates a real `upi://pay?pa=...&pn=...&am=...&cu=INR&tn=...`
deep-link QR code (zxing `QRCodeWriter`, same library the app's on-screen `QrCodeImage` composable
already uses) encoding the invoice's own real payee name and grand total, and draws it under the
Bank Details section as "Scan to Pay (UPI)". Not a placeholder or static image - a genuine,
scannable payment QR generated from data the user already provided (their Business Profile's UPI
ID) - and the amount changes correctly per invoice.

**Verification:** all of the above compiles clean; full unit test suite re-run afterward - still
exactly the same 7 pre-existing/unrelated failures as the previous run (no new regressions from
this batch). Not yet verified on a real device (holding per the user's "don't test until I ask"
instruction) - in particular the UPI QR should be visually confirmed scannable on a real device
before considering this fully done.

## 2026-09-09 (continued 2) - OCR popup retired, Purchases scan, voucher warnings

User feedback: "sometimes u dont follow the prompt why?" - noted. Being more literal below rather
than filling ambiguity with my own "safe default" guesses.

**Generic "Scan a Document" popup retired entirely.** Every OCR type now has a contextual home
(Sales, Purchases, Profile, Money), so there was nothing left for the old 8-then-3-option generic
popup + center bottom-bar FAB to offer once "Other Document" was removed per explicit instruction.
Removed the FAB, the popup, and `pendingScanDocumentType`'s now-unnecessary picker-open state;
`ScanDocumentTypeDialog`/`ScanProfileDocumentTypeDialog` merged into one reusable
`ScanTypePickerDialog(options: List<Pair<String, OcrDocumentType>>, ...)` component.

**Purchase Bill scan added to the Purchases screen** - mirrors Sales' own FAB exactly (bottom-start
scan icon on the "Purchases" tab). Its dialog offers Purchase Bill + UPI Payment (see below).

**UPI Payment scan added to Sales, Purchases, and Money's Receive/Pay voucher entry.** Rather than
a fixed single type per screen, each of these screens' own scan icon now opens a small 2-option
`ScanTypePickerDialog`: Sales offers Sales Invoice/UPI Payment, Purchases offers Purchase Bill/UPI
Payment, Receive/Pay's header icon (previously Bank Statement only) now offers Bank Statement/UPI
Payment. The Bank ledger list's own icon stays Bank Statement only (unchanged - a UPI screenshot
isn't tied to a specific bank ledger the way a bank statement is).

**OCR review is now a real modal dialog, not embedded in one screen.** Real gap found while doing
the above: the review step (`OcrReviewCard`) only ever rendered inline on Data Tools, but scans now
start from Sales/Purchases/Profile/Money - a scan from Sales had no visible way to show its result
without the user manually navigating to Data Tools first. Moved it to a new
`presentation/components/OcrReviewDialog.kt`, shown as a modal `Dialog` from `MainAppScreen`
whenever `AccountingUiState.lastOcrExtraction` is non-null, regardless of current screen. Data
Tools' "Scan Document" section and its `onPickReceiptPhoto`/`lastOcrExtraction`/etc. parameters
were removed - it's now CSV/JSON import only, matching its own remaining scope.

**New Purchase/Sale voucher - ledger-selection warning.** `CreateVoucherDialog`'s existing generic
"Complete the fields to continue" status line is now specific when a trading voucher (Sale/
Purchase) is blocked because no Customer/Supplier or no Sales/Purchase Account is selected yet -
"Select a Supplier to continue" / "Select a Purchase Account to continue" instead of the vague
generic message. Checked first (per the "why don't you follow the prompt" feedback, verifying
before building): `TradingForm` already has both a "+ Add New Customer/Supplier" inline creation
option in the party dropdown and a "No {Sales/Purchase} account exists yet" banner when the trade
ledger doesn't exist at all - both were already real and working, not fake; only the missing-party
warning specifically was actually absent, now fixed.

**Test suite note - root-caused an environment issue.** Two additional Robolectric test failures
seen in one run (`ExampleRobolectricTest`, `GreetingScreenshotTest`) trace to
`"Android SDK 36 requires Java 21 (have Java 17)"` - a JDK version mismatch on this dev machine,
same root cause as the other Robolectric `DefaultSdkProvider` crashes already logged above (not a
code defect, and not touched - fixing it means reinstalling a JDK, a system-level change out of
scope here). Full suite re-run after this batch: still only the same pre-existing D1a + Robolectric-
environment failures, no new regressions from any of the above.

## 2026-09-09 (continued 3) - Real "not saving" bug root-caused and fixed, PAN/GSTIN feature added

**Root cause found for "Business profile is not working not saving too... its not taking auto
Business setup wizard... logo and signature is also not uploading nor saving."** Reproduced live
on the tablet (fresh device, no business created yet): `updateBusinessProfile`,
`updateBusinessProfileFull`, `uploadBusinessBrandingAsset`, and `updateIndividualProfile` all
guarded on `_uiState.value.currentCompany ?: return@launch` - with **zero feedback** on the null
branch. On a device with no business yet, every field in Profile & Business Setup is fully
interactive and every Save/Upload button is enabled, but tapping them did *nothing at all*,
silently. This is indistinguishable from "the button is broken." Fixed: all four now emit
"Set up your business first." on that branch - confirmed live (see screenshot flow: typed a trade
name, tapped Save, snackbar appeared). `updateBusinessProfileFull` also previously gave no
feedback even on real success (silent on the wizard's every "Next"/"Finish" tap) - now emits
"Saved".

**"Not taking auto Business setup wizard" - real gap, fixed.** `createCompany` previously left the
user on the Dashboard after creating a business, with the guided Wizard only reachable by
manually finding it under Profile & Business Setup. Now `createCompany`'s success path navigates
straight into `AppRoute.ProfileWizard`. Confirmed live: creating "TestBiz2Co" landed directly on
"Step 1 of 7 - Business", not the Dashboard.

**GSTIN -> PAN auto-extraction - new feature, confirmed live.** A GSTIN's characters 3-12 (after
the 2-digit state code) *are* the holder's real PAN by law. Added `ContactFieldValidation.extractPanFromGstin`
and wired it (auto-fills PAN only while PAN is still blank - never overwrites a typed value) into
every screen with both fields: Business Profile, Business Setup Wizard, Create Company, Create
Ledger, Settings > GST Details. Confirmed live: typing GSTIN `27ABCDE1234F1Z5` auto-filled PAN
`ABCDE1234F` instantly, no red error border.

**PAN structural validation - added, then corrected per user feedback.** PAN's 4th character
encodes holder type by law; added `isValidPanForHolderType` and wired Individual->'P',
HUF->'H', Company->'C' checks (the wizard's own Constitution Type is used where available). Real
correction from the user while testing live: the 5th-character rule is **not** a generic "surname"
for every holder type - that's an Individual-only rule (a person's surname). A **Company's** PAN
5th character is the first letter of the *company's own registered name*, never a surname (there
is no person's surname on a Company PAN). Fixed by splitting into two distinct functions -
`panSurnameLetterMatches` (Individual/Proprietorship only, checked against Legal Name's last word)
and `panEntityNameLetterMatches` (Company only, checked against Legal Name's first letter) - wired
into the Wizard's GST_TAX step with distinct, correct error messages for each.

**Correction on OCR permissions.** The user separately asked to "add permission... for ocr too."
Checked: there genuinely isn't one to add. ML Kit's on-device text recognizer and the Android
Photo Picker both need zero runtime permission by design (the whole reason the Photo Picker was
chosen originally for this feature). No permission was added for OCR since none is actually
required - noting this here instead of adding a fake/unused manifest entry just to look complete.

**PAN 5th-character validation - fully retracted.** User's next instruction after the Company-vs-
Individual correction above: "still problem of fifth charecter i think dont apply vaildation for
fifth charecter ok." Removed `panSurnameLetterMatches`/`panEntityNameLetterMatches` from
`ContactFieldValidation.kt` entirely, and their call sites/error text in `ProfileScreen.kt`
(Individual Profile) and `ProfileWizardScreen.kt`. Only the 4th-character holder-type check
(`isValidPanForHolderType`) remains anywhere. Left a comment at the deletion site so this isn't
silently re-added later without the user asking again.

**PIN code lookup complaint (Individual Profile/Ledgers) - investigated, no code defect found.**
User reported: "in individual profile the pin code api is not there and also on ledgers." Traced
`AddressPinCodeFields.kt` (the shared component both `IndividualProfileSection` and
`CreateLedgerDialog.kt` use) and its backing `PostalPinCodeLookupAdapter.kt` - confirmed both are
real, wired correctly, and hit India Post's live public API on every 6-digit entry via their own
internal `LaunchedEffect(pinCode)` trigger. No divergence from the Business Profile path (which the
user did not report as broken) found. Not yet resolved: what the user is actually seeing on-device
(spinner that never resolves vs. literally nothing happening) hasn't been confirmed - needs a
follow-up description or screenshot before further changes here.

**Individual Profile data wired into invoice PDF - proprietor's signature line.** Root cause of "its
not going into pdf and csv": `IndividualProfile` was saved to the DB but never read by any
render/export path - confirmed via grep, it appeared only in the domain model and profile-editing
code. First piece fixed, per explicit follow-up ("wire it into the invoice PDF, proprietor's
signature line"): added `signatoryName` to `DocumentBrandingSnapshot`
(`domain/rendering/DocumentData.kt`), populated in `AccountingRepository.brandingSnapshot()` from
`dao.getIndividualProfile(companyId)?.name` - this one shared function already feeds both
`assembleDocumentData` and `assembleDocumentDataFromVoucher`, so Invoices, TradeDocuments, and
posted Sale/Purchase vouchers all pick it up with no per-caller change. `PdfDocumentRenderer.kt`
now prints the proprietor's name as a line above the existing "Authorised Signatory" label,
directly under the signature image/line, only when an Individual Profile exists (falls back to the
plain label otherwise, never a fabricated name). CSV export does not carry document branding at
all today (it's a data export, not a rendered document) - the user's "and csv" half of the original
report has not been addressed yet and needs a separate, explicit decision on what an Individual
Profile field would even mean in a CSV row before building anything.

## 2026-09-09 (later same day)

**LedgerPrime product logo - created and wired into PDF/Print/Share (never JSON).** New
`res/drawable/ic_ledgerprime_brandmark.xml`: an opaque, self-contained version of the same
double-entry-ledger mark the launcher icon already uses (`ic_launcher_foreground.xml`/
`ic_launcher_background.xml`) - one consistent brand mark for the whole product, not a second
design invented for documents. `PdfDocumentRenderer.kt` rasterizes it via
`ContextCompat.getDrawable` and draws it centered in the header on every template, under a
"LedgerPrime" caption - the JSON renderer is untouched (no branding concept there) and the
Compose on-screen preview (`InvoicePreviewScreen`) is untouched (a distinct, deliberately
non-pixel-identical preview, per its own existing KDoc). Verified live on a real device: the mark
renders correctly on an actual system Print Preview of a posted Sales Invoice.

**Invoice layout redesign, per the user's explicit field-by-field spec.** In
`PdfDocumentRenderer.kt`: seller's own business name/GSTIN/state moved to upper-right (was
upper-left in the Plain/default template) - "branding name of the user on the header upper right";
added a centered "TAX INVOICE" title (literal text, only for `SALES_INVOICE`/`PURCHASE_BILL` - a
Credit/Debit Note keeps its own real name, since it is not legally a tax invoice) drawn right after
the header on every template, replacing the old per-style "document type" label (removed from
Banner/Centered headers to avoid a duplicate title); added a conditional Ship Date/Dispatched On
line (`DocumentData.shipDate`/`dispatchDate` - real, pre-existing, always-null-today extension
points per that class's own KDoc, so nothing fabricated - the line only appears once a future phase
adds the underlying field); added a "Subject to `<Seller's State>` Jurisdiction Only" line after
Terms & Conditions, sourced from the seller's already-resolved state (never a fabricated city).
Mandatory fields already present and confirmed still correct: seller+buyer full details, invoice
no/date, HSN/SAC, qty, taxable value, discount (shown only when non-zero, never forced), multiple
line items, taxable value total, correct CGST+SGST vs IGST split by interstate/intrastate (verified
live: a Maharashtra seller vs Delhi buyer correctly showed IGST only), invoice value, bank details,
signature panel, UPI QR. Verified live end-to-end: enabled "Track Inventory" for a test company
(Settings > My Business > Invoice/Business Preferences - account-only businesses have no
item-level invoice, a real pre-existing product distinction, not a bug), created a real stock item,
posted a real Purchase Bill and a real interstate Sales Invoice, and inspected the actual generated
PDF via the system Print Preview - every element above rendered exactly as specified.

**PDF/CSV/Excel now a real, wired-in choice on Invoice Preview - "show me UI for PDF CSV Excel".**
`domain/rendering/CsvExporter.kt`/new `ExcelExporter.kt` were never called from any screen before
this (confirmed by grep - `CsvExporter` was dead code). Added `shareInvoicePreviewCsv()`/
`shareInvoicePreviewExcel()` to `AccountingViewModel.kt` (same file-then-share pattern
`exportVoucherAndShare` already uses) and two new "CSV"/"Excel" text buttons next to Print/Share
in `InvoicePreviewScreen.kt`'s top bar (plain `TextButton`s, not icons - this app only depends on
`material-icons-core`, not `-extended`, so there's no `TableChart`/`GridOn` icon to reach for).
`ExcelExporter` is a genuine hand-rolled `.xlsx` (a real Office Open XML Zip package, inline-string
cells, no shared-strings table) - never a CSV file renamed with an `.xlsx` extension; no Apache POI
dependency (too heavy for Android). Verified live: both buttons render correctly in the Invoice
Preview toolbar on a real posted invoice.

**CSV/Excel branding decision - Individual Profile's proprietor name.** Resolved the explicitly
deferred item from the entry above: `DocumentBrandingSnapshot.signatoryName` (already wired for
the PDF signature line) is the one Individual-Profile-derived fact with a well-defined meaning in a
tabular export - who signed for the seller. Both `CsvExporter` and `ExcelExporter` now open with
Document Type/No/Date, Seller, Buyer, and a "Proprietor / Authorised Signatory" row whenever an
Individual Profile exists (never a fabricated row when one doesn't) - before this, the per-document
CSV had zero identity information in it at all, only bare line items.

**PIN code lookup bug - root-caused via live reproduction and fixed (not a phantom).** User
reported, still generic: "nothing happing when i type 6 digit pin code." Reproduced live: typed the
same PIN code into Individual Profile that Business Profile had already resolved moments earlier -
City/State never filled, no spinner, no error. Root cause: `AccountingViewModel.pinCodeLookupResult`
is one shared `StateFlow` field feeding every Business/Individual Profile section and every open
Create Ledger dialog; `MutableStateFlow` never emits a value that `equals()` the value it already
holds. A second lookup resolving to the exact same result (a cache hit on a repeated PIN - the
ordinary case for a sole proprietor whose home and business address share a PIN code) produced a
structurally-identical `PinCodeLookupResult`, so the second screen's own
`LaunchedEffect(pinCodeLookupResult)` silently never re-ran. Fixed by adding a `requestId: Long`
to `PinCodeLookupResult` (`domain/profile/PinCodeLookup.kt`), stamped with a fresh value on every
single `lookupPinCode()` call (cache hit or not) - it carries no real-world meaning, purely forces
the `StateFlow` to always emit. Verified live: the exact repro (474002 typed into Individual Profile
right after Business Profile already resolved it) now correctly fills City "Gwalior" / State
"Madhya Pradesh".

## 2026-09-09 (later same day) — Product Identity Audit: directive received, plan drafted

User sent a long, explicit "THIS APPLICATION IS NOT AN ERP" correction (full text preserved in the
conversation, condensed onto `CORRECTIONS_README.md`'s new "Product identity correction" section -
read that first). Core complaint: prior work (and generic instinct) risks treating this Vyapar-style
Indian small-business app like a Western ERP - exposing Dr/Cr ledger mechanics in normal invoice UI,
risking duplicate voucher posting, hard-coded/duplicated GSTIN, ERP-style Staff/User-management
screens, forcing inventory fields on service businesses, and vague error messages instead of exact
fix-it guidance. Eighteen numbered sections, ending in a required test matrix (A-H) and a mandatory
completion report. **Nothing in this entry has been fixed yet - this is the audit plan only**, per
the user's own instruction ("make its small prompt in the read me and plan it"), not an execution.

### Execution plan (in this order - each phase is read-only audit first, fix only what's found)

1. **Repo-wide grep sweep** (section 16) for: `Dr.`/`Cr.`/`Debit`/`Credit ledger` in
   `presentation/features/**` (excluding domain/data layers, where these terms are legitimate),
   "Journal Entry"/"Journal Lines"/"Select Ledger" in invoice/voucher screens specifically, "Staff
   Management"/"User Management"/"Add Staff"/"Employee"/"Department" anywhere, hard-coded GSTIN
   literals (`\d{2}[A-Z]{5}\d{4}[A-Z]\d[A-Z]Z\d` outside test fixtures), and "Inventory required"/
   "Item required"/"HSN required"/"SAC required" validation strings. Record every hit with a
   verdict: legitimate internal accounting engine code (keep) vs. leaked into user-facing UI (fix).
2. **Voucher/invoice duplication audit** (section 1) - trace every path that can end in a posted
   Voucher: `CreateVoucherDialog`/`TradingForm` → `TradingWorkflowEngine`; OCR review → apply →
   post; CSV/JSON import → create; edit-then-repost; draft-then-post. For each, confirm there is one
   stable source-transaction id gating a single post (not invoice number alone) and that retry/
   re-save/re-open cannot double-post. This session's own OCR/CSV work (Sept 2026) is in scope.
3. **Invoice/Voucher UI audit** (sections 2-3) - `CreateVoucherDialog.kt`, `TradingForm.kt`, the
   posted-voucher detail dialog (`Particulars/Ledger, Debit, Credit` table seen live on-device this
   session at `INV-2026-0001`/`INV-2026-0002`'s detail view) - this is exactly the kind of Dr/Cr
   table section 2 prohibits in "normal" UI; needs a design decision (relabel as a plain Bill
   summary vs. gate behind an explicit "View Accounting Entries" advanced action) before touching
   code, since a real feature already reads this screen (Delete/Correct Voucher/Preview/QR). Ask
   before changing this screen's default view.
4. **Backend single-calculation-path audit** (section 4) - confirm `InvoiceSummaryCalculator`/
   `GstCalculationEngine`/`TradingWorkflowEngine` remain the only figures used by every consuming
   screen (already largely true per this session's `DocumentData` KDoc contract - verify, don't
   assume).
5. **GSTIN single-source audit** (section 5) - grep every `.gstin` read site; confirm each resolves
   through `Company`/`BusinessProfile` (per the existing precedence rule, `57_BUSINESS_IDENTITY_
  DISPLAY.md`) and never a literal/cached copy. Cross-check the GST Dashboard, invoice header/QR,
  and reports.
6. **ERP-UI removal audit** (section 6) - grep for Staff/User-management/Department screens in
   `presentation/navigation`/drawer/settings; this codebase's Settings already looked lean on-device
   this session ("Users / Staff - Not enabled - you're the only user on this business") - confirm
   that's a real disabled-state, not a partially-built enterprise feature to strip further.
7. **User Profile vs Business Profile audit** (section 7-8) - confirm `IndividualProfile` (the
   person) and `BusinessProfile`/`Company` (the entity) are the only two sources, each read from one
   place; audit every workflow failure path for exact-field/why/how-to-fix messaging (section 8) -
   this session's own `emitMessage("Set up your business first.")` fix is a first example of the
   right shape but is generic, not field-exact - likely needs revisiting under this rule.
8. **Inventory vs Non-Inventory audit** (sections 9-12) - already partly verified live this session
   (`isInventoryEnabled` gate in `TradingForm`/`CreateVoucherDialog`, confirmed both an account-only
   and an item-level Sale/Purchase path exist and work); confirm HSN/SAC is genuinely optional in
   the non-inventory path (it currently is, per `accountOnlyHsnSacInput`) and re-verify no path
   forces Item/Qty/Rate on a non-inventory business.
9. **GST mismatch UX audit** (sections 13-14) - locate existing GST validation/mismatch surfaces
   (`GstReturnDashboardView`'s `onFixNow`, `GST Return Dashboard`'s Pending/Ready status seen live
   this session) and confirm each mismatch states what/why/how-to-fix with a direct action, per
   section 14's exact template.
10. **Test matrix A-H** (section 18) - run once the above audits/fixes land, on a real device per
    this project's existing device-testing convention, and produce the mandated 10-point completion
    report (duplicate findings, every Dr/Cr exposure changed, GSTIN sources found, inventory/
    non-inventory verified, GST mismatch verified, User JSON fields audited, exact-remedy examples,
    ERP-UI removed/retained with justification, build result, test result).

**Not started yet.** Next step is phase 1 (repo-wide grep sweep) unless the user redirects.

## 2026-09-09 (later same day) — Phase 3 done out of order: VoucherDetailDialog no longer shows Dr/Cr by default

User said proceed without waiting on the design question phase 3 raised, with an explicit spec:
plain Bill by default, real double-entry unchanged internally, Dr/Cr moved behind an explicit
"Advanced -> View Accounting Entries" action, Preview/Share/Print/Correct/Delete/QR preserved, no
duplicate posting. Implemented and verified live - this is a genuine fix, not a rename:

- New `domain/accounting/VoucherBillSummary.kt` - a plain-business shape (party label/name,
  taxable/CGST/SGST/IGST/CESS, total), no ledger concept in it at all.
- New `AccountingRepository.getVoucherBillSummary(voucher)` - fallback bill data for whatever
  `assembleDocumentDataFromVoucher` doesn't cover (account-only Sale/Purchase, or any non-trading
  type). Party identity is read straight off the voucher's own already-posted `JournalItem`s (Dr
  Customer/Cr Supplier - the same convention `TradingWorkflowEngine` itself posts by, e.g.
  `buildAccountOnly`'s `partyLineType = if (isSale) DEBIT else CREDIT`) - a pure, synchronous-safe
  read, not a guess. GST breakdown comes from this voucher's own `GstTransactionEntity` rows, which
  exist for account-only-with-GST postings even without stock lines - never recomputed.
- `AccountingViewModel.loadVoucherBillDetails(voucher)`/`clearVoucherBillDetails()`: for a Sale/
  Purchase, tries `assembleDocumentDataFromVoucher` first (the exact same single source of truth
  Invoice Preview and the PDF already use - so this view can never disagree with either); falls
  back to `getVoucherBillSummary` otherwise. Wired into `MainAppScreen.kt`'s existing
  `selectedVoucherDetail` flow via a `LaunchedEffect`, cleared on dismiss.
- `VoucherDetailDialog.kt`: default view is now a new private `BillSummarySection` composable -
  Party (Customer/Supplier/Received From/Paid To/Transfer, never a ledger name), real Items table
  with HSN/Qty/Rate/Discount/GST% when item-level data exists, Taxable/CGST-SGST-or-IGST/CESS/
  Total. The original "Particulars/Ledger, Debit, Credit" table is untouched in code, just gated
  behind a new `showAccountingEntries` toggle ("Advanced ▸ View Accounting Entries" / "Hide
  Accounting Entries") - starts collapsed on every fresh open.
- Verified live on-device against all three real vouchers already posted this session:
  `INV-2026-0002` (item-level Sale, interstate) - shows Customer/Item/HSN/Qty/Rate/IGST/Total by
  default, correctly matches the PDF; toggling Advanced shows the exact same 3-line Dr/Cr table as
  before. `PUR-2026-0001` (item-level Purchase, intrastate) - Supplier/Item/CGST+SGST/Total, same
  toggle behavior. `INV-2026-0001` (account-only, No GST) - Customer/Total only (no fabricated GST
  line), Advanced toggle still present. Purchases(1)/Sales(2) counts unchanged after all this
  navigation - confirms no duplicate posting from the UI change (expected: this was a pure display
  change, no posting code touched).
- Not yet covered: Receipt/Payment/Contra vouchers weren't re-verified live this pass (no live test
  data currently posted for them) - the `getVoucherBillSummary` logic for those three types should
  be spot-checked next time one exists. Credit/Debit Notes and Journal/Reversing Journal/Stock
  Journal intentionally fall back to "no party line, Total only" (`identity = null` in
  `getVoucherBillSummary`) rather than a guessed label - a real follow-up, not a bug, if those types
  need their own plain-bill framing later.

## 2026-09-09 (later same day) — Receipt/Payment/Contra/Credit Note/Debit Note/Journal tested live with real posted data

Per explicit instruction, tested all six remaining voucher types on-device with real posted
transactions (not guessed) before moving to Phase 1. Two real bugs were found and fixed in
`AccountingRepository.getVoucherBillSummary` as a direct result - not deferred, since fixing them
is what "verify with real data" actually means here.

**Receipt (RCT-2026-0001, ₹3950 via UPI, allocated against both open invoices)** - default view
correctly showed "Received From: Test Buyer" / "Total: 3950.00"; Advanced toggle correctly revealed
Dr Cash in Hand / Cr Test Buyer. Correct, no changes needed.

**Payment (PMT-2026-0001, ₹2000 via BANK, partial payment against the Purchase Bill)** - default
view correctly showed "Paid To: Test" / "Total: 2000.00"; Advanced toggle correctly revealed Dr Test
/ Cr Cash in Hand. Correct, no changes needed.

**Contra (CTR-2026-0001, ₹500, "From Account: Cash in Hand" / "To Account: Primary Bank Account" as
entered) - real bug found and fixed.** Default view showed "Transfer: Primary Bank Account -> Cash
in Hand" - backwards from what the user actually entered. Root cause: `CreateVoucherDialog.kt`
binds its own "From Account" field to `debitLedgerId` and "To Account" to `creditLedgerId`
(confirmed by reading that file - this is the app's own established Contra convention, matching
Tally's To/By style, not a bug in that dialog); `getVoucherBillSummary`'s Contra branch had this
backwards (used Credit as "from", Debit as "to" - textbook double-entry phrasing, but inconsistent
with how the rest of the app already uses these exact words for this exact voucher type). Fixed by
swapping to match: From = Debit line, To = Credit line. Re-verified against the same underlying
Dr/Cr entries (Dr Cash in Hand 500 / Cr Primary Bank Account 500) - not yet re-verified via a fresh
app install, since `MainAppScreen.kt` does not currently compile (see below); verified by re-reading
the fixed code against the real posted entries already captured on-device.

**Credit Note (CRN-2026-0001, full return against INV-2026-0001) - real bug found and fixed.**
Default view showed only "Total: 1000.00" with no party line (Credit Note wasn't handled in the
`when` branch - the documented "intentional, not yet handled" fallback from the previous entry).
Advanced toggle showed the real entries: Dr Sales Account 1000 / Cr Test Buyer 1000 - confirming
live that a Credit Note reverses a Sale by crediting the customer (opposite of a Sale's own
Dr Customer). Added `VoucherType.CREDIT_NOTE -> "Customer" to <credit-side ledger>`.

**Debit Note (DRN-2026-0001, full return against a Purchase Bill) - two real bugs found and fixed.**
(1) Same missing-identity gap as Credit Note - Advanced toggle showed Dr Test (supplier) 7080 /
Cr Purchase Account 6000 / Cr Input CGST 540 / Cr Input SGST 540, confirming a Debit Note reverses a
Purchase by debiting the supplier (opposite of a Purchase's own Cr Supplier). Added
`VoucherType.DEBIT_NOTE -> "Supplier" to <debit-side ledger>`. (2) Default view's "Amount" line
showed **-6000.00** (negative) before the party-line fix even applied - `GstTransactionEntity`
stores a reversal document's taxable/CGST/SGST/IGST/CESS figures as negative internally (for correct
net GST-return aggregation across normal + reversal rows), which `getVoucherBillSummary` summed
raw. Fixed by taking `.abs()` on all four GST figures plus the taxable amount - a returned/adjusted
value is a real, positive fact to a non-accountant reading a plain Bill; the sign is a GST-ledger
internal concern that must never surface in this view. (Needed creating a second Purchase Bill,
PUR-2026-0002, to have enough unconsumed stock for the return - the first bill's stock had already
been partly sold to Test Buyer; the resulting `INSUFFICIENT_STOCK` rejection on the first attempt
was itself a correct, working safety guard, not a bug.)

**Journal (JRN-2026-0001, ₹100, CESS A/c <-> Test Buyer)** - reached via Money tab's explicit
"Advanced > Journal Entry" section ("Manual accounting adjustment - for users who understand debit
and credit") - confirms Journal is already correctly gated behind an advanced/manual area per the
product correction's own section 3, not exposed in normal navigation. Default view correctly showed
no party line (Journal has no stable customer/supplier concept), "Total: 100.00", Advanced toggle
correctly revealed Dr CESS A/c 100 / Cr Test Buyer 100. Correct, no changes needed - `identity=null`
for Journal remains the right behavior, not a gap.

**Preview/Share/Print/Correct/Delete/QR, and no-duplicate-posting, confirmed across all six.** Every
type showed its correct existing actions (Correct+Delete for Receipt/Payment/Contra/Journal;
Delete-only for the Notes, which have their own linked-reversal correction mechanism instead of
"Correct"; no Preview/Share/QR for any of these six, correctly - that's Sale/Purchase-only). Sales/
Purchases/Returns counts stayed exactly as expected after every post (no navigation-triggered
duplicate), and the two extra Purchase Bills/stock top-ups created purely to unblock the Debit Note
test were deliberate, tracked test data, not accidental duplicates.

**Blocker found and NOT fixed (out of scope, unrelated, actively in progress by the user):
`MainAppScreen.kt` does not currently compile.** `git diff --stat` shows 667 insertions/747
deletions, uncommitted. `./gradlew compileDebugKotlin` fails with unresolved references
(`copyUriToCacheFile`, `resolveContactNameAndPhone`, `queryDisplayName`, `viewRepo`) and a syntax
error at line 1041 ("Expecting '}'"). This is consistent with the user actively restructuring this
file by hand (they opened it in their IDE this same session and separately asked for a new
`InvoicePreviewDialog.kt` component) - not touched, not reverted, per standing instruction to flag
rather than fix another actor's in-progress edit. Practical effect: all six voucher-type tests above
were run against the already-installed APK (built before this edit began, still fully functional);
the two `AccountingRepository.kt` fixes from this entry (Contra direction, Credit/Debit Note
identity + sign) are committed to source but **not yet re-verified via a fresh install** - that
needs to happen once `MainAppScreen.kt` compiles again.

## 2026-09-09 (later same day) — Phase 1: repository-wide grep sweep (findings, no fixes applied yet)

Read-only audit per the drafted plan's phase 1 - every hit inspected and given a keep/fix verdict.
No code changed in this entry; findings below are ready for prioritization.

**Dr./Cr./Debit/Credit sweep across `presentation/`:**
- `ChartOfAccountsScreen.kt`, `ReportsScreen.kt` (Trial Balance/Balance Sheet/GSTR-3B Debit/Credit
  column headers and "Input Tax Credit" labels) - **keep**. These are standard financial-statement
  screens (Trial Balance, P&L, Balance Sheet, GST returns) that even Vyapar-style apps show verbatim
  with Dr/Cr columns - not exposed during normal invoice/voucher creation, and removing them would
  make the reports themselves useless to an actual bookkeeper/CA reviewing the business.
- `VoucherDraftReviewScreen.kt` (Debit/Credit FilterChips) - **keep**. This is the OCR/import
  draft-review queue - a deliberately manual, line-by-line ledger-correction screen by design (per
  this project's own pre-existing "OCR never posts automatically, human explicitly applies it"
  rule) - exactly the "explicit advanced/manual accounting area" section 3 carves out.
- `CreateLedgerDialog.kt` (Debit/Credit opening-balance toggle) - **borderline, leaning keep**.
  Creating a Ledger at all is already an advanced/setup action (not a normal Sale/Purchase/Receipt
  flow), so Dr/Cr here is closer to "the advanced area" than "normal invoice UI" - but see the next
  finding for the identical pattern in a normal-user dialog.
- **`CreatePartyDialog.kt:316-327` (Debit/Credit opening-balance toggle) - real finding, FIX
  recommended.** This is the everyday "Add Customer/Supplier" dialog - confirmed live this session
  (created "Test Buyer" and "Test" supplier through it) - a normal business owner's most common
  action, not an advanced area. Its Opening Balance toggle literally reads "Debit"/"Credit"
  (`FilterChip`s bound to `DrCr.DEBIT`/`DrCr.CREDIT`) for what a non-accountant would recognize
  instantly as "They owe me" (Debit, for a Customer) / "I owe them" (Credit, for a Customer) - or
  the reverse framing for a Supplier. This is exactly section 2's own worked example
  ("Dr. Customer Ledger" must not appear in normal UI) applied to opening balances instead of
  invoice lines. Recommend: relabel the two `FilterChip`s to plain business language (e.g.
  "They owe me" / "I owe them", flipped appropriately for Customer vs Supplier), touching only the
  chip labels - `DrCr.DEBIT`/`DrCr.CREDIT` stay exactly as posted internally, this is display-only.
  **Not fixed yet** - flagging for prioritization rather than making an unrequested UI change mid
  voucher-audit task.

**Staff/User/Department/Role administration sweep:** No `StaffEntity`/`EmployeeEntity`/working
enterprise-admin feature exists anywhere in the codebase (confirmed by grep - zero hits for
"Staff Management"/"User Management"/"Add Staff"/"Employee"/"Department"). The only related surface
is `SettingsAndSyncScreen.kt`'s `UsersStaffStep` (`My Business > Users/Staff`) - a placeholder card
reading "You're the only user on this business... Staff accounts and permission roles aren't
available yet. When they are, you'll be able to add staff..." **Borderline finding**: it does not
expose any working ERP feature today (honest, inert), but its copy explicitly telegraphs a future
multi-staff/permission-role roadmap - arguably in tension with "this is NOT an ERP" as a stated
product direction, even though nothing here is broken or misleading about the CURRENT state. Left
as-is pending a product decision: remove the row's forward-looking promise, or leave it (it's
tucked inside Settings, not normal navigation, so the exposure is already minimal).

**Hard-coded/static GSTIN sweep:** Zero hits for a hard-coded GSTIN pattern
(`\d{2}[A-Z]{5}\d{4}[A-Z]\d[A-Z]Z[\dA-Z]`) or a string-literal GSTIN default assignment anywhere in
`app/src/main/java`. Every GSTIN displayed during this session's live testing (`23BNJPS3408M1ZP` for
New Sethi Electrical, `27ABCDE1234F1Z5` for SettingsHubCo) was real per-company data entered through
Business Profile/GST Settings, not a static fallback. **No fix needed - already correct**, consistent
with `57_BUSINESS_IDENTITY_DISPLAY.md`'s existing Business Profile/Company precedence rule.

**Inventory/Item/HSN "required" sweep:** No hard-blocking "Item required"/"HSN required"/"SAC
required" validation string exists anywhere. The one real hit,
`Gstr1Validation.kt`'s HSN/SAC check, is a genuine GST-law compliance threshold (HSN mandatory above
a real turnover threshold) applied at *return-filing* time, not a UI gate blocking normal invoice
creation - already correctly scoped. `TradingForm.kt`'s own doc comment explicitly states an Item is
never required/fabricated for a non-inventory business. **No fix needed - already correct.**

**Multi-company (Select/Switch/Add Company) sweep:** Present and used correctly this session
(switched between "New Sethi Electrical" and "SettingsHubCo" live) - this is standard Vyapar-style
multi-business support (one owner running two shops under one app), not enterprise multi-tenant
administration. **No fix needed - legitimate feature, not an ERP concept.**

**Summary: one clear, low-risk, not-yet-applied fix recommended (`CreatePartyDialog.kt`'s Debit/
Credit opening-balance labels) and one borderline copy/product decision (`UsersStaffStep`'s
forward-looking placeholder text). Everything else swept in phase 1 was already correct.**

## 2026-09-09 (later same day) — MainAppScreen.kt recovered; Customer/Supplier Dr/Cr fix applied; Dashboard rebuilt; new report UI wrappers

**`MainAppScreen.kt` truncation - real root cause found and fixed, not a git restore.** The user
asked to restore it from "the corrected one on git." Investigated first rather than doing it blind:
`git log` showed the last commit predates this ENTIRE day's session (nothing was committed today,
confirmed by the user) across ~40 files. Tested `git checkout HEAD -- MainAppScreen.kt` as a
deliberate experiment (stashed first, never a raw discard) - confirmed it does NOT fix anything: it
references symbols removed today (`AdaptiveNavigationType`, `Breakpoints`, `windowsizeclass`) and is
incompatible with every other file's already-changed-today signatures (wrong lambda arities for
`CreateVoucherDialog`/`CreateCompanyDialog`). Reverted that experiment immediately (`git stash pop`).
Real root cause: the file was genuinely truncated mid-statement at
`onOpenSalesRegister = { viewModel.viewRepo` (confirmed via VS Code's own local history, which only
had progressively-larger *truncated* snapshots - the save itself never completed, not a semantic
edit gone wrong). A first reconstruction attempt via a forked background agent hit a session rate
limit before finishing but left real, substantial progress (1042 -> 1188 lines, fully rebuilding the
route dispatch, dialogs, and helper functions) with only 8 real compile errors remaining, all
wrong-name/wrong-arity mistakes (e.g. `viewModel.filterDayBookByType` -> real name
`setVoucherTypeFilter`, `deleteLedger` -> `deleteLedgerSafely`, `uiState.subscription` ->
`currentSubscription`, `GstOperatingMode.NOT_APPLICABLE` -> that enum has no such value, real ones
are `ACCOUNT_ONLY`/`ACCOUNT_WITH_GST`/`GST_ONLY`, `lastBarcodeScan?.payload?.rawValue` -> `rawValue`
is directly on `BarcodeScanSuggestion`, no `.payload`). Fixed all 8 by reading each real signature
before guessing. **Both `compileDebugKotlin` and `assembleDebug` are clean.** Everything built
earlier today (VoucherDetailDialog's plain-Bill view, CSV/Excel export, OCR contextual entry points,
contacts import, drawer additions, business identity display fix) survived intact - confirmed by the
build succeeding against all of today's other already-modified files with zero further changes
needed to them.

**Customer/Supplier Dr/Cr problem - fixed, per explicit instruction.** `CreatePartyDialog.kt`'s
Opening Balance toggle no longer reads "Debit"/"Credit" - now "They owe me" / "I owe them". A Debit
opening balance always means the party owes the business (receivable) and Credit always means the
business owes the party (payable) - true for both a Customer and a Supplier ledger alike (the
existing `role: PartyRole` param already keeps Customer/Supplier creation on separate call sites -
Sales -> Customer, Purchases -> Supplier - so no further file-splitting was needed to "separate
them"). `DrCr.DEBIT`/`DrCr.CREDIT` themselves are completely unchanged - display-only fix.
`CreateLedgerDialog.kt`'s own Debit/Credit toggle was deliberately left alone - that dialog creates
*any* ledger type (Bank/Cash/Expense/Income/etc.), where "they owe me" would be actively wrong.

**Dashboard rebuilt - Recent Transactions removed, LedgerPrime brand container + Quick Report
added, Sale renamed to Invoice.** In `DashboardScreen.kt`: removed the `recentTransactionsSection`
call entirely (every transaction is already reachable via Day Book/Ledger statements/each module's
own list) and replaced it with a real brand card (the same `ic_ledgerprime_brandmark` mark used on
the invoice PDF header, plus "Ledger Prime" text) and a new "Quick Report" section with 6 shortcut
tiles: Trading (deep-links to Profit & Loss - a Trading Account is the goods-trading section within
P&L in this domain model, never a separate report), Profit & Loss, Balance Sheet, Cash Flow, Trial
Balance, Day Book - each wired to the same real `viewModel.viewReport(...)`/`AppRoute.DayBook`
navigation the existing Receivables/Payables/P&L/GST cards already use, never a placeholder tile.
**"CMA Data" and "Project Report" were deliberately NOT added as tiles** - flagged to the user rather
than faked: `domain/cma/CmaReportGenerator.kt` has real, working CMA-statement generation logic but
zero ViewModel/UI wiring anywhere (confirmed by grep - a real gap, not a small one), and "Project
Report" does not exist at any layer of this codebase. Also renamed the "Sale"/"Purchase" Quick
Action label to "Invoice" (Purchase unchanged, Service-business "Income"/"Expenditure" labels
unchanged) - the destination (`CreateVoucherDialog`/`TradingForm`) already auto-adapts between
item-level and account-only posting based on the company's own Inventory setting, confirmed live
on-device earlier today - this was a label-only change, no new routing logic.

**New file `presentation/features/reports/ReportUiModels.kt`** (small, purpose-named, per explicit
instruction) - presentation-layer bridge types the domain report models deliberately don't carry
themselves (`isExpanded`/`isSelected` are UI state, not business facts):
- `LedgerStatementRowUi` - wraps `LedgerStatementRow` (whose own `voucherType` field was itself
  fixed today from a throwaway display `String` to the real `VoucherType` enum, with its one
  construction site in `AccountingRepository.kt` and one display site in `ChartOfAccountsScreen.kt`
  updated to match) plus a `formattedRunningBalance` convenience getter.
- `SelectableItem<T>` - generic bulk-selection wrapper for Day Book/Outstanding Receivables-Payables
  table screens.
- `UiGroupTreeNode` (+ `TrialBalanceReport.toUiGroupTree()`) - real expandable Trial Balance tree
  with actual Debit/Credit group subtotals and the individual ledger rows filed under each group.
- `UiStatementNode` (+ `FinancialStatementItem.toUiStatementTree()`) - the honest P&L/Balance Sheet
  equivalent, deliberately a *different* shape (single signed amount, no ledgers/no Dr-Cr split)
  rather than forcing `UiGroupTreeNode`'s Trial-Balance-specific fields onto a report family that
  doesn't have a real Debit/Credit split at the line-item level.
- `RatioStatus` (HEALTHY/WARNING/CRITICAL) as extension properties on `RatioAnalysisReport` per
  ratio, with documented standard financial-analysis thresholds (e.g. Current Ratio >=1.5 healthy,
  >=1.0 watch, else critical) - always secondary to the real number, never replacing it.
None of these are wired into an actual report screen's UI yet - they're the data-shaping layer only,
built and compiling clean; wiring them into `ReportsCenterScreen.kt`'s actual Trial Balance/P&L/
Balance Sheet/Day Book views is the next real step whenever that's wanted.

**Build verified clean end-to-end**: both `compileDebugKotlin` and `assembleDebug` succeed with zero
errors after all of the above. Not yet re-verified live on a device (none connected at the time of
this entry) - the user intends to check it themselves next.
