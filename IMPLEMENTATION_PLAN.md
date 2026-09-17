# LedgerPrime - Double-Entry Accounting Implementation Plan
**India-Only Accounting System | 2026-09-13**

## Overview
LedgerPrime is a **double-entry accounting system** for Android, not an ERP. This plan focuses on completing Phase 7J (UI) and Phase 8 (GST Returns) with full compliance to Indian accounting standards (GST, GSTR filing, Indian state codes, Indian rupee only).

### Key Constraint: Double-Entry Accounting Only
- ✅ Every transaction creates exactly 2 ledger entries (Debit = Credit)
- ✅ Trial Balance always balances (Debit Total = Credit Total)
- ✅ No ERP features (no HR, CRM, Manufacturing, Supply Chain)
- ✅ Pure accounting: Ledgers, Journals, Financial Statements, GST Compliance

---

## 🎯 Priority 1: Bug Fixes & Verification (2-3 days)

### 1.1 Fix Debtor/Creditor Selection in Money Screens
**Time:** 1-2 hours  
**Files:** `AccountingViewModel.kt`, Money screens (ReceiveMoneyScreen, PayMoneyScreen)  
**Impact on Ledgers:** Ensures double-entry posting works for all payment scenarios  
**Tasks:**
- [ ] Add "+ Add new Debtor/Creditor" option to dropdown in Money screens
- [ ] Integrate with existing `CreatePartyDialog`
- [ ] Test inline debtor/creditor creation (both immediate posting)
- [ ] Verify dropdown reloads after party created
- [ ] Confirm both debit and credit entries post to ledgers correctly

### 1.2 Add Barcode/QR Scan Feature for HSN/SAC (GST Item Classification)
**Time:** 1-2 hours  
**Files:** `DataToolsScreen.kt`, `domain/qrbarcode/`  
**Impact on Ledgers:** HSN/SAC codes determine GST rate (CGST/SGST/IGST/CESS) in double-entry posting  
**Tasks:**
- [ ] Add "Scan Barcode" button to Data Tools for HSN/SAC code scanning
- [ ] Reuse existing barcode generation logic
- [ ] Integrate camera permission handling
- [ ] Test scan accuracy on device
- [ ] Verify scanned HSN/SAC feeds correctly into sale/purchase entry

### 1.3 Device Testing of Recent Fixes (Double-Entry Validation)
**Time:** 1-2 hours  
**Device:** Redmi 13  
**Tasks:**
- [ ] Fresh APK build (`./gradlew assembleDebug`)
- [ ] Clear app data & reinstall
- [ ] Test 4 fixed scenarios with double-entry verification:
  - [ ] Add new debtor from Money screen → confirm both ledger entries (Debit Money/Credit Debtor)
  - [ ] Scan barcode in Data Tools → HSN/SAC applies correct GST rate
  - [ ] Create debtor with GSTIN → state name displays + GST slabs load (CGST/SGST/IGST rates per state)
  - [ ] Trigger GST posting block (missing state) → ledger opens auto for correction
- [ ] Document any new issues found
- [ ] Verify Trial Balance remains balanced

### 1.4 Full Regression Test - Double-Entry Accounting Focus
**Time:** 1 hour  
**Tasks:**
- [ ] Create company (Account + Inventory mode)
- [ ] Add customer with GSTIN (GST Registered)
- [ ] Add supplier with GSTIN (GST Registered)
- [ ] Post Sale with GST:
  - [ ] Verify 4 ledger entries created: Debit Receivable / Debit GST Receivable, Credit Sales Revenue / Credit GST Payable
  - [ ] Confirm GST rate applied correctly per HSN/SAC and state (CGST + SGST = total tax)
  - [ ] Check ITC (Input Tax Credit) eligibility for purchased items
- [ ] Post Purchase with GST (same verification as above)
- [ ] View Trial Balance → **Total Debits = Total Credits (fundamental check)**
- [ ] View GST Register → verify GSTR-1 outward and inward data matches postings
- [ ] Export to PDF/CSV → all ledger entries visible
- [ ] Verify all 440+ tests still pass: `./gradlew testDebugUnitTest`

---

## 🎯 Priority 2: Print/View/Share Enhancement (2-3 days)

### 2.1 Audit & List Missing Print/View/Share Features
**Time:** 1 hour  
**Impact on Double-Entry:** Audit trail + document proof required for GST compliance  
**Tasks:**
- [ ] Identify all screens where Print/View/Share needed:
  - [ ] Sale entry screen (print GST invoice for audit)
  - [ ] Purchase entry screen (print bill for matching with ledger)
  - [ ] Money management (Receive/Pay) - print receipt for audit trail
  - [ ] Invoice Dashboard - print/share invoice with parties
  - [ ] Reports center - export financial statements for auditor
  - [ ] Financial statements (Trial Balance, P&L, Balance Sheet) - print for filing
- [ ] Document exact locations where buttons needed
- [ ] Note any GST-specific print requirements (Invoice registration, HSN/SAC display, tax summary)

### 2.2 Add Print/View/Share to Core Accounting Screens
**Time:** 2-3 hours  
**Files:** Sales, Purchases, Money, Invoice screens  
**Double-Entry Validation:** Each printed document must show complete debit/credit entries  
**Tasks:**
- [ ] Reuse existing `DocumentRendering` architecture
- [ ] Add floating action button (FAB) or top menu with options:
  - [ ] Print to device printer (for audit trail)
  - [ ] Download as PDF (Indian GST invoice format - GSTR-compliant)
  - [ ] Share via WhatsApp/Email (party confirmation)
  - [ ] Preview before print (verify debit/credit entries visible)
