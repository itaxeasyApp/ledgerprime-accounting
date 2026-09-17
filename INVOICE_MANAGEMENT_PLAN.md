# LedgerPrime Invoice Management System - Implementation Plan
**India-Only Tax Invoice System | Double-Entry Accounting | 2026-09-13**

---

## 📋 Overview

This plan describes a lightweight, easy-to-use Invoice Management System for LedgerPrime that:
- ✅ Integrates with Sales & Purchase transactions (tied to double-entry posting)
- ✅ Generates compliant Indian tax invoices (GSTR-ready format)
- ✅ Includes printable, preview, and share capabilities
- ✅ Adds digital signature panel (cost-effective - image/PDF annotation)
- ✅ Embeds real QR code (IRN-based for e-Invoice, or basic transaction QR)
- ✅ Includes barcode for item HSN/SAC codes (for scanning)
- ✅ Lightweight, modular architecture (not bloatware)
- ✅ Fully compliant with Indian GST tax invoice standards

---

## 🎯 Invoice Types Supported

| Invoice Type | Trigger | Ledger Impact | Tax Invoice Required |
|--------------|---------|---------------|----------------------|
| **Sale Invoice** | Sale transaction posted | Debit Receivable, Credit Revenue + GST | ✅ YES (GSTR-1 outward) |
| **Purchase Invoice** | Purchase transaction posted | Debit Expense, Credit Payable + GST | ✅ YES (GSTR-2 inward) |
| **Debit Note** | Sale return / adjustment | Reversal entries (decreases revenue) | ✅ YES (GSTR-1 amended) |
| **Credit Note** | Purchase return / adjustment | Reversal entries (decreases expense) | ✅ YES (GSTR-2 amended) |
| **Payment Receipt** | Money received / paid | Debit Bank, Credit Receivable | ⚠️ Optional (audit trail) |
| **Proforma Invoice** | Quote or estimate | ❌ NO ledger entry (draft state) | ❌ NO (pre-sale) |

---

## 📐 Invoice Structure (Indian GST Tax Invoice Format)

### Header Section
```
┌─────────────────────────────────────────────────────┐
│                    LOGO & BRANDING                   │
│  Company Name | GSTIN: XX AAAA0000A1Z5             │
│  Address (Registered Office)                         │
│  State: TN | PAN: AAAAA0000A                        │
└─────────────────────────────────────────────────────┘
```

### Invoice Details
```
┌─────────────────────────────────────────────────────┐
│ Invoice No: INV-2026-001234         Date: 2026-09-13│
│ Reference: Transaction ID (links to ledger posting) │
│ Invoice Type: TAX INVOICE                           │
│ GSTIN: XX BBBB0000B1Z5 (Buyer's GSTIN)             │
│ Buyer: Customer Name | Place of Supply: TN         │
└─────────────────────────────────────────────────────┘
```

### Item Line Details
```
┌─────────────────────────────────────────────────────┐
│ S.No | Item Name | HSN/SAC | Qty | Rate | Amount   │
│  1   | Widget A  | 841213  |  10 | 100  | 1000     │
│      | [BARCODE: 841213-001]                        │
│  2   | Service B | 998599  |   1 | 500  | 500      │
│      | [BARCODE: 998599-001]                        │
└─────────────────────────────────────────────────────┘
```

### Tax Calculation Section (GST Display)
```
┌─────────────────────────────────────────────────────┐
│                    TAX SUMMARY                       │
│ Subtotal (excluding tax):           ₹ 1,500.00     │
│                                                      │
│ CGST @ 9%:                          ₹ 135.00       │
│ SGST @ 9%:                          ₹ 135.00       │
│ [OR IGST @ 18% for inter-state]                    │
│ CESS @ 5% (if applicable):          ₹ 75.00        │
│                                                      │
│ Total Tax:                          ₹ 345.00       │
│ TOTAL AMOUNT:                       ₹ 1,845.00     │
│ Rounded Off:                        ₹ 0.00         │
│ Final Amount (In Words):            Rupees One...  │
└─────────────────────────────────────────────────────┘
```

