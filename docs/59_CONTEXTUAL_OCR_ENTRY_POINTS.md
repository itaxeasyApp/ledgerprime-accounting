# 59. Contextual OCR Entry Points

## What changed

The Document/Image Scan feature (see `domain/ocr/README.md`, `docs/57`/`58`-adjacent work) originally
had exactly one entry point: a center bottom-bar `FloatingActionButton` opening a single 8-option
"Scan a Document" picker (`ScanDocumentTypeDialog`) covering every `OcrDocumentType`.

Per explicit user direction, most scan types now also have (or exclusively have) a **contextual**
entry point on the screen where that document naturally belongs, so the user never has to remember
"scan is a separate generic thing" - it's just an action on the screen they're already using:

| Document type | Contextual entry point | Still in the generic popup? |
|---|---|---|
| Sales Invoice | Sales screen, "Sales" tab - second FAB (bottom-start) next to "New Sale" | No - removed |
| PAN Card | Profile & Business Setup - "Scan PAN / Aadhaar / GST Certificate" card | No - removed |
| Aadhaar Card | Profile & Business Setup - same card | No - removed |
| GST Certificate (new) | Profile & Business Setup - same card | No - never was (new type) |
| Bank Statement | Money tab's Bank ledger list (icon next to the "Bank" title) **and** the Receive/Pay voucher entry screen (icon in the header) | No - removed |
| Expense Receipt | *(none requested)* | No - **removed entirely**, no OCR entry point for this type today |
| Purchase Bill | Generic popup only | Yes |
| UPI Payment | Generic popup only | Yes |
| Other Document | Generic popup only | Yes |

Every contextual entry point skips `ScanDocumentTypeDialog` (and, for Profile, uses its own smaller
`ScanProfileDocumentTypeDialog` with just the 3 identity-document options) and opens the Photo
Picker directly with `documentTypeHint` already set to the correct type - the screen already
answers "what kind of document is this?" so the generic picker would be redundant there.

## New: GST_CERTIFICATE document type

Added to `OcrDocumentType` alongside PAN_CARD/AADHAAR_CARD - extraction reuses the same
`extractIdentityDocument` heuristic (GSTIN via the existing `gstinRegex`, trade/legal name via the
same longest-non-boilerplate-line guess, with "GOODS AND SERVICES TAX"/"CERTIFICATE OF
REGISTRATION"/"REGISTRATION NUMBER"/"GSTIN" added to the boilerplate exclusion list so those
header words are never mistaken for the business name).

**Important:** applying a reviewed GST Certificate scan updates `BusinessProfile.businessName`/`.gstin`
(document-branding, shown on invoices) via a new `AccountingViewModel.applyOcrBusinessProfileDraft`
function - it deliberately **never** touches `Company.gstin` (the statutory record GST returns are
filed against). This follows the same precedence rule as `docs/57_BUSINESS_IDENTITY_DISPLAY.md`: a
scanned document is real evidence, but changing what GST filing treats as your registered number
must stay a deliberate act in Settings > My Business > GST Details, never an OCR side effect. If a
user wants their scanned GSTIN to also become the company's statutory one, they re-enter it there
themselves after reviewing the scan.

## Files touched

- `domain/ocr/OcrIngestionAdapter.kt` - `GST_CERTIFICATE` added to `OcrDocumentType`.
- `domain/ocr/DocumentFieldExtractor.kt` - extraction + auto-detection + boilerplate list.
- `presentation/viewmodel/AccountingViewModel.kt` - `GST_CERTIFICATE` routed like PAN/Aadhaar in
  `scanDocument`; new `applyOcrBusinessProfileDraft`.
- `presentation/features/datatools/DataToolsScreen.kt` - `OcrReviewCard` gets a dedicated
  `GST_CERTIFICATE` branch (Business Profile apply target, distinct from PAN/Aadhaar's Individual
  Profile target).
- `presentation/components/ScanDocumentTypeDialog.kt` - trimmed to Purchase Bill/UPI/Other; new
  `ScanProfileDocumentTypeDialog` (PAN/Aadhaar/GST Certificate only) for Profile's own entry point.
- `presentation/features/sales/SalesScreen.kt` - `onScanInvoice` + second FAB on the Sales tab.
- `presentation/features/profile/ProfileScreen.kt` - `onScanProfileDocument` + its own SectionCard
  and dialog state.
- `presentation/features/money/MoneyHomeScreen.kt` - `onScanBankStatement` threaded through
  `MoneyTabContent` to `CashOrBankLedgerListScreen` (Bank only, not Cash) and to the voucher entry
  screen (Receive/Pay, not Transfer).
- `presentation/features/money/MoneyVoucherEntryScreen.kt` - optional scan icon in the header.
- `presentation/MainAppScreen.kt` - wires all three new contextual callbacks to the same
  `documentPhotoPickerLauncher` the generic popup already used, just with `pendingScanDocumentType`
  pre-set instead of coming from `ScanDocumentTypeDialog`.

## Not yet done / worth checking next

- Purchase Bill has no contextual home on the Purchases screen yet (only Sales Invoice was
  explicitly requested for Sales) - ask before adding one, to avoid guessing wrong again.
- No on-device visual verification yet of the three new contextual entry points (Sales FAB, Profile
  card, Bank ledger/voucher icons) - do this before calling this feature done.