- [ ] Test on 5 document types:
  - [ ] GST Invoice (Sale with Registered buyer - IGST for inter-state, CGST+SGST for intra-state)
  - [ ] GST Bill (Purchase with Registered seller)
  - [ ] Debit Note (GST return adjustment - decreases liability)
  - [ ] Credit Note (GST return adjustment - decreases receivable)
  - [ ] Payment Voucher (Money receipt - must show which ledger updated)
- [ ] Ensure consistent UI & ledger detail visibility across all screens
- [ ] Verify PDF shows all double-entry posting details (for auditor review)

### 2.3 Test Print/View/Share Flow on Device
**Time:** 1-2 hours  
**Tasks:**
- [ ] Fresh APK build & install
- [ ] Test on Redmi 13 (India device):
  - [ ] Create GST Sale → Print → Verify Invoice shows: Item, HSN, Qty, Rate, Amount, CGST, SGST, Total (with ledger reference)
  - [ ] Create GST Purchase → Share via WhatsApp → Verify recipient receives readable PDF
  - [ ] View Trial Balance Report → Download PDF → Check Total Debits = Total Credits displayed
  - [ ] Preview Payment Receipt → Confirm ledger updates shown (e.g., "Debit Bank A/c, Credit Cash")
- [ ] Verify no crashes or permission issues
- [ ] Test file storage & sharing permissions
- [ ] Confirm all ledger entries visible in printed documents

---

## 🎯 Priority 3: Visual Enhancements (1-2 days)

### 3.1 Sales vs Purchases Visual Distinction
**Time:** 1-2 hours  
**Files:** Sales & Purchases screens  
**Double-Entry Context:** Different posting patterns (Sales: Debit Receivable / Credit Revenue; Purchase: Debit Expense / Credit Payable)  
**Tasks:**
- [ ] Add visual icon/color differentiation:
  - [ ] Sales: Green color + outward-arrow icon (money flows out to customer)
  - [ ] Purchases: Blue color + inward-arrow icon (money flows in from supplier)
- [ ] Update tab headers with icons
- [ ] Update screen backgrounds with subtle tint (green vs blue)
- [ ] Ensure contrast & accessibility (readable on Redmi 13)
- [ ] Verify no ledger posting logic changes (pure UI)

### 3.2 Improve Indian State Code Display (Critical for GST)
**Time:** 30 minutes  
**Files:** GST registration screens, Debtor/Creditor creation  
**Double-Entry Impact:** State code determines CGST/SGST (intra-state) vs IGST (inter-state) posting  
**Tasks:**
- [ ] Verify state name shows in all GST-relevant screens:
  - [ ] Debtor/Creditor creation dialog: "State: TN - Tamil Nadu" (code + full name)
  - [ ] Ledger creation dialog: Display company state + debtor state
  - [ ] Company GST settings: State selection shows "MH - Maharashtra" (Registered with this state for GST)
  - [ ] Settings sync screen: Confirm state codes synced correctly
- [ ] Format: "TN - Tamil Nadu" (2-letter state code + full name)
- [ ] Ensure state selection affects GST rate table loading (CGST/SGST for same state, IGST for different state)

### 3.3 Quality Check - Double-Entry Validation
**Time:** 1 hour  
**Tasks:**
- [ ] Compile code: `./gradlew compileDebugKotlin` → no errors
- [ ] Run unit tests: `./gradlew testDebugUnitTest` → 440+ tests passing
- [ ] Build APK: `./gradlew assembleDebug` → clean build
- [ ] Install & verify on device
- [ ] Create test company & post one sale → check Trial Balance still balances
- [ ] Confirm all 4 ledger entries created (Receivable, Sales Revenue, GST Receivable, GST Payable)

---

## 🎯 Priority 4: Commit & Freeze Phase 7J (1 day)

### 4.1 Code Review & Cleanup
**Time:** 1 hour  
**Tasks:**
- [ ] Review all changes from priorities 1-3
- [ ] Remove any debug code or logging
- [ ] Verify documentation updated (in-code comments explain double-entry posting)
- [ ] Ensure no new dependencies added
- [ ] Check no hardcoded American/European features crept in

### 4.2 Final Device Testing - Complete Double-Entry Workflows
**Time:** 1-2 hours  
**Tasks:**
- [ ] End-to-end workflow test (full double-entry verification):
  - [ ] Create 1 company with GST (Account + Inventory mode)
  - [ ] Create 2 customers (1 Registered, 1 Unregistered) with GSTIN
  - [ ] Create 2 suppliers (1 Registered, 1 Unregistered) with GSTIN
  - [ ] Post GST Sale to Registered customer (inter-state):
    - Verify 4 ledger entries: Debit Receivable / Credit Sales (Debit GST Receivable / Credit IGST Payable)
  - [ ] Post GST Sale to Unregistered customer:
    - Verify posting: Debit Cash/Receivable / Credit Sales (No GST for Unregistered in most cases)
  - [ ] Post GST Purchase from Registered supplier (inter-state):
    - Verify 4 ledger entries: Debit Expense / Credit Payable (Debit IGST Receivable / Credit GST Payable)
  - [ ] Receive Payment from customer:
    - Verify 2 ledger entries: Debit Bank / Credit Receivable (complete payment reconciliation)
  - [ ] Print/view/share all documents → confirm ledger details visible
  - [ ] Generate Trial Balance → **Debit Total = Credit Total**
  - [ ] Generate P&L → Verify Sales and Expense accounts match ledger
  - [ ] Generate Balance Sheet → Verify Assets = Liabilities + Equity
  - [ ] Export to PDF/CSV/JSON → all ledger entries included
- [ ] Test edge cases:
  - [ ] Low device storage → confirm PDF still exports
  - [ ] Sync interrupted → confirm data not double-posted
  - [ ] Large numbers in calculations → verify no rounding errors in GST
  - [ ] Multiple companies → confirm ledger isolation (no cross-company posting)
  - [ ] HSN/SAC code changes → verify GST rate updates correctly in new postings