### ITC Section (For Purchase Invoices Only)
```
┌─────────────────────────────────────────────────────┐
│              INPUT TAX CREDIT (ITC)                  │
│ Eligible CGST:  ₹ 135.00 (Ledger: GST Input A/c)   │
│ Eligible SGST:  ₹ 135.00 (Ledger: GST Input A/c)   │
│ Ineligible:     ₹ 0.00                              │
│ (Based on HSN/SAC category & GSTR-2 mapping)       │
└─────────────────────────────────────────────────────┘
```

### Footer Section
```
┌─────────────────────────────────────────────────────┐
│                   SIGNATURE PANEL                    │
│  Digital Signature / Image Signature                │
│  [Authorized By: ________________] [Date: ___]      │
│                                                      │
│  QR CODE (Bottom Right)                             │
│  ┌────────────────┐                                │
│  │      QR        │  Transaction ID + IRN           │
│  │      CODE      │  (e-Invoice link if available)  │
│  │      HERE      │                                │
│  └────────────────┘                                │
│                                                      │
│  Invoice Reference: INV-2026-001234                 │
│  Ledger Post Date: 2026-09-13 | Posted By: User123 │
│  [This is a computer-generated invoice]             │
└─────────────────────────────────────────────────────┘
```

---

## 🔗 Invoice-to-Ledger Mapping (Double-Entry Foundation)

### Sale Invoice → Ledger Posting Relationship

**Invoice Details:**
```
Invoice: INV-2026-001234
Buyer: ABC Limited (GSTIN: XX CCCC0000C1Z5)
Item: Widget A (HSN 841213)
Quantity: 10
Rate: ₹100 per unit
Subtotal: ₹1,000
SGST (9%): ₹90
CGST (9%): ₹90
Total: ₹1,180
```

**Ledger Entries Created (Double-Entry):**
```
Entry 1:
  Debit: Receivable (ABC Limited) ────────── ₹1,180
  Credit: Sales Revenue (HSN 841213) ──────── ₹1,000
  (Links to: Account Head "Sales of Goods")

Entry 2:
  Debit: GST Input Receivable ──────────────── ₹180
  Credit: GST Output Payable ──────────────── ₹180
  (CGST ₹90 + SGST ₹90)
  (Links to: Tax accounts for GSTR-1 filing)
```

**Invoice Display Shows:**
- ✅ All ledger line items (debit/credit)
- ✅ Reference to posting date & ledger ID
- ✅ Account heads involved (for audit trail)

### Purchase Invoice → Ledger Posting Relationship

**Invoice Details:**
```
Invoice: INV/SUP/2026/001
Supplier: XYZ Pvt Ltd (GSTIN: XX DDDD0000D1Z5)
Item: Raw Material (HSC 721830)
Quantity: 5
Rate: ₹500 per unit
Subtotal: ₹2,500
SGST (9%): ₹225
CGST (9%): ₹225
Total: ₹2,950
```

**Ledger Entries Created (Double-Entry):**
```
Entry 1:
  Debit: Purchase / Inventory Account ──────── ₹2,500
  Credit: Payable (XYZ Pvt Ltd) ──────────── ₹2,500
  (Links to: Account Head "Purchase of Goods")

Entry 2:
  Debit: GST Input Credit ────────────────── ₹450
  Credit: GST Payable ────────────────────── ₹450
  (CGST ₹225 + SGST ₹225 - eligible for ITC)
  (Links to: Tax accounts for GSTR-2 filing)
```

**Invoice Display Shows:**
- ✅ All ledger line items (debit/credit)
- ✅ ITC eligibility status (will be matched in GSTR-2)
- ✅ Ledger posting reference

---

## 🖼️ Invoice Component Breakdown

### 1. Header Component
**Files Involved:**
- `domain/invoice/InvoiceHeader.kt` (data class)
- `ui/screens/invoice/InvoiceHeaderComposable.kt` (Compose UI)
- `DocumentData.shipDate`, `dispatchDate` (already in Phase 7D)

**Data Fields:**
- Company logo (from `BusinessProfile`)
- Company name, GSTIN, PAN, State, Address
- Invoice number (auto-generated, never reused)
- Invoice type (Sale/Purchase/Debit/Credit)
- Invoice date & posting date
- Buyer/Supplier name, GSTIN, State, Address

