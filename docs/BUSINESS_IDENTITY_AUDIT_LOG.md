# Business Identity Display - Running Audit Log

Living notes file for the "does this screen show my updated Business Profile, or a stale Company
value?" audit. See `docs/57_BUSINESS_IDENTITY_DISPLAY.md` for the rule this log checks screens
against. Append new entries at the bottom; don't rewrite history.

Columns: **Location** (file/screen), **What it shows**, **Verdict** (Fixed / Correct as-is / Open
question), **Checked**.

## 2026-09-09 - Initial audit (triggered by user report: header showed stale name/GSTIN after a real Profile edit)

| Location | What it shows | Verdict | Notes |
|---|---|---|---|
| `AppTopBar.kt` | Dashboard header name/GSTIN | **Fixed** | Now prefers `BusinessProfile.businessName`/`.gstin`, falls back to `Company` |
| `AppDrawer.kt` | Hamburger drawer header name/GSTIN | **Fixed** | Same fix as AppTopBar, same session |
| `SettingsAndSyncScreen.kt` (`SettingsStep.Root`) | "My Business" row subtitle | **Fixed** | Now prefers `BusinessProfile.businessName` |
| `MoneyHomeScreen.kt` (UPI Receive/Pay) | Payee name on generated UPI QR | Correct as-is | Already layers saved UPI profile > BusinessProfile > Company |
| `ReportsCenterScreen.kt` (`GstCategory`) | GST Return Dashboard enabled/disabled | Correct as-is | Statutory eligibility check - must stay Company-only |
| `GstReturnDashboardScreen.kt` (`GstDeclarationCard`, multiple spots) | GST declaration name/GSTIN | Correct as-is | Name already layers Individual > BusinessProfile > Company; GSTIN correctly Company-only |
| `VoucherDetailDialog.kt` (`InvoiceQrSection`) | `SELLER_GSTIN` in invoice QR | Correct as-is | Statutory - must stay Company.gstin |
| `AccountingRepository.kt` (`sellerSnapshot` - powers Invoice Preview + all document rendering) | Seller name/address/GSTIN/PAN/phone/email on every invoice/document | Correct as-is | Built correctly from Phase 7D, no change needed |

**Root cause confirmed:** Two independently-editable entities (`Company` via Settings, `BusinessProfile`
via Profile & Business Setup) with no single "business name" source of truth for display purposes.
Fix applied: a documented precedence rule (BusinessProfile first, Company fallback) for every
*display* context, while leaving every *statutory* context reading Company only, untouched.

**Verified on-device:** Phone (test company "New Sethi Electrical", GSTIN `23BNJPS3408M1ZP`) -
Dashboard header confirmed correct after fix. Drawer and Settings root row fixed in code same
session; re-verify on-device next session opens this file.

**Still open / not yet checked this pass:**
- Backup & Sync screen - does it label the exported backup with a business name anywhere, and if
  so which source?
- Any exported CSV/JSON filename that embeds the business name (check `CsvExporter.kt`/export
  DTOs for a filename-generation helper) - confirm it uses the same precedence rule if it displays
  (not just files) a name to the user.
- Subscription/billing screen - does it show a business name anywhere?
- PDF/print header vs. on-screen Invoice Preview - both already traced to the same
  `assembleDocumentData`/`sellerSnapshot` path, so should be consistent, but not yet visually
  confirmed on-device (no voucher exists yet on the test company to render).

## 2026-09-20 - No-mock-data audit: sellerSnapshot GSTIN/PAN fixed, all 4 prior open questions resolved

Re-checked every `.gstin`/`.stateName`/business-name read site (74 occurrences) as part of a
broader "no mock/static data" audit. All previously-open questions from the initial audit resolve
clean with no code change needed:

| Open question | Resolution |
|---|---|
| Backup & Sync screen - business name label source? | Same fixed Settings root row (`SettingsAndSyncScreen.kt:207`), already prefers `BusinessProfile`. |
| Exported CSV/JSON filename embeds business name? | No - filenames are `invoice_<documentNumber>_<timestamp>.csv` / `gst_response_<timestamp>.json`, never a business name. Nothing to fix. |
| Subscription/billing screen shows a business name? | No - `SubscriptionScreen.kt` shows no business name anywhere. |
| PDF/print header vs on-screen Invoice Preview parity | Confirmed both paths already go through the same `assembleDocumentData`/`sellerSnapshot`. |

**One real finding, fixed:** `sellerSnapshot()` (`AccountingRepository.kt:5171`) - the seller block
for every rendered invoice/document - was letting `BusinessProfile.gstin`/`.pan` silently override
`Company.gstin`/`.pan` when non-blank. Per this file's own rule (GSTIN must never substitute from
BusinessProfile in a statutory context, same as the QR row), and since GSTIN/PAN are independently
user-editable in Profile & Business Setup separately from Settings' own GST Details screen, a
stale or mistyped Business Profile value could have printed on a real customer-facing tax invoice
while GST Return filing correctly kept reading `Company`. Fixed: `gstin`/`pan` now read `company`
unconditionally; `name`/`address`/`phone`/`email` continue to prefer `BusinessProfile` (legitimate
branding, unchanged). `docs/57_BUSINESS_IDENTITY_DISPLAY.md`'s own table updated to match.

Also re-verified (no change needed): every GST Return Dashboard GSTIN read stays `Company`-only;
GSTR-1's `recipientGstin` is sourced from the frozen `GstTransaction.partyGstin` captured at
posting time (never a live re-join to a possibly-changed Ledger) via `Gstr1ReturnBuilder`'s own
already-computed JSON; `MaskedBusinessProfileSummary.gstinMasked` is a real masked derivation, not
hardcoded.

## How to add a new entry

When you check a new screen (or re-verify an old one after a change), append a new dated section
in this same table format. Don't delete or edit prior entries except to fix a factual error -
this file is a log, not a snapshot.