### 4.3 Commit to Git (Phase 7J - India-Only Double-Entry Accounting)
**Time:** 30 minutes  
**Tasks:**
- [ ] Run all tests: `./gradlew testDebugUnitTest` → verify 440+ passing
- [ ] Build final APK: `./gradlew assembleDebug` → clean
- [ ] Commit message: 
  ```
  Phase 7J: UI bug fixes, Print/View/Share, visual polish - FROZEN
  
  - Fixed debtor/creditor selection in Money screens
  - Added barcode scan for HSN/SAC (double-entry GST posting)
  - Enhanced Print/View/Share for all accounting documents
  - Visual distinction for Sales (Green) vs Purchases (Blue)
  - Indian state code display for GST compliance
  - Device tested on Redmi 13, Trial Balance validated
  - All 440+ tests passing, zero double-entry ledger issues
  - India-only, not ERP, no American/European features
  ```
- [ ] Tag: `v7j-freeze-2026-09-13`
- [ ] Push to repository

---

## ⏸️ Priority 5: Phase 8 (When Ready) - Indian GST Returns (GSTR-1/3B/9)

### 5.1 Device Test GSTR-3B & GSTR-9 (Double-Entry Ledger Mapping)
**Time:** 3-4 hours  
**Device:** Redmi 13  
**Configs to Test:** Account + Inventory, Service-based  
**Double-Entry Focus:** GSTR returns are derived from ledger postings; verify all ledger entries map correctly to GSTR forms  
**Tasks:**
- [ ] **Config 1: Account + Inventory (Goods Trading)**
  - [ ] Create company with GST Registration
  - [ ] Post real GST Sale (intra-state to Registered buyer: CGST + SGST)
  - [ ] Post real GST Sale (inter-state to Registered buyer: IGST)
  - [ ] Post GST Purchase (intra-state from Registered seller: CGST + SGST with ITC)
  - [ ] Post GST Purchase (inter-state from Registered seller: IGST with ITC)
  - [ ] Generate GSTR-3B (Monthly return):
    - [ ] Prepare return → Verify outward supplies match Sale ledger
    - [ ] Validate return → Check ITC eligibility matches Purchase ledger + HSN/SAC
    - [ ] Preview PDF → Confirm CGST, SGST, IGST, CESS totals calculated from ledger
    - [ ] Export as GSTR JSON → Verify transaction mapping to ledger entries
    - [ ] Download CSV → Check all line items match posted vouchers
  - [ ] Generate GSTR-9 (Annual return - same steps as GSTR-3B but cumulative)

- [ ] **Config 2: Service-Based (Service Providers)**
  - [ ] Create company with GST Registration
  - [ ] Post GST Sale (service to intra-state Registered client: CGST + SGST)
  - [ ] Post GST Sale (service to inter-state Registered client: IGST)
  - [ ] Post GST Purchase (service inputs from Registered vendor: may have ITC eligibility)
  - [ ] Generate GSTR-3B & GSTR-9 (same verification as above)
  - [ ] Confirm HSN/SAC code applied correctly (998599 for services vs goods codes)

- [ ] Verify no double-posting in GSTR (each ledger entry should appear exactly once)
- [ ] Confirm GSTR-1 outward supplies match sales ledger
- [ ] Confirm GSTR-2 inward supplies match purchase ledger

### 5.2 Independent Audit (Double-Entry Ledger Integrity)
**Time:** 2-3 hours  
**Tasks:**
- [ ] Fresh-context code review of Phase 8 implementation:
  - [ ] Verify posting engine unchanged (same double-entry logic as Phase 0-7J)
  - [ ] Check GSTR mapping reads FROM ledgers, not FROM transaction DTOs
  - [ ] Ensure no duplicate posting paths (one transaction = one set of ledger entries)
  - [ ] Verify ITC calculation matches ledger (Input Tax Credit from Purchase postings only)
- [ ] Verify math accuracy:
  - [ ] CGST + SGST = Total Tax (intra-state transaction)
  - [ ] IGST = Total Tax (inter-state transaction)
  - [ ] CESS added correctly (if applicable per HSN/SAC)
  - [ ] Total tax on GSTR-3B matches tax accounts in Trial Balance
- [ ] Check PDF generation:
  - [ ] All required GSTR fields populated from ledger
  - [ ] PDF matches GSTR JSON export (no discrepancies)
  - [ ] PDF format compliant with Income Tax Department guidelines
- [ ] Check ledger export:
  - [ ] All sale/purchase postings included in JSON/CSV
  - [ ] HSN/SAC codes preserved in export
  - [ ] Debit/Credit direction correct for each entry
- [ ] Document audit findings (0 findings = PASS)

### 5.3 Fix Defects & Commit Phase 8
**Time:** 1-2 hours  
**Tasks:**
- [ ] Fix any defects found in audit
- [ ] Run full test suite: `./gradlew testDebugUnitTest` → all passing
- [ ] Build final APK: `./gradlew assembleDebug` → clean
- [ ] Commit message:
  ```
  Phase 8: GSTR-3B & GSTR-9 Device Tested + Audited - FROZEN
  
  - Tested on Account + Inventory config with real GST transactions
  - Tested on Service-based config with real GST service entries
  - GSTR-3B & GSTR-9 generation verified for both configs
  - Ledger entries mapped correctly to GSTR forms
  - ITC eligibility verified from Purchase ledger postings
  - PDF, CSV, JSON exports validated
  - Independent audit passed, zero findings
  - All 440+ tests passing, double-entry integrity confirmed
  - Ready for production use in India (GST-compliant)
  ```
- [ ] Tag: `v8-freeze-2026-09-XX`
- [ ] Push to repository