**Implementation:**
- Reuse existing `BusinessProfile` (Phase 7G, frozen)
- Reuse existing document template system (Phase 7D)
- No new database schema needed

---

### 2. Item Line Component (With Barcode)
**Files Involved:**
- `domain/invoice/InvoiceLineItem.kt` (data class)
- `ui/screens/invoice/ItemLineComposable.kt` (Compose UI)
- `domain/qrbarcode/BarcodeGenerator.kt` (reuse for HSN/SAC)

**Data Fields Per Item:**
- Item name, HSN/SAC code
- Quantity, Unit, Rate
- Amount (Qty × Rate)
- GST rate applicable (%)
- CGST/SGST/IGST/CESS breakdown
- Barcode (HSN/SAC code as 1D barcode EAN-13 format)

**Barcode Details:**
```
Barcode Format: EAN-13 (or Code128 for HSN/SAC)
Barcode Content: "HSN{code}-{item_line_number}"
Example: "841213-001" (HSN code + line number)
Position: Below each item line
Size: 20mm × 10mm (small, QR-code sized)
Used For: Item scanning in warehouse/stock management
```

**Implementation:**
- Use existing `BarcodeGenerator` (Phase 7J contract exists)
- Generate barcode per line item
- Display below HSN/SAC code
- No new dependencies (use existing barcode library)

---

### 3. Tax Calculation Component
**Files Involved:**
- `domain/invoice/InvoiceTaxCalculator.kt` (logic)
- `ui/screens/invoice/TaxSummaryComposable.kt` (Compose UI)

**Calculation Logic:**
```kotlin
subtotal = sum(quantity × rate for all items)

if (intra_state_sale_to_registered_buyer) {
    cgst = subtotal × 9%  // or rate per HSN/SAC
    sgst = subtotal × 9%  // or rate per HSN/SAC
    igst = 0
} else if (inter_state_sale_to_registered_buyer) {
    igst = subtotal × 18%  // or rate per HSN/SAC
    cgst = 0
    sgst = 0
} else if (sale_to_unregistered_buyer) {
    // No GST for most cases (unless reverse charge)
    cgst = 0
    sgst = 0
    igst = 0
}

if (cess_applicable_per_hsn) {
    cess = subtotal × 5%  // or rate per HSN/SAC
}

total_tax = cgst + sgst + igst + cess
final_amount = subtotal + total_tax
```

**Display Format:**
- Subtotal (amount before tax)
- Line-by-line tax breakdown (CGST, SGST, IGST, CESS)
- Total tax amount
- Final amount (including tax)
- Amount in words (rupees & paise)
- Rounding off adjustment (if any)

**Implementation:**
- Reuse existing GST calculator (Phase 6, frozen)
- No new logic needed (already in posting engine)
- Just display format change for invoice

---

### 4. ITC (Input Tax Credit) Section - Purchase Invoices Only
**Files Involved:**
- `domain/invoice/InvoiceITC.kt` (ITC eligibility logic)
- `ui/screens/invoice/ITCComposable.kt` (Compose UI)

**Logic:**
```kotlin
// For each tax line item in Purchase Invoice:
if (hsn_gst_rate > 0 && supplier_is_registered && purchase_type_eligible) {
    eligible_itc = tax_amount  // Full ITC eligible
    ineligible_itc = 0
} else if (hsn_gst_rate == 0 || supplier_not_registered) {
    eligible_itc = 0
    ineligible_itc = tax_amount  // No ITC
}

// Display on invoice:
- Eligible CGST: ₹X (can be claimed in GSTR-3B)
- Eligible SGST: ₹X (can be claimed in GSTR-3B)
- Eligible IGST: ₹X (can be claimed in GSTR-3B)
- Ineligible: ₹Y (cannot be claimed)
```

**Display Position:**
- Below tax summary section
- Only for Purchase/Debit invoices
- Hidden for Sale/Credit invoices

**Implementation:**
- Simple read-only display (ITC calculation done in posting engine)
- No new logic needed
- Just format output for printing

