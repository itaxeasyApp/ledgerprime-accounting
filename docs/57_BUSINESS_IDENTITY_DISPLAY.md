# 57. Business Identity Display Precedence

## The problem this fixes

This app stores business identity data in **two separate places**:

- **`Company`** (`domain/company/Company.kt`) — the sole authoritative record for
  statutory/accounting fields (`name`, `gstin`, `pan`, `stateCode`, ...). Edited via
  **Settings > My Business > Edit Business Details / GST Details / Contact Details**.
- **`BusinessProfile`** (`domain/rendering/BusinessProfile.kt`) — the document-branding identity
  (Phase 7D): trade name, address, GSTIN, PAN, phone, email, logo, signature, bank/UPI details.
  Edited via **Profile & Business Setup**. Deliberately separate from `Company` so a business can
  have a shorter trading name on invoices than its registered legal name — see that file's own
  KDoc.

Because the app is strictly single-business (one `Company` row per install), a user editing
"their business name" in Profile & Business Setup reasonably expects **every screen** that shows
their business name/GSTIN to reflect that edit. Before this fix, several screens read `Company`
directly and never re-checked `BusinessProfile`, so an edit in Profile & Business Setup appeared
to silently fail everywhere except the one screen that actually reads `BusinessProfile`.

Reported by the user as: "stale things are still didnot changed after updating the profile" — the
Dashboard header, hamburger drawer, and Settings root row were all showing the old value after a
real, saved Business Profile edit.

## The rule

**For any UI element whose job is "show the user their business's name/GSTIN":**

```kotlin
val displayName = businessProfile?.businessName?.ifBlank { null } ?: company?.name ?: "My Business"
val displayGstin = (businessProfile?.gstin?.ifBlank { null } ?: company?.gstin)
    ?.ifBlank { "Unregistered / Composition" } ?: "--"
```

Business Profile first, `Company` only as a fallback for a brand-new business that hasn't opened
Profile & Business Setup yet (a `BusinessProfile` row doesn't exist until then).

**For any statutory/compliance/accounting use** (GST Return filing eligibility, e-invoice QR
`SELLER_GSTIN`, the GST Declaration card's registered GSTIN, tax calculation inputs) — keep reading
`Company` directly, **never** `BusinessProfile`. `Company` stays the sole authoritative record for
these; `BusinessProfile` is a branding preference that must never silently substitute for it in a
statutory context. Do not "fix" these by switching them to `BusinessProfile` — that would be a
real regression, not a consistency fix.

## Where the rule is applied (as of this audit)

| File | What it shows | Status |
|---|---|---|
| `presentation/components/AppTopBar.kt` | Dashboard header name/GSTIN | Fixed - prefers `BusinessProfile` |
| `presentation/components/AppDrawer.kt` | Hamburger drawer header name/GSTIN | Fixed - prefers `BusinessProfile` |
| `presentation/features/settings/SettingsAndSyncScreen.kt` (`SettingsStep.Root`) | "My Business" row subtitle | Fixed - prefers `BusinessProfile` |
| `presentation/features/money/MoneyHomeScreen.kt` (UPI payee name) | Receive/Pay UPI QR payee name | Already correct - prefers `BusinessProfile`/saved UPI profile |
| `presentation/features/reports/GstReturnDashboardScreen.kt` (`GstDeclarationCard`) | GST return declaration name | Already correct - layers Individual > Business Profile > Company for name; GSTIN stays Company-only (statutory) |
| `presentation/features/reports/ReportsCenterScreen.kt` (`GstCategory`) | Whether GST Return Dashboard is enabled | Correct as-is - must check `Company.gstin` (real registration), not branding copy |
| `presentation/components/VoucherDetailDialog.kt` (`InvoiceQrSection`) | `SELLER_GSTIN` in the invoice QR payload | Correct as-is - statutory, must be `Company.gstin` |
| `data/repository/AccountingRepository.kt` (`sellerSnapshot`, used by `assembleDocumentData`/`assembleDocumentDataFromVoucher` - i.e. Invoice Preview and all document rendering) | Seller name/address/GSTIN/PAN/phone/email on every generated invoice/document | Already correct - was built this way from Phase 7D |

`Settings > My Business > Edit Business Details/GST Details/Contact Details` themselves correctly
show and edit `Company`'s own current values (that IS the screen that edits `Company`) - not a
violation of this rule, since that screen's whole purpose is `Company`, not display consistency.

## Adding a new screen that shows business identity

Before adding a new "your business" name/GSTIN/address display anywhere:

1. Is this a **display/branding** context (header, drawer, list subtitle, printed document, UPI
   payee name)? -> Apply the precedence rule above.
2. Is this a **statutory/compliance/accounting** context (GST filing, tax calculation, e-invoice
   QR, anything that must match what's actually registered with the GSTN)? -> Read `Company`
   directly, do not consult `BusinessProfile`.
3. Unsure which? -> Ask before choosing; picking wrong in the statutory direction is real
   accounting risk, not a cosmetic bug.

See also: `docs/BUSINESS_IDENTITY_AUDIT_LOG.md` for the running log of what's been checked in this
area and when.