---

## 📋 Priority 6: Phase 2a (DO NOT START YET)

### Waiting For:
- [ ] Explicit instruction to proceed
- [ ] Phase 7J fully committed & verified
- [ ] Fresh device ready for testing

### When Ready (7-step detailed plan exists):
**Reference:** `docs/PHASE_7J_INVOICE_GST_INTEGRATION_PLAN.md`

**Sub-steps (follow sequence strictly):**
1. Add Inclusive/Exclusive pricing toggle in Sale/Purchase entry (affects ledger debit/credit calculation)
2. Add live CGST/SGST/IGST/CESS breakdown display (before posting - double-entry preview)
3. Add Cess input field (for certain HSN/SAC codes - adds extra debit/credit line if applicable)
4. Migrate Credit/Debit Notes to Invoice Dashboard (currently in old modal - must work with double-entry)
5. Integrate full GST details in invoice form (all fields: GSTIN, state, HSN/SAC, tax slabs)
6. Add validation & error messages (prevent invalid ledger entries)
7. Device test & audit (verify no double-posting, Trial Balance balances)

---

## ✅ Success Criteria (Double-Entry Validation)

| Milestone | Pass Criteria |
|-----------|--------------|
| Priority 1 Complete | All 4 bugs fixed, device verified, 440+ tests pass, Trial Balance balances |
| Priority 2 Complete | Print/View/Share on 5+ screens, no crashes, PDF generated with ledger details |
| Priority 3 Complete | Visual distinction clear, state names display everywhere, no ledger changes |
| Priority 4 Complete | Phase 7J committed with tag, all tests passing, double-entry integrity verified |
| Priority 5 Complete | GSTR-3B/9 tested on 2+ configs, audit passed (0 findings), Phase 8 committed |
| Priority 6 Ready | Waiting for explicit GO signal, no code changes made |

---

## 📊 Time Estimate Summary

| Priority | Task | Hours | Days | Double-Entry Test |
|----------|------|-------|------|------------------|
| 1 | Bug fixes + device test | 4-5 | 1 | Trial Balance check |
| 2 | Print/View/Share | 4-5 | 1 | Ledger details in PDF |
| 3 | Visual polish | 3-4 | 0.5 | No posting changes |
| 4 | Commit & freeze (Phase 7J) | 2-3 | 0.5 | Complete workflows |
| **Total (Phase 7J)** | **Priorities 1-4** | **13-17** | **3-4 days** | **All verified** |
| 5 | Phase 8 testing & audit (GSTR) | 6-9 | 1-2 days | Ledger mapping |
| 6 | Phase 2a (deferred) | 8-12 | 2-3 days | Invoice ledger logic |

**Total to Phase 7J Frozen:** 3-4 days (assuming no major blockers)

---

## 🔍 Daily Standup Template

Use this each day to track progress:

```
Date: YYYY-MM-DD
Priority Working On: [1/2/3/4/5]

✅ Completed Today:
- [ ] Task A
- [ ] Task B

🔄 In Progress:
- [ ] Task C

🚫 Blockers:
- [ ] Issue X (solution: Y)

📈 Tests Passing: 440/445 (GST double-entry validation included)
📦 Build Status: CLEAN / FAIL
📱 Device Status: TESTED / UNTESTED (Redmi 13)

💹 Double-Entry Validation:
- [ ] Trial Balance: Debit Total = Credit Total
- [ ] No duplicate posting errors
- [ ] Ledger entries match printed documents

Next: [Priority to work on tomorrow]
```

---

## 🚀 How to Execute (Double-Entry Accounting Focus)

1. **Before Starting:** Read this entire plan end-to-end
2. **Daily:** Pick ONE priority section and complete all tasks
3. **After Each Priority:** 
   - Run tests: `./gradlew testDebugUnitTest` → verify passing
   - Device test: Create 1 Sale + 1 Purchase on Redmi 13
   - Check Trial Balance: **Must balance** (Debit = Credit)