---

### 5. Signature Panel (Cost-Effective Implementation)
**Files Involved:**
- `domain/invoice/SignaturePanel.kt` (data class)
- `ui/screens/invoice/SignaturePanelComposable.kt` (Compose UI)
- `DocumentAsset` (Phase 7G - already exists for branding)

**Options (Cost-Effective):**

**Option A: Image Signature (Lightest Weight)**
```
- Store PNG/JPG image (company seal or director signature)
- Display as-is in invoice PDF
- No external dependency
- Setup: Upload once, reuse forever
- Size: ~50KB per signature
```

**Option B: Digital Certificate (Medium)**
```
- Add PDF digital signature (optional, future)
- For now: use image-based approach
- Signature library can be added later (e.g., iText 7)
```

**Option C: Text Signature (Fallback)**
```
- Simple text line: "Authorized By: ___________ Date: ___"
- Printer signs manually (hybrid approach)
- Zero cost, most common in India
```

**Recommended Approach:**
- Use **Option A + Option C hybrid**
- Store company seal/logo as signature image (already in `DocumentAsset`)
- Add text line for manual signature
- PDF library handles rendering (already in Phase 7D)

**Implementation:**
- Reuse existing `DocumentAsset` from Phase 7G (frozen)
- No new database schema
- Just add signature rendering in PDF template

**Display:**
```
┌──────────────────────────────────┐
│     Authorized Signatory         │
│                                  │
│  [Company Seal/Signature Logo]   │
│                                  │
│  _____________________________   │
│  Authorized By: _____________   │
│                                  │
│  Date: __________________       │
│                                  │
│  This is a computer-generated   │
│  invoice and does not require   │
│  physical signature              │
└──────────────────────────────────┘
```

---

### 6. QR Code Component (Real QR Code)
**Files Involved:**
- `domain/invoice/InvoiceQRCode.kt` (data class & generator)
- `ui/screens/invoice/QRCodeComposable.kt` (Compose UI)
- `domain/qrbarcode/QRCodeGenerator.kt` (reuse existing)

**QR Code Content Options:**

**Option A: Transaction ID + IRN (e-Invoice Ready)**
```
QR Content: 
{
  "ref": "INV-2026-001234",
  "irn": "ABC1234DEF56789",  // From GSTIN verification API (Phase 7H)
  "date": "2026-09-13",
  "amount": 1845.00,
  "gstin": "XX AAAA0000A1Z5"
}

Size: ~2.5cm × 2.5cm (bottom-right of invoice)
Format: QR Code (ISO/IEC 18004)
Position: Bottom-right corner (footer area)
Used For: Quick verification, e-Invoice link
```

**Option B: Invoice Hash (Simple, Offline)**
```
QR Content: Simple string
"INV-2026-001234|2026-09-13|1845.00|MD5_HASH"

Advantage: Works offline, no API dependency
Disadvantage: Less rich data
```

**Recommended Approach:**
- Use **Option A (Transaction ID + IRN)**
- Ready for future e-Invoice integration (Phase 7H-2)
- Can work offline with fallback to Option B
- No external API call needed for invoice generation (QR data created locally)

**Implementation:**
- Reuse existing `QRCodeGenerator` (domain/qrbarcode/, Phase 7J)
- Generate QR after invoice posted
- Store QR image in invoice cache (no database)
- Render in PDF template

**Display:**
```
┌──────────────┐
│     QR       │  Real QR Code (2.5cm × 2.5cm)
│    CODE      │  Contains: Transaction ID, date, amount
│              │  Scannable with any QR reader
│              │  Links to invoice verification (if API available)
└──────────────┘
Invoice Ref: INV-2026-001234
```

---

### 7. Footer Component (Ledger Reference & Audit Trail)
**Files Involved:**
- `domain/invoice/InvoiceFooter.kt` (data class)
- `ui/screens/invoice/FooterComposable.kt` (Compose UI)

