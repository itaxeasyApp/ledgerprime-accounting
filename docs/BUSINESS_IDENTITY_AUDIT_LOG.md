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

## How to add a new entry

When you check a new screen (or re-verify an old one after a change), append a new dated section
in this same table format. Don't delete or edit prior entries except to fix a factual error -
this file is a log, not a snapshot.