4. **Git Discipline:** Commit after each priority completes (don't mix priorities)
5. **No Skipping:** Follow order strictly (1 → 2 → 3 → 4 → 5 → 6)
6. **Double-Entry Validation:** Every completed priority must have ledger verification
7. **India Compliance:** All tests must use Indian GST rules, state codes, rupee currency only

---

## ⚠️ Critical Constraints (India-Only Double-Entry Accounting System)

✅ **Pure Double-Entry Accounting:** Every transaction = exactly 2 ledger entries (Debit = Credit)  
✅ **India-Only:** No American, European, or multi-national features  
✅ **GST Mandatory:** All transactions must calculate and post GST (CGST/SGST/IGST/CESS)  
✅ **Indian State Codes Only:** TN, MH, KA, DL, etc. - 2-letter state abbreviations  
✅ **Indian Rupee (INR) Only:** No multi-currency support  
✅ **Trial Balance Must Balance:** Fundamental check after every transaction  
✅ **NOT an ERP:** Zero HR, CRM, Manufacturing, Supply Chain, Payroll features  
✅ **No Duplicate Posting:** One transaction = one set of ledger entries (never double-counted)  
✅ **Audit Trail:** Every posting must be printable/exportable for auditor review  

---

## 📚 Reference Architecture

**Double-Entry Posting Pattern:**
```
Transaction: Sale to Customer with GST (Intra-state, Registered)

Ledger Entries Created:
1. Debit: Receivable (Customer) → Amount (Revenue + Tax)
2. Credit: Sales Revenue → Amount (Gross Revenue)
3. Debit: GST Receivable (Tax Input) → Tax Amount
4. Credit: GST Payable (Tax Output) → Tax Amount

Trial Balance:
Total Debits (1 + 3) = Total Credits (2 + 4) ✓ BALANCED
```

---

**Status:** Ready to execute  
**Start Date:** 2026-09-13  
**Target Completion Phase 7J:** 2026-09-16 (Frozen)  
**Target Completion Phase 8:** 2026-09-18 (If on track)  

---

**This plan is locked for India-only double-entry accounting system. No ERP features. No non-Indian tax systems. GSTR compliance mandatory.**

### 1.1 रुपये भुगतान स्क्रीन में क्रेडिटर/डेबिटर चयन ठीक करें
Fix Creditor/Debtor Selection in Money Screens
**समय:** 1-2 घंटे | **Time:** 1-2 hours  
**Files:** `AccountingViewModel.kt`, Money screens (ReceiveMoneyScreen, PayMoneyScreen)  
**काम:**
- [ ] "+ नया डेबिटर/क्रेडिटर जोड़ें" विकल्प ड्रॉपडाउन में जोड़ें (Add "+ Add new Debtor/Creditor" option)
- [ ] मौजूदा `CreatePartyDialog` के साथ एकीकृत करें (Integrate with existing CreatePartyDialog)
- [ ] इनलाइन डेबिटर/क्रेडिटर निर्माण परीक्षण करें (Test inline debtor/creditor creation)
- [ ] सत्यापित करें कि ड्रॉपडाउन रीलोड हो जाता है (Verify dropdown reloads)

### 1.2 बारकोड स्कैन सुविधा जोड़ें (GST-संबंधित)
Add Barcode/QR Scan Feature (GST-related HSN/SAC codes)
**समय:** 1-2 घंटे | **Time:** 1-2 hours  
**Files:** `DataToolsScreen.kt`, `domain/qrbarcode/` (HSN/SAC के लिए)  
**काम:**
- [ ] डेटा टूल्स में "बारकोड स्कैन करें" बटन जोड़ें (Add "Scan Barcode" button for HSN/SAC)
- [ ] मौजूदा बारकोड जनरेशन लॉजिक का पुनः उपयोग करें (Reuse existing barcode generation)
- [ ] कैमरा अनुमति हैंडलिंग एकीकृत करें (Integrate camera permissions)
- [ ] डिवाइस पर परीक्षण करें (Test on device)

### 1.3 हाल के फिक्स का डिवाइस परीक्षण (भारतीय GST मोड में)
Device Testing of Recent Fixes (Indian GST Configuration)
**समय:** 1-2 घंटे | **Time:** 1-2 hours  
**डिवाइस:** Redmi 13 (भारतीय डिवाइस)  
**काम:**
- [ ] ताजा APK बिल्ड (`./gradlew assembleDebug`)
- [ ] ऐप डेटा साफ करें और पुनः इंस्टॉल करें (Clear app data & reinstall)
- [ ] 4 फिक्स्ड परिदृश्य परीक्षण करें:
  - [ ] रुपये स्क्रीन से नया डेबिटर/क्रेडिटर जोड़ें (Add new debtor/creditor from Money screen)
  - [ ] डेटा टूल्स में बारकोड स्कैन करें (Scan barcode in Data Tools)
  - [ ] GSTIN के साथ डेबिटर/क्रेडिटर बनाएं (state name दिखता है) - Create debtor with GSTIN (state name shows)
  - [ ] GST पोस्टिंग ब्लॉक ट्रिगर करें (Trigger GST posting block - ledger opens auto)
- [ ] कोई भी नई समस्या दस्तावेज़ करें (Document any new issues)

### 1.4 पूर्ण प्रतिगमन परीक्षण चलाएं (भारतीय GST के लिए)
Run Full Regression Test (Indian GST Focus)
**समय:** 1 घंटा | **Time:** 1 hour  
**काम:**
- [ ] कंपनी बनाएं (खाता + इन्वेंटरी मोड) - Create company (Account + Inventory mode)
- [ ] GSTIN के साथ ग्राहक जोड़ें (Add customer with GSTIN)
- [ ] GSTIN के साथ आपूर्तिकर्ता जोड़ें (Add supplier with GSTIN)
- [ ] बिक्री पोस्ट करें (GST गणना जांचें) - Post sale (check GST calculations)
- [ ] खरीद पोस्ट करें (GST गणना जांचें) - Post purchase (check GST calculations)
- [ ] ट्रायल बैलेंस देखें (View Trial Balance)
- [ ] GST रजिस्टर देखें (View GST Register)
- [ ] PDF/CSV में निर्यात करें (Export to PDF/CSV)
- [ ] सभी 440+ परीक्षण पास सत्यापित करें (Verify all 440+ tests pass): `./gradlew testDebugUnitTest`

---

## 🎯 प्राथमिकता 2: प्रिंट/व्यू/शेयर वृद्धि (2-3 दिन)
Priority 2: Print/View/Share Enhancement (2-3 days)

### 2.1 लापता सुविधाओं का ऑडिट और सूची
Audit & List Missing Features
**समय:** 1 घंटा | **Time:** 1 hour  
**काम:**
- [ ] जांचें कि किन स्क्रीन में प्रिंट/व्यू/शेयर है:
  - [ ] बिक्री प्रविष्टि स्क्रीन (Sale entry screen)
  - [ ] खरीद प्रविष्टि स्क्रीन (Purchase entry screen)
  - [ ] रुपये प्रबंधन (Receive/Pay) (Money management)
  - [ ] इनवॉइस डैशबोर्ड (Invoice Dashboard)
  - [ ] रिपोर्ट केंद्र (Reports center)
  - [ ] वित्तीय विवरण (Financial statements)
- [ ] बटन की आवश्यकता वाले स्थानों को दस्तावेज़ करें (Document exact locations where buttons needed)

### 2.2 मुख्य स्क्रीन पर प्रिंट/व्यू/शेयर जोड़ें (भारतीय कर अनुरूप)
Add Print/View/Share to Core Screens (Indian Tax Compliant)
**समय:** 2-3 घंटे | **Time:** 2-3 hours  
**Files:** Sales, Purchases, Money, Invoice screens  
**काम:**
- [ ] मौजूदा `DocumentRendering` आर्किटेक्चर का पुनः उपयोग करें (Reuse existing DocumentRendering architecture)
- [ ] फ्लोटिंग एक्शन बटन (FAB) या शीर्ष मेनू जोड़ें:
  - [ ] प्रिंट (PDF प्रिंट) - Print (to printer)
  - [ ] डाउनलोड (PDF के रूप में) - Download (as PDF)
  - [ ] शेयर करें (WhatsApp, Email, आदि) - Share (WhatsApp, Email, etc.)
  - [ ] भेजें (Email/डिजिटल हस्ताक्षर के लिए) - Send (for Email/digital signature)
- [ ] 5 विभिन्न दस्तावेज़ प्रकारों पर परीक्षण करें (GST इनवॉइस, डेबिट नोट, आदि) - Test on GST Invoice, Debit Note, etc.
- [ ] सभी स्क्रीन में UI सुसंगत सुनिश्चित करें (Ensure consistent UI across screens)

### 2.3 प्रिंट/व्यू/शेयर प्रवाह परीक्षण करें
Test Print/View/Share Flow
**समय:** 1-2 घंटे | **Time:** 1-2 hours  
**काम:**
- [ ] ताजा APK बिल्ड व इंस्टॉल (Fresh APK build & install)
- [ ] Redmi 13 पर परीक्षण:
  - [ ] GST इनवॉइस बनाएं → प्रिंट करें (Create GST Invoice → print)
  - [ ] खरीद बिल बनाएं → शेयर करें (Create Purchase Bill → share)
  - [ ] वित्तीय रिपोर्ट देखें → PDF डाउनलोड (View financial report → download PDF)
  - [ ] प्रिंट से पहले प्रीव्यू (Preview before print)
- [ ] कोई क्रैश या अनुमति समस्या सत्यापित करें (Verify no crashes or permission issues)

---

## 🎯 प्राथमिकता 3: विजुअल सुधार (1-2 दिन)
Priority 3: Visual Enhancements (1-2 days)

### 3.1 बिक्री बनाम खरीद विजुअल अंतर (भारतीय व्यावसायिक शैली)
Sales vs Purchases Visual Distinction
**समय:** 1-2 घंटे | **Time:** 1-2 hours  
**Files:** Sales & Purchases screens  
**काम:**
- [ ] आइकन/रंग अंतर जोड़ें (भारतीय रंग पसंद):
  - बिक्री: हरा/बाहर का तीर (Sales: Green/arrow-out)
  - खरीद: नीला/अंदर का तीर (Purchases: Blue/arrow-in)
- [ ] टैब हेडर अपडेट करें (Update tab headers)
- [ ] स्क्रीन पृष्ठभूमि अपडेट करें (Update screen backgrounds)
- [ ] विपरीतता & पहुंच सुनिश्चित करें (Ensure contrast & accessibility)

### 3.2 भारतीय राज्य कोड और नाम डिस्प्ले में सुधार (महत्वपूर्ण GST के लिए)
Improve Indian State Code Display (Critical for GST)
**समय:** 30 मिनट | **Time:** 30 min  
**Files:** GST registration screens, Debtor/Creditor creation  
**काम:**
- [ ] हर जगह राज्य का नाम सत्यापित करें:
  - [ ] डेबिटर/क्रेडिटर संवाद (Debtor/Creditor creation dialog)
  - [ ] खाता संवाद (Ledger creation dialog)
  - [ ] कंपनी GST सेटिंग्स (Company GST settings)
  - [ ] सेटिंग्स सिंक स्क्रीन (Settings sync screen)
- [ ] Format: "TN - तमिलनाडु" (कोड + नाम) (code + name)

### 3.3 गुणवत्ता जांच (Indian GST सत्यापन के साथ)
Quality Check
**समय:** 1 घंटा | **Time:** 1 hour  
**काम:**
- [ ] कोड कंपाइल करें: `./gradlew compileDebugKotlin`
- [ ] परीक्षण चलाएं: `./gradlew testDebugUnitTest`
- [ ] APK बिल्ड करें: `./gradlew assembleDebug`
- [ ] डिवाइस पर इंस्टॉल व सत्यापित करें (Install & verify on device)

---

## 🎯 प्राथमिकता 4: Phase 7J को प्रतिबद्ध व फ्रीज करें (1 दिन)
Priority 4: Commit & Freeze Phase 7J (1 day)

### 4.1 कोड समीक्षा व सफाई
Code Review & Cleanup
**समय:** 1 घंटा | **Time:** 1 hour  
**काम:**
- [ ] प्राथमिकता 1-3 से सभी परिवर्तन समीक्षा करें (Review all changes)
- [ ] कोई डीबग कोड बाकी न रहे सत्यापित करें (Check no debug code left)
- [ ] दस्तावेज़ अद्यतन सत्यापित करें (Verify documentation updated)
- [ ] कोई नई निर्भरता न जुड़ी हो सुनिश्चित करें (Ensure no new dependencies)

### 4.2 अंतिम डिवाइस परीक्षण (Indian GST व भारतीय कर नियमों के लिए)
Final Device Testing (Indian GST Compliance)
**समय:** 1-2 घंटे | **Time:** 1-2 hours  
**काम:**
- [ ] अंत-से-अंत वर्कफ़्लो परीक्षण:
  - [ ] GST के साथ कंपनी बनाएं (Create company with GST)
  - [ ] ग्राहक व आपूर्तिकर्ता बनाएं (Create customers & suppliers)
  - [ ] GST के साथ बिक्री पोस्ट करें (Post sales with GST)
  - [ ] GST के साथ खरीद पोस्ट करें (Post purchases with GST)
  - [ ] दस्तावेज़ प्रिंट/व्यू/शेयर करें (Print/view/share documents)
  - [ ] वित्तीय रिपोर्ट जनरेट करें (Generate financial reports)
  - [ ] PDF/CSV/JSON में निर्यात करें (Export to PDF/CSV/JSON)
- [ ] किनारे के मामलों का परीक्षण करें:
  - [ ] कम भंडारण (Low storage)
  - [ ] सिंक में रुकावट (Connection interrupted)
  - [ ] बड़ी संख्या में गणना (Long numbers in calculations)
  - [ ] कई कंपनियां (Multiple companies)

### 4.3 Git में प्रतिबद्ध करें (भारतीय GST अनुपालन के साथ)
Commit to Git
**समय:** 30 मिनट | **Time:** 30 min  
**काम:**
- [ ] सभी परीक्षण चलाएं: `./gradlew testDebugUnitTest` (verify 440+ pass)
- [ ] अंतिम APK बिल्ड करें: `./gradlew assembleDebug`
- [ ] प्रतिबद्धि संदेश: "Phase 7J: UI bug fixes, Print/View/Share, visual polish - FROZEN (India-only, GST-compliant)"
- [ ] टैग: `v7j-freeze-2026-09-13-india`
- [ ] रिपॉजिटरी में पुश करें (Push to repository)

---

## ⏸️ प्राथमिकता 5: Phase 8 (जब तैयार हो) - भारतीय GST रिटर्न परीक्षण
Priority 5: Phase 8 (When Ready) - Indian GST Returns (GSTR-1/3B/9)

### 5.1 GSTR-3B & GSTR-9 डिवाइस परीक्षण (भारतीय कर फाइलिंग के लिए)
Device Test GSTR-3B & GSTR-9 (Indian Tax Filing)
**समय:** 3-4 घंटे | **Time:** 3-4 hours  
**डिवाइस:** Redmi 13  
**परीक्षण करने के लिए कॉन्फ़िग:** खाता + इन्वेंटरी, सेवा-आधारित (Account + Inventory, Service-based)  
**काम:**
- [ ] खाता + इन्वेंटरी कंपनी बनाएं (Create Account + Inventory company)
- [ ] वास्तविक GST बिक्री पोस्ट करें (Post real GST sale)
- [ ] वास्तविक GST खरीद पोस्ट करें (Post real GST purchase)
- [ ] GSTR-3B जनरेट करें (रिटर्न जनरेट करें):
  - [ ] रिटर्न तैयार करें (Prepare return)
  - [ ] रिटर्न सत्यापित करें (Validate return)
  - [ ] PDF प्रीव्यू (Preview PDF)
  - [ ] GSTR JSON के रूप में निर्यात (Export as GSTR JSON)
  - [ ] CSV डाउनलोड (Download CSV)
- [ ] GSTR-9 जनरेट करें (same steps) - वार्षिक रिटर्न (Generate GSTR-9 - annual return)
- [ ] सेवा-आधारित कॉन्फ़िग के लिए दोहराएं (Repeat for Service-based config)

### 5.2 स्वतंत्र ऑडिट (भारतीय GST अनुपालन के लिए)
Independent Audit
**समय:** 2-3 घंटे | **Time:** 2-3 hours  
**काम:**
- [ ] Phase 8 कोड की ताजा संदर्भ समीक्षा (Fresh context review)
- [ ] पोस्टिंग इंजन में कोई संशोधन न हो सत्यापित करें (Verify no posting engine modifications)
- [ ] गणित की सटीकता रिटर्न में सत्यापित करें (Verify math accuracy in returns)
- [ ] PDF/निर्यात में कोई दोष जांचें (Check for any defects in PDF/export)
- [ ] ऑडिट निष्कर्ष दस्तावेज़ करें (Document audit findings)

### 5.3 दोष सुधारें व प्रतिबद्ध करें
Fix Defects & Commit
**समय:** 1-2 घंटे | **Time:** 1-2 hours  
**काम:**
- [ ] मिले किसी भी दोष को ठीक करें (Fix any defects found)
- [ ] पूरी परीक्षण सूट फिर से चलाएं (Run full test suite again)
- [ ] प्रतिबद्धि: "Phase 8: GSTR-3B & GSTR-9 - device tested + audited - FROZEN (India GST compliant)"
- [ ] टैग: `v8-freeze-2026-09-XX-india-gst`

---

## 📋 प्राथमिकता 6: Phase 2a (अभी शुरू न करें)
Priority 6: Phase 2a (DO NOT START YET)

### तैयारी कर रहे:
Waiting For:
- [ ] आगे बढ़ने के लिए स्पष्ट निर्देश (Explicit instruction to proceed)
- [ ] Phase 7J पूरी तरह प्रतिबद्ध व सत्यापित (Phase 7J fully committed & verified)
- [ ] डिवाइस परीक्षण के लिए तैयार (Fresh device ready for testing)

### जब तैयार हो (7-step plan exists):
When Ready:
1. समावेशी/एक्सक्लूसिव मूल्य निर्धारण टॉगल जोड़ें (Add Inclusive/Exclusive pricing toggle)
2. लाइव CGST/SGST/IGST/CESS विभाजन जोड़ें (Add live CGST/SGST/IGST/CESS breakdown)
3. Cess इनपुट फील्ड जोड़ें (Add Cess input field)
4. डेबिट/क्रेडिट नोट को इनवॉइस डैशबोर्ड में माइग्रेट करें (Migrate Credit/Debit Notes to Invoice Dashboard)
5. इनवॉइस फॉर्म में GST विवरण एकीकृत करें (Integrate GST details in invoice form)
6. सत्यापन व त्रुटि संदेश जोड़ें (Add validation & error messages)
7. डिवाइस परीक्षण व ऑडिट (Device test & audit)

**संदर्भ:** `docs/PHASE_7J_INVOICE_GST_INTEGRATION_PLAN.md` (7-step sub-phase plan - भारतीय GST के अनुसार)

---

## ✅ सफलता मानदंड (Indian GST के अनुसार)
Success Criteria

| माइलस्टोन | पास मानदंड |
|-----------|-----------|
| प्राथमिकता 1 पूर्ण | सभी 4 बग ठीक, डिवाइस सत्यापित, 440+ परीक्षण पास |
| प्राथमिकता 2 पूर्ण | 5+ स्क्रीन पर प्रिंट/व्यू/शेयर, कोई क्रैश न हो, PDF जनरेट |
| प्राथमिकता 3 पूर्ण | विजुअल अंतर स्पष्ट, हर जगह राज्य नाम दिखे |
| प्राथमिकता 4 पूर्ण | Phase 7J प्रतिबद्ध टैग के साथ, सभी परीक्षण पास |
| प्राथमिकता 5 पूर्ण | GSTR-3B/9, 2+ कॉन्फ़िग पर परीक्षित, ऑडिट पास, Phase 8 प्रतिबद्ध |
| प्राथमिकता 6 तैयार | GO सिग्नल की प्रतीक्षा, कोड परिवर्तन न हो |

---

## 📊 समय अनुमान सारांश (भारतीय GST परीक्षण सहित)
Time Estimate Summary

| प्राथमिकता | काम | घंटे | दिन |
|-----------|------|------|-----|
| 1 | Bug fixes + device test | 4-5 | 1 |
| 2 | Print/View/Share | 4-5 | 1 |
| 3 | Visual polish (भारतीय रंग, राज्य प्रदर्शन) | 3-4 | 0.5 |
| 4 | Commit & freeze (Phase 7J, India-only) | 2-3 | 0.5 |
| **Total (Phase 7J)** | **Priorities 1-4** | **13-17** | **3-4 दिन** |
| 5 | Phase 8 testing & audit (GSTR-3B/9) | 6-9 | 1-2 दिन |
| 6 | Phase 2a (deferred) | 8-12 | 2-3 दिन |

**Phase 7J को फ्रीज करने तक कुल समय:** 3-4 दिन (कोई बड़ी रुकावट न मानते हुए)

---

## 🔍 दैनिक स्टैंडअप टेम्पलेट (Daily Standup Template)

हर दिन प्रगति ट्रैक करने के लिए यह टेम्पलेट उपयोग करें:

```
तारीख / Date: YYYY-MM-DD
प्राथमिकता / Priority Working On: [1/2/3/4/5]

✅ आज पूरा किया / Completed Today:
- [ ] काम A / Task A
- [ ] काम B / Task B

🔄 प्रगति में / In Progress:
- [ ] काम C / Task C

🚫 रुकावटें / Blockers:
- [ ] समस्या X / Issue X (समाधान / solution: Y)

📈 परीक्षण पास हो रहे हैं / Tests Passing: 440/445 (GST validation tests included)
📦 बिल्ड स्थिति / Build Status: CLEAN / FAIL
📱 डिवाइस स्थिति / Device Status: TESTED / UNTESTED (Redmi 13 - India device)

अगला / Next: [कल काम करने की प्राथमिकता / Priority to work on tomorrow]
```

---

## 🚀 कार्यान्वयन कैसे करें (भारतीय GST अनुपालन के साथ)
How to Execute

1. **शुरू करने से पहले:** इस योजना को अंत तक पढ़ें (Read this plan end-to-end)
2. **प्रतिदिन:** एक प्राथमिकता चुनें और सभी काम पूरा करें (Pick one priority section and complete all tasks)
3. **प्रत्येक प्राथमिकता के बाद:** परीक्षण चलाएं और डिवाइस पर सत्यापित करें (Run tests and verify on device)
4. **Git अनुशासन:** प्रत्येक प्राथमिकता पूरी होने के बाद प्रतिबद्ध करें (Commit after each priority completes)
5. **कोई छोड़ना नहीं:** क्रम में सख्ती से पालन करें (Follow order strictly: 1 → 2 → 3 → 4 → 5 → 6)
6. **GST अनुपालन:** सभी परीक्षण भारतीय GST नियमों के साथ किए जाएं (All tests must be GST-compliant)

---

## ⚠️ महत्वपूर्ण नोट्स (Important Notes - India-Only)

✅ **यह सिर्फ भारतीय व्यवसायों के लिए है** - This is India-only, not ERP  
✅ **GST अनिवार्य है** - GST is mandatory for all transactions  
✅ **कोई अमेरिकी/यूरोपीय सुविधा नहीं** - No US/European features added  
✅ **भारतीय राज्य कोड का उपयोग** - Indian state codes only  
✅ **GSTR फाइलिंग के लिए तैयार** - Ready for GSTR compliance filing  
✅ **कोई ERP सुविधाएं नहीं** - No HR/CRM/Manufacturing features  

---

**स्थिति / Status:** कार्यान्वयन के लिए तैयार (Ready to execute)  
**शुरुआती तारीख / Start Date:** 2026-09-13  
**लक्ष्य समय पूरा करना / Target Completion:** 2026-09-16 (Phase 7J frozen, India-only)  
**Phase 8 लक्ष्य / Phase 8 Target:** 2026-09-18 (GSTR-3B/9 tested, if all on track)  

---

**यह योजना सिर्फ भारतीय GST अनुपालन के लिए लॉक की गई है।**  
**This plan is locked for India-only GST compliance only.**