**Content:**
```
Invoice Reference: INV-2026-001234
Ledger Posting Date: 2026-09-13
Ledger ID: ledger_posting_12345
Posted By: user@company.com
Transaction Hash: ABCD1234EFGH5678 (for audit)

Terms & Conditions:
- Goods/Services once sold are not returnable.
- Invoice subject to jurisdiction of [state] courts.
- E-way bill (if applicable): [number]

Declaration:
I/We certify that particulars given above are true
and correct. This invoice is generated by computer
and is valid without signature.
```

**Implementation:**
- No new database schema (all data already exists)
- Just formatting/display
- Links back to ledger posting for audit

---

## 💾 Database Schema Changes (Minimal)

**New Tables Needed:** NONE (Minimal approach)

**Existing Tables Used:**
- `Company` (from Phase 0)
- `Party` (from Phase 7A)
- `Ledger` (from Phase 0 - posting destination)
- `VoucherDetail` (from Phase 0 - line items)
- `DocumentTemplate` (from Phase 7D)
- `DocumentAsset` (from Phase 7G - for signature/logo)

**New Fields (Additive Only - No Structural Changes):**

```sql
-- Add to VoucherDetail table (if not exists):
ALTER TABLE VoucherDetail ADD COLUMN IF NOT EXISTS 
  hsnSacCode VARCHAR(10),          -- HSN/SAC code
  barcode VARCHAR(50),              -- Generated barcode string
  gstRateApplicable DECIMAL(5,2);   -- GST % for this item

-- Add to DocumentTemplate table (if not exists):
ALTER TABLE DocumentTemplate ADD COLUMN IF NOT EXISTS 
  includeQRCode BOOLEAN DEFAULT TRUE,      -- Show QR in footer
  includeBarcode BOOLEAN DEFAULT TRUE,     -- Show barcode per item
  signatureImagePath VARCHAR(255),         -- Path to signature image
  includeLedgerReference BOOLEAN DEFAULT TRUE;  -- Show ledger post ref
```

**No Deletion or Migration Needed:**
- All existing structures remain untouched
- Only additive changes
- Backward compatible

---

## 🖨️ Print/Preview/Share Features

### Print Workflow
```
1. User opens Sale/Purchase transaction
2. Clicks "Print Invoice" button
3. System generates PDF from template + data
4. Opens Print dialog (OS native)
5. User selects printer (or "Save as PDF")
6. Invoice printed with:
   ✅ All sections (header, items, tax, signature, QR, footer)
   ✅ Barcode per item
   ✅ QR code in footer
   ✅ High resolution (300 DPI recommended)
   ✅ A4 size (8.27 × 11.69 inches)
```

### Preview Workflow
```
1. User opens Sale/Purchase transaction
2. Clicks "Preview Invoice" button
3. System generates PDF (in-memory)
4. Opens in device PDF viewer (or web view)
5. User can:
   ✅ Zoom in/out
   ✅ Check layout before printing
   ✅ Verify QR code visible
   ✅ Check barcode legibility
   ✅ Go back to edit if needed
```

### Share Workflow
```
1. User opens Sale/Purchase transaction
2. Clicks "Share Invoice" button
3. System generates PDF file
4. Opens share menu (WhatsApp, Email, Drive, etc.)
5. User selects channel
6. Invoice sent with:
   ✅ PDF attachment
   ✅ All details intact
   ✅ QR code scannable
   ✅ Print-ready format
```

### Implementation Details
- Reuse existing `DocumentRendering` (Phase 7D, frozen)
- Use Android PDF libraries: `iText` or `PdfBox` (lightweight)
- Generate PDF in background (async, no UI freeze)
- Cache PDF temporarily (deleted after 24 hours)
- No cloud upload needed (offline-first)

---

## 📦 Implementation Phases (Sequence)

### Phase 1: Basic Invoice Display (1-2 days)
**Dependency:** Phase 7J (UI) frozen

**Tasks:**
1. Create `InvoiceHeaderComposable` (company details + invoice info)
2. Create `ItemLineComposable` (item details + HSN/SAC)
3. Create `TaxSummaryComposable` (tax breakdown)
4. Create `FooterComposable` (ledger reference)
5. Integrate into existing Sale/Purchase screens
6. Test on Redmi 13 (layout, text visibility)

**Deliverable:** Invoice preview on screen (no print yet)

---

