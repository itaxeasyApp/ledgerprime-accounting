# LedgerPrime Corrections - README

This is the **one** reference document for the ongoing round of corrections/fixes being made to
the app (business identity display, navigation layout, OCR entry points, contacts/favorites, and
whatever comes next). Read this first; check `CORRECTIONS_LOG.md` in this same folder for the
dated, append-only history of what was actually done and when.

Earlier in this same effort, three separate numbered docs were created per-topic
(`57_BUSINESS_IDENTITY_DISPLAY.md`, `58_SINGLE_NAVIGATION_LAYOUT.md`,
`59_CONTEXTUAL_OCR_ENTRY_POINTS.md`) - that was a mistake; the user explicitly asked for one
readme and one continuous-use log, not a new file per topic. Those three still exist (their
content is accurate and more detailed on those specific topics) and are linked below, but **no
new numbered topic doc should be created going forward** - new work gets a new dated section in
`CORRECTIONS_LOG.md` instead, and this README's summary table gets a new row.

## What's been corrected so far (summary - see the log for detail)

| Area | What changed | Detail doc |
|---|---|---|
| Business identity display | Dashboard header, drawer header, and Settings root row now prefer Business Profile's trade name/GSTIN over Company's, with statutory contexts (GST filing, invoice QR) untouched | `57_BUSINESS_IDENTITY_DISPLAY.md` |
| Navigation layout | Removed the tablet `NavigationRail` switch - one consistent bottom nav bar on every screen size, phone or tablet | `58_SINGLE_NAVIGATION_LAYOUT.md` |
| OCR entry points | Sales Invoice/PAN/Aadhaar/GST Certificate/Bank Statement scans moved to contextual buttons on their own screens instead of one generic popup; added new `GST_CERTIFICATE` document type | `59_CONTEXTUAL_OCR_ENTRY_POINTS.md` |
| Contacts + Favorites (in progress) | Adding a real "import from device Contacts" action and a real, persisted "favorite" flag for Customers/Suppliers | this file + log |
| Drawer additions | "Rate on Play Store" (real market:// intent) and "Log Out" (reuses existing Cloud Sync logout, shown only when actually logged in) | this file + log |

## Product identity correction (2026-09-09) — READ BEFORE TOUCHING VOUCHERS/INVOICES/GST/PROFILE UI

User's own words: **"THIS APPLICATION IS NOT AN ERP."** It's a Vyapar-style Indian small-business
app: simple, invoice-first, GST-oriented, mobile-first, non-accountant-friendly. Double-entry
accounting stays mandatory *internally* - it must never leak into normal UI as ERP/Western-SaaS
patterns. Full 18-section source directive archived at the top of the `CORRECTIONS_LOG.md` entry
dated 2026-09-09 ("Product Identity Audit - directive received"); condensed rules below are what
every future change in this area must follow:

- **Invoice/Voucher UI never shows Dr/Cr, ledger names, or journal lines.** Normal user sees
  Customer/Item/Qty/Rate/GST/Total and Preview/Share/Print - nothing else. The double-entry engine
  runs behind that screen, never exposed except in an explicit advanced/manual accounting area.
- **One transaction = one posted voucher, always.** Audit every create/edit/OCR/import/retry path
  for a way to double-post the same source transaction. Never dedupe on invoice number alone
  (different parties can reuse the same number) - use a stable internal id.
- **GSTIN (and all business identity data) has exactly one authoritative source** (the Business/GST
  profile) - no hard-coded, static, or independently-copied GSTIN anywhere else in the app.
- **No ERP-style Staff/User/Department/Role administration UI** in normal navigation - this is a
  single-owner app.
- **User Profile (the person) and Business Profile (the entity/GSTIN) are distinct but each
  single-sourced** - never duplicated independently per screen.
- **Inventory vs Non-Inventory is the user's choice and must be respected everywhere** - a service
  business is never forced through Item/Qty/Rate/Stock fields; HSN/SAC stays optional wherever the
  workflow allows it.
- **Every missing-data/validation failure names the exact field, explains why it's needed, and
  gives exact navigation to fix it** (a "Fix Now" deep link where feasible) - never a generic error.
- **A GST mismatch never hides or duplicates the transaction.** It stays visible in every section it
  legitimately belongs to; the GST Dashboard separately surfaces the mismatch with the exact reason
  and the exact remedy.

This is a large, repo-wide audit (voucher/invoice posting paths, all Dr/Cr-adjacent UI, every GSTIN
read site, inventory/non-inventory branching, GST validation/mismatch UI, the User vs Business
Profile model, and a grep sweep for ERP-flavored strings) - see `CORRECTIONS_LOG.md`'s 2026-09-09
entry for the section-by-section execution plan and running findings. Not yet started/completed as
of the plan being written - do not assume any of section 1-18's fixes are already in place.

## Standing rules this whole effort follows

- **No fake/mock/stub data or behavior.** Every button added must do the real thing (real Android
  intents, real permission requests, real DB writes) or not be added at all.
- **Business Profile vs Company** (see `57_BUSINESS_IDENTITY_DISPLAY.md`): display contexts prefer
  Business Profile; statutory/compliance contexts (GST filing, e-invoice QR, tax calculation) must
  keep reading Company directly. Never swap these.
- **One consistent navigation layout** (see `58_SINGLE_NAVIGATION_LAYOUT.md`): bottom nav bar only,
  never a side rail, regardless of screen size.
- **OCR never posts/creates master data automatically** - every scan produces a reviewable
  draft/suggestion; the human explicitly applies or posts it. This predates this round of
  corrections and must not regress.
- **Ask before guessing on ambiguous scope**, note the interpretation taken when proceeding anyway,
  so it's easy to correct later instead of silently compounding a wrong guess.
- **Testing on the physical device only happens when explicitly asked.** Build/compile freely, but
  do not install/launch/screenshot on the phone or tablet until told to.