### Phase 2: Barcode & QR Code Integration (1-2 days)
**Dependency:** Phase 1 complete

**Tasks:**
1. Add HSN/SAC barcode generation per item
2. Add QR code generation (transaction ID + IRN)
3. Integrate barcode display below each item
4. Integrate QR code in footer
5. Test barcode scannability (use barcode scanner app)
6. Test QR code readability (use QR reader app)

**Deliverable:** Invoices with working barcodes & QR codes

---

### Phase 3: PDF Export & Print (1-2 days)
**Dependency:** Phase 2 complete

**Tasks:**
1. Create PDF template using Android PDF library
2. Render all invoice components to PDF
3. Add Print button (OS native print dialog)
4. Add Preview button (PDF viewer)
5. Add Download button (save to device storage)
6. Test on Redmi 13 (actual print to printer)
7. Test PDF preview in device PDF app

**Deliverable:** Printable, previewable PDF invoices

---

### Phase 4: Share Integration (0.5-1 day)
**Dependency:** Phase 3 complete

**Tasks:**
1. Add Share button (OS share menu)
2. Create temporary PDF file
3. Trigger Android Intent for share
4. Test with WhatsApp, Email, Google Drive
5. Verify file permissions

**Deliverable:** Shareable invoices via WhatsApp, Email, etc.

---

### Phase 5: Signature Panel & Digital Branding (0.5-1 day)
**Dependency:** Phase 1 complete (can be parallel)

**Tasks:**
1. Fetch company logo from `BusinessProfile`
2. Display as signature/seal in footer
3. Add "Authorized By" text line
4. Add authorization date field
5. Test rendering in PDF (logo visibility, size)

**Deliverable:** Professional-looking signed invoices

---

### Phase 6: Device Testing & Refinement (1-2 days)
**Dependency:** Phases 1-5 complete

**Tasks:**
1. End-to-end testing on Redmi 13:
   - Create sale → generate invoice → preview → print → share
   - Create purchase → generate invoice → check ITC section
   - Create debit note → verify format
   - Create credit note → verify format
2. Test edge cases:
   - Long item descriptions (text wrapping)
   - Many items (multi-page invoice)
   - Large numbers (formatting, rounding)
   - Unicode characters (state names in Hindi)
3. Performance testing (PDF generation time < 2 seconds)
4. Accessibility testing (text size, contrast)

**Deliverable:** Production-ready invoice system

---

### Phase 7: Integration with Phase 7J & Phase 8 (0.5-1 day)
**Dependency:** Phase 6 complete

**Tasks:**
1. Integrate Invoice UI into Main Dashboard
2. Add "View Invoice" button to all Sale/Purchase entries
3. Ensure GSTR-ready invoice format (for Phase 8 GSTR generation)
4. Verify invoices match ledger postings (audit trail)
5. Test with GSTR-3B generation (Phase 8)

**Deliverable:** Full invoice lifecycle in LedgerPrime

---

## 📊 Time Estimate Summary

| Phase | Task | Hours | Days | Dependency |
|-------|------|-------|------|-----------|
| 1 | Basic invoice display | 8-10 | 1 | Phase 7J frozen |
| 2 | Barcode + QR code | 8-10 | 1 | Phase 1 |
| 3 | PDF export & print | 8-10 | 1 | Phase 2 |
| 4 | Share integration | 4-6 | 0.5 | Phase 3 |
| 5 | Signature panel | 4-6 | 0.5 | Phase 1 |
| 6 | Device testing | 8-10 | 1-2 | Phases 1-5 |
| 7 | GSTR integration | 4-6 | 0.5 | Phase 6 |
| **Total** | **Invoice System** | **44-58** | **5-7 days** | **Sequential** |

---

## 🔧 Technology Stack (Lightweight, Cost-Effective)

| Component | Technology | Why | Cost |
|-----------|-----------|-----|------|
| Invoice Layout | Jetpack Compose | Already used in Phase 7J | ✅ FREE |
| PDF Generation | iText 7 (or PdfBox) | Lightweight, no external API | ✅ FREE |
| Barcode Generation | ZXing (or ML Kit) | Android native, proven | ✅ FREE |
| QR Code Generation | Existing `domain/qrbarcode/` | Already implemented | ✅ FREE |
| Image Rendering | Android Canvas API | Native, no dependency | ✅ FREE |
| Print Dialog | Android PrintManager | OS native, no dependency | ✅ FREE |
| Share Dialog | Android Intent | OS native, no dependency | ✅ FREE |
| Signature Image | ImageAsset (from Phase 7G) | Already in database | ✅ FREE |

**Total Cost: ₹0 (All open-source or Android native)**

---

## ✅ Success Criteria (Invoice Management)

| Criterion | Pass Criteria |
|-----------|--------------|
| Invoice Display | All sections visible & formatted correctly on Redmi 13 |
| Barcode Per Item | Scannable with barcode scanner app, correct HSN/SAC |
| QR Code | Scannable with QR reader app, contains transaction ID |
| PDF Export | Generates < 2 seconds, file size < 500KB |
| Print | Prints correctly to any Bluetooth printer, A4 size |
| Preview | Opens in device PDF viewer, all details visible |
| Share | Successfully shared via WhatsApp, Email, Drive |
| Signature | Company seal/logo visible, "Authorized By" line present |
| Ledger Link | Invoice references ledger posting ID, audit trail visible |
| GST Compliance | Invoice format matches GSTR requirements, Tax amounts correct |
| Double-Entry | Invoice shows debit/credit entries (from ledger posting) |
| Edge Cases | Handles multi-line items, large numbers, long descriptions |

---

## 🚀 Next Steps

1. **Phase 7J Freeze First** (Main Implementation Plan)
   - Complete all Priority 1-4 tasks
   - Get Phase 7J committed and tagged

2. **Then Start Invoice System**
   - Begin Phase 1 (Basic Display)
   - Follow sequential phases (1 → 2 → 3 → 4 → 5 → 6 → 7)

3. **Parallel Phase 8**
   - Once Invoice System reaches Phase 5
   - Start Phase 8 (GST Returns) testing
   - Invoice format feeds into GSTR-3B/9 generation

---

## ⚠️ Constraints & Assumptions

✅ **Double-Entry Integrity:** Every invoice must map to ledger entries (Debit = Credit)  
✅ **India-Only Format:** Only GST tax invoices, Indian state codes, rupee currency  
✅ **No API Dependency:** QR code generated locally, no external service needed  
✅ **Lightweight:** No bloatware, minimal dependencies, < 5MB APK size addition  
✅ **Offline-First:** All invoice generation works without internet  
✅ **Cost-Effective:** All free/open-source libraries, no paid services  
✅ **Audit Ready:** Every invoice linked to ledger posting for auditor review  
✅ **GSTR-Ready:** Invoice format feeds into GSTR-3B/9 generation (Phase 8)  

---

## 📚 Reference Documents

| Document | Purpose |
|----------|---------|
| [Main IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | Overall Phase 7J & Phase 8 roadmap |
| [FEATURES_AND_PROGRESS.md](FEATURES_AND_PROGRESS.md) | Project status summary |
| [docs/42_DOCUMENT_TEMPLATE_ARCHITECTURE.md](docs/42_DOCUMENT_TEMPLATE_ARCHITECTURE.md) | Template system (Phase 7D) |
| [docs/45_DOCUMENT_BRANDING.md](docs/45_DOCUMENT_BRANDING.md) | Branding & signature (Phase 7G) |
| [docs/50_AUTOMATION_ARCHITECTURE.md](docs/50_AUTOMATION_ARCHITECTURE.md) | Automation framework (Phase 7F) |

---

**Status:** Ready to integrate into Phase 7J schedule  
**Start Date:** Post-Phase 7J freeze (2026-09-16+)  
**Target Completion:** 5-7 days of focused development  

---

**This invoice system is designed to be:**
- ✅ Easy to use
- ✅ Small & lightweight
- ✅ Compliant with Indian tax invoices
- ✅ Integrated with double-entry accounting
- ✅ Printable, previewable, shareable
- ✅ Cost-effective (no paid APIs)
- ✅ Ready for GSTR filing (Phase 8)
