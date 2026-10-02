package com.example.accounting

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.core.database.AppDatabase
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.taxation.gst.DocumentIdentityStatus
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.PurchaseDocumentIdentity
import com.example.accounting.domain.trading.TradingLineInput
import com.example.accounting.domain.trading.TradingWorkflowEngine
import com.example.accounting.domain.trading.TradingWorkflowResult
import com.example.accounting.domain.export.ExportFormat
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Phase 8 Step 13 - purchase document identity (supplier GSTIN + supplier invoice number + date) on the
 * GST fact, for future GSTR-2B matching. Runs the real production path against a real in-memory Room
 * database: `repository.postVoucher` / `postGstOnlyPurchase` -> `VoucherPostingEngine` ->
 * `PurchaseDocumentGuard` -> `gst_transactions`, then `getGstTransactionsForVoucher` back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PurchaseDocumentIdentityTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AccountingRepository
    private val companyId = "COMP_P13"
    private val fyId = "FY_2026_27_$companyId"
    private val supplierA = "LED_SUP_A"
    private val supplierB = "LED_SUP_B"
    private val gstinA = "29ABCDE1234F1Z5"
    private val gstinB = "29PQRST5678G1Z9"
    private val purchaseLedger = "LED_PUR_P13"

    private fun ledger(id: String, groupBare: String, gstin: String = "") = LedgerEntity(
        ledgerId = id, companyId = companyId, groupId = "${groupBare}_$companyId", name = id, code = id,
        openingBalancePaise = 0L, openingBalanceType = DrCr.CREDIT, currentBalancePaise = 0L, currentBalanceType = DrCr.CREDIT,
        gstin = gstin, pan = "", stateCode = "27", email = "", phone = "", address = "", bankAccountNumber = "", bankIfsc = "",
        isSystem = false, isActive = true, hsnSacCode = "", defaultTaxRate = 0.0
    )

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = AccountingRepository(db.accountingDao(), db)
        repo.seedInitialDataForCompany(companyId, "P13 Co", "27AAAAA0000A1Z5")
        db.accountingDao().insertLedger(ledger(supplierA, StandardSystemGroups.CREDITORS_GROUP_ID, gstinA))
        db.accountingDao().insertLedger(ledger(supplierB, StandardSystemGroups.CREDITORS_GROUP_ID, gstinB))
        db.accountingDao().insertLedger(ledger(purchaseLedger, StandardSystemGroups.PURCHASE_GROUP_ID))
        repo.ensureGstLedgersExist(companyId)
        repo.ensureRoundOffLedgerExists(companyId)
    }

    @After
    fun tearDown() { db.close() }

    private fun lineInput(gstRate: Double = 18.0, hsn: String = "8471") = TradingLineInput(
        itemId = "", itemName = "Goods", hsnSacCode = hsn, quantity = Quantity.fromDouble(1.0, "Nos"),
        rate = Money.fromPaise(1000_00L), gstRatePercent = gstRate
    )

    private val results = HashMap<String, TradingWorkflowResult>()

    /** Mirrors AccountingViewModel.postTradingDocument's purchase branch. */
    private suspend fun postPurchase(
        id: String, supplierId: String = supplierA, gstin: String = gstinA, number: String?, date: LocalDate? = null,
        fy: String = fyId, bookingDate: LocalDate = LocalDate.of(2026, 6, 10), gstRate: Double = 18.0, hsn: String = "8471"
    ): AccountingResult<Voucher> {
        val r = TradingWorkflowEngine.buildPurchase(
            voucherId = id, companyId = companyId, financialYearId = fy,
            supplierLedgerId = supplierId, supplierName = supplierId, supplierGstin = gstin,
            purchaseLedgerId = purchaseLedger, purchaseLedgerName = "Purchase", companyStateCode = "27", placeOfSupply = "27",
            lines = listOf(lineInput(gstRate, hsn)), gstLedgers = repo.resolveGstLedgerRefs(companyId),
            roundOffLedgerId = repo.resolveRoundOffLedgerRef(companyId).ledgerId, roundOffLedgerName = repo.resolveRoundOffLedgerRef(companyId).name,
            trackInventory = false, supplierDocumentNumber = number, supplierDocumentDate = date
        )
        results[id] = r
        val voucher = Voucher(
            voucherId = id, companyId = companyId, financialYearId = fy,
            voucherNumber = repo.generateNextVoucherNumber(companyId, fy, VoucherType.PURCHASE), voucherType = VoucherType.PURCHASE,
            date = bookingDate, referenceNumber = number ?: "", narration = "Being purchase",
            totalAmount = r.totalAmount, items = r.journalItems, createdBy = "TEST", partyGstin = gstin, isGstApplicable = true
        )
        return repo.postVoucher(voucher, stockLines = r.stockLines, gstTransactions = r.gstTransactions)
    }

    private suspend fun factsOf(voucherId: String) = repo.getGstTransactionsForVoucher(voucherId)

    // ---------------------------------------------------------------- persistence

    @Test
    fun recordedIdentity_isPersistedOnTheGstFact_andReadBackWithSupplierGstin() = runBlocking {
        assertTrue(postPurchase("P1", number = "  INV/2026/001 ", date = LocalDate.of(2026, 6, 3)) is AccountingResult.Success)
        val fact = factsOf("P1").single()
        assertEquals("INV/2026/001", fact.supplierDocumentNumber)
        assertEquals(LocalDate.of(2026, 6, 3), fact.supplierDocumentDate)
        assertEquals(gstinA, fact.partyGstin)
        assertEquals(GstDirection.INPUT, fact.direction)
        assertEquals(VoucherType.PURCHASE, fact.voucherType)
        assertEquals(DocumentIdentityStatus.RECORDED, fact.documentIdentityStatus)
        // the raw column is the ISO text, not a locale string
        assertEquals("2026-06-03", db.accountingDao().getGstTransactionsForVoucher("P1").single().supplierDocumentDate)
    }

    @Test
    fun missingIdentity_staysNull_neverDefaultedFromTheVoucherNumberOrDate() = runBlocking {
        assertTrue(postPurchase("P2", number = null) is AccountingResult.Success)
        val fact = factsOf("P2").single()
        assertNull(fact.supplierDocumentNumber)
        assertNull("the booking date must not be reused as the supplier's date", fact.supplierDocumentDate)
        assertEquals(DocumentIdentityStatus.NOT_RECORDED, fact.documentIdentityStatus)
        assertNull(db.accountingDao().getGstTransactionsForVoucher("P2").single().supplierDocumentNumber)
    }

    @Test
    fun blankNumber_isStoredAsNotRecorded_notAsAnEmptyString() = runBlocking {
        assertTrue(postPurchase("P3", number = "   ") is AccountingResult.Success)
        assertNull(factsOf("P3").single().supplierDocumentNumber)
    }

    @Test
    fun numberOnly_isPartial() = runBlocking {
        postPurchase("P4", number = "B-77")
        assertEquals(DocumentIdentityStatus.PARTIAL, factsOf("P4").single().documentIdentityStatus)
    }

    @Test
    fun aDebitNote_doesNotInheritTheOriginalPurchasesSupplierDocument() = runBlocking {
        postPurchase("P5", number = "INV-5", date = LocalDate.of(2026, 6, 1))
        val note = TradingWorkflowEngine.buildNote("DN5", VoucherType.DEBIT_NOTE, emptyList(), emptyList(), factsOf("P5"))
        note.gstTransactions.forEach {
            assertNull(it.supplierDocumentNumber); assertNull(it.supplierDocumentDate)
            assertEquals(DocumentIdentityStatus.NOT_RECORDED, it.documentIdentityStatus)
        }
        val gstOnly = TradingWorkflowEngine.buildGstOnlyNote(VoucherType.DEBIT_NOTE, factsOf("P5"), LocalDate.of(2026, 6, 20))
        gstOnly.forEach { assertNull(it.supplierDocumentNumber); assertNull(it.supplierDocumentDate) }
    }

    @Test
    fun aSalesRow_neverCarriesSupplierIdentity() {
        val sale = TradingWorkflowEngine.buildSale(
            voucherId = "S1", companyId = companyId, financialYearId = fyId, customerLedgerId = "C", customerName = "C", customerGstin = "",
            salesLedgerId = "SL", salesLedgerName = "Sales", companyStateCode = "27", placeOfSupply = "27", lines = listOf(lineInput()),
            gstLedgers = runBlocking { repo.resolveGstLedgerRefs(companyId) }, roundOffLedgerId = "RO", roundOffLedgerName = "RO", trackInventory = false
        )
        sale.gstTransactions.forEach { assertNull(it.supplierDocumentNumber); assertNull(it.supplierDocumentDate) }
    }

    // ---------------------------------------------------------------- duplicate detection

    @Test
    fun sameSupplierSameNumber_isRejected_includingCaseAndWhitespaceVariants() = runBlocking {
        assertTrue(postPurchase("D1", number = "INV-100") is AccountingResult.Success)
        val dup = postPurchase("D2", number = "  inv-100 ")
        assertTrue("a second booking of the same supplier document must be rejected", dup is AccountingResult.Failure)
        val msg = (dup as AccountingResult.Failure).error.message
        assertTrue(msg, msg.contains("already booked") && msg.contains("D1"))
        assertTrue("nothing of the rejected purchase may be persisted", factsOf("D2").isEmpty())
        assertNull(db.accountingDao().getVoucherById(companyId, "D2"))
    }

    @Test
    fun theSameNumberFromADifferentSupplier_isAllowed() = runBlocking {
        assertTrue(postPurchase("E1", number = "INV-1") is AccountingResult.Success)
        assertTrue(postPurchase("E2", supplierId = supplierB, gstin = gstinB, number = "INV-1") is AccountingResult.Success)
    }

    @Test
    fun aDifferentNumberFromTheSameSupplier_isAllowed() = runBlocking {
        assertTrue(postPurchase("F1", number = "INV-1") is AccountingResult.Success)
        assertTrue(postPurchase("F2", number = "INV-2") is AccountingResult.Success)
    }

    @Test
    fun purchasesWithNoRecordedNumber_areNeverDuplicates() = runBlocking {
        assertTrue(postPurchase("G1", number = null) is AccountingResult.Success)
        assertTrue(postPurchase("G2", number = null) is AccountingResult.Success)
    }

    @Test
    fun aCancelledPurchase_doesNotBlockTheCorrectedRepost() = runBlocking {
        assertTrue(postPurchase("H1", number = "INV-9") is AccountingResult.Success)
        assertTrue(repo.deleteVoucherSafely(companyId, fyId, "H1") is AccountingResult.Success)
        assertTrue("a cancelled purchase is out of the books and must not block", postPurchase("H2", number = "INV-9") is AccountingResult.Success)
    }

    @Test
    fun theSameNumberInAnotherFinancialYear_isNotADuplicate() = runBlocking {
        // The key is supplier + number WITHIN one financial year (suppliers restart numbering each year).
        assertTrue(postPurchase("Y1", number = "INV-1") is AccountingResult.Success)
        val nextFy = "FY_2027_28_$companyId"
        assertTrue(postPurchase("Y2", number = "INV-1", fy = nextFy, bookingDate = LocalDate.of(2027, 5, 10)) is AccountingResult.Success)
        assertEquals(
            PurchaseDocumentIdentity.duplicateKey(gstinA, supplierA, "INV-1"),
            PurchaseDocumentIdentity.duplicateKey(gstinA.lowercase(), "OTHER_LEDGER", " inv-1 ")
        )
        assertNotNull(PurchaseDocumentIdentity.duplicateKey("", supplierA, "INV-1")) // unregistered supplier: keyed by ledger
    }

    @Test
    fun aGstOnlyPurchase_isCheckedAgainstVoucherPurchases_andRecordsItsOwnIdentity() = runBlocking {
        assertTrue(postPurchase("I1", number = "INV-GO") is AccountingResult.Success)
        val dup = repo.postGstOnlyPurchase(
            companyId, fyId, supplierA, listOf(lineInput()), LocalDate.of(2026, 6, 11),
            supplierDocumentNumber = "inv-go", supplierDocumentDate = LocalDate.of(2026, 6, 9)
        )
        assertTrue("GST-only purchase must hit the same duplicate rule", dup is AccountingResult.Failure)

        val ok = repo.postGstOnlyPurchase(
            companyId, fyId, supplierA, listOf(lineInput()), LocalDate.of(2026, 6, 11),
            supplierDocumentNumber = "INV-GO-2", supplierDocumentDate = LocalDate.of(2026, 6, 9)
        )
        val row = (ok as AccountingResult.Success).data.single()
        assertEquals("INV-GO-2", row.supplierDocumentNumber)
        assertEquals(LocalDate.of(2026, 6, 9), row.supplierDocumentDate)
        val stored = db.accountingDao().getGstTransactionsByGroupId(companyId, row.transactionGroupId).single()
        assertEquals("INV-GO-2", stored.supplierDocumentNumber)
        assertEquals("2026-06-09", stored.supplierDocumentDate)

        // and a voucher purchase of that same number is now rejected the other way round
        assertTrue(postPurchase("I2", number = "INV-GO-2") is AccountingResult.Failure)
    }

    // ---------------------------------------------------------------- Step 14: supplier invoice date

    @Test
    fun s14_dateEntered_isPersistedInTheGstFact_separateFromTheBookingDate() = runBlocking {
        val booking = LocalDate.of(2026, 6, 10)
        val supplierDate = LocalDate.of(2026, 5, 28)
        assertTrue(postPurchase("S1", number = "SUP-1", date = supplierDate, bookingDate = booking) is AccountingResult.Success)
        val fact = factsOf("S1").single()
        assertEquals(supplierDate, fact.supplierDocumentDate)
        assertEquals("2026-05-28", db.accountingDao().getGstTransactionsForVoucher("S1").single().supplierDocumentDate)
        assertEquals("the voucher keeps its own booking date", booking.toString(), db.accountingDao().getVoucherById(companyId, "S1")!!.date)
        assertTrue(fact.supplierDocumentDate != booking)
        assertEquals(DocumentIdentityStatus.RECORDED, fact.documentIdentityStatus)
    }

    @Test
    fun s14_dateAbsent_isNotRecorded_evenWhenTheNumberIsGiven() = runBlocking {
        assertTrue(postPurchase("S2", number = "SUP-2", date = null) is AccountingResult.Success)
        val fact = factsOf("S2").single()
        assertNull(fact.supplierDocumentDate)
        assertNull(db.accountingDao().getGstTransactionsForVoucher("S2").single().supplierDocumentDate)
        assertEquals(DocumentIdentityStatus.PARTIAL, fact.documentIdentityStatus)
    }

    @Test
    fun s14_bookingDate_neverPopulatesTheSupplierDate() = runBlocking {
        // Any booking date, with or without a number: the supplier date is only ever what the user chose.
        listOf(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 10), LocalDate.of(2027, 3, 31)).forEachIndexed { i, booking ->
            assertTrue(postPurchase("B$i", number = "NB-$i", date = null, bookingDate = booking) is AccountingResult.Success)
            assertNull("booking date $booking leaked into the supplier date", factsOf("B$i").single().supplierDocumentDate)
            assertTrue(postPurchase("C$i", number = null, date = null, bookingDate = booking) is AccountingResult.Success)
            assertNull(factsOf("C$i").single().supplierDocumentDate)
        }
    }

    @Test
    fun s14_gstOnlyPurchase_carriesTheDate_andWithoutOneStaysNotRecorded() = runBlocking {
        val withDate = repo.postGstOnlyPurchase(
            companyId, fyId, supplierA, listOf(lineInput()), LocalDate.of(2026, 6, 11),
            supplierDocumentNumber = "GO-1", supplierDocumentDate = LocalDate.of(2026, 6, 2)
        ) as AccountingResult.Success
        assertEquals(LocalDate.of(2026, 6, 2), withDate.data.single().supplierDocumentDate)
        val without = repo.postGstOnlyPurchase(companyId, fyId, supplierA, listOf(lineInput()), LocalDate.of(2026, 6, 11)) as AccountingResult.Success
        assertNull("the GST-only booking date must not become the supplier date", without.data.single().supplierDocumentDate)
        assertEquals(LocalDate.of(2026, 6, 11), without.data.single().transactionDate)
    }

    @Test
    fun s14_cancelThenCorrect_theCorrectedDateWins_andTheCancelledRowIsOutOfTheFacts() = runBlocking {
        assertTrue(postPurchase("K1", number = "INV-K", date = LocalDate.of(2026, 5, 1)) is AccountingResult.Success)
        assertTrue(repo.deleteVoucherSafely(companyId, fyId, "K1") is AccountingResult.Success)
        // the correction: same supplier document, the date typed wrongly the first time is now right
        assertTrue(postPurchase("K2", number = "INV-K", date = LocalDate.of(2026, 5, 3)) is AccountingResult.Success)
        val live = repo.getGstTransactionsForCompanyFY(companyId, fyId).filter { it.direction == GstDirection.INPUT }
        assertEquals(listOf("K2"), live.map { it.voucherId }.distinct())
        assertEquals(LocalDate.of(2026, 5, 3), live.single().supplierDocumentDate)
        // the duplicate rule still holds against the live correction
        assertTrue(postPurchase("K3", number = "INV-K", date = LocalDate.of(2026, 5, 3)) is AccountingResult.Failure)
    }

    @Test
    fun s14_creditAndDebitNotes_doNotInheritTheDate() = runBlocking {
        postPurchase("N1", number = "INV-N", date = LocalDate.of(2026, 5, 20))
        val note = TradingWorkflowEngine.buildNote("DN-N1", VoucherType.DEBIT_NOTE, emptyList(), emptyList(), factsOf("N1"))
        note.gstTransactions.forEach { assertNull(it.supplierDocumentDate); assertNull(it.supplierDocumentNumber) }
        val creditNote = TradingWorkflowEngine.buildNote("CN-N1", VoucherType.CREDIT_NOTE, emptyList(), emptyList(), factsOf("N1"))
        creditNote.gstTransactions.forEach { assertNull(it.supplierDocumentDate) }
    }


    // ---------------------------------------------------------------- Step 17: propagation round-trips

    private val mapAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    /** Every list found under [key], anywhere in the parsed JSON tree. */
    private fun findLists(node: Any?, key: String, out: MutableList<List<Map<String, Any?>>> = mutableListOf()): List<List<Map<String, Any?>>> {
        when (node) {
            is Map<*, *> -> node.forEach { (k, v) -> if (k == key && v is List<*>) out += v.filterIsInstance<Map<String, Any?>>() else findLists(v, key, out) }
            is List<*> -> node.forEach { findLists(it, key, out) }
        }
        return out
    }

    private suspend fun syncGstDtos(): List<Map<String, Any?>> =
        db.accountingDao().getOutboxItemsByCompany(companyId, 500)
            .flatMap { findLists(mapAdapter.fromJson(it.payloadJson), "gstTransactions").flatten() }

    private suspend fun postSale(id: String, gstRate: Double = 18.0, hsn: String = "8471"): AccountingResult<Voucher> {
        db.accountingDao().insertLedger(ledger("LED_CUST_$id", StandardSystemGroups.DEBTORS_GROUP_ID, "29PQRST5678G1Z9"))
        db.accountingDao().insertLedger(ledger("LED_SALE_$id", StandardSystemGroups.SALES_GROUP_ID))
        val r = TradingWorkflowEngine.buildSale(
            voucherId = id, companyId = companyId, financialYearId = fyId, customerLedgerId = "LED_CUST_$id", customerName = "Cust",
            customerGstin = "29PQRST5678G1Z9", salesLedgerId = "LED_SALE_$id", salesLedgerName = "Sales", companyStateCode = "27", placeOfSupply = "27",
            lines = listOf(lineInput(gstRate, hsn)), gstLedgers = repo.resolveGstLedgerRefs(companyId),
            roundOffLedgerId = repo.resolveRoundOffLedgerRef(companyId).ledgerId, roundOffLedgerName = repo.resolveRoundOffLedgerRef(companyId).name, trackInventory = false
        )
        return repo.postVoucher(
            Voucher(
                voucherId = id, companyId = companyId, financialYearId = fyId, voucherNumber = repo.generateNextVoucherNumber(companyId, fyId, VoucherType.SALES),
                voucherType = VoucherType.SALES, date = LocalDate.of(2026, 6, 12), referenceNumber = "S-1", narration = "Being sale", totalAmount = r.totalAmount,
                items = r.journalItems, createdBy = "TEST", partyGstin = "29PQRST5678G1Z9", isGstApplicable = true
            ), stockLines = r.stockLines, gstTransactions = r.gstTransactions
        )
    }

    private suspend fun postDebitNoteFor(purchaseId: String, noteId: String): AccountingResult<Voucher> {
        val original = results.getValue(purchaseId)
        val note = TradingWorkflowEngine.buildNote(noteId, VoucherType.DEBIT_NOTE, original.journalItems, original.stockLines, original.gstTransactions)
        return repo.postVoucher(
            Voucher(
                voucherId = noteId, companyId = companyId, financialYearId = fyId, voucherNumber = repo.generateNextVoucherNumber(companyId, fyId, VoucherType.DEBIT_NOTE),
                voucherType = VoucherType.DEBIT_NOTE, date = LocalDate.of(2026, 6, 15), referenceNumber = "DN-REF", narration = "Being debit note", totalAmount = note.totalAmount,
                items = note.journalItems, createdBy = "TEST", partyGstin = gstinA, isGstApplicable = true, referenceVoucherId = purchaseId
            ), stockLines = note.stockLines, gstTransactions = note.gstTransactions
        )
    }

    @Test
    fun s17_syncOutbox_carriesRecordedNumberDateAndGstin_forAPurchaseVoucher() = runBlocking {
        assertTrue(postPurchase("Z1", number = "INV-Z1", date = LocalDate.of(2026, 5, 30)) is AccountingResult.Success)
        val dto = syncGstDtos().single()
        assertEquals("INV-Z1", dto["supplierDocumentNumber"])
        assertEquals("2026-05-30", dto["supplierDocumentDate"])
        assertEquals(gstinA, dto["partyGstin"])
        assertEquals("PURCHASE", dto["voucherType"])
    }

    @Test
    fun s17_syncOutbox_carriesTheIdentityForAGstOnlyPurchase_andNullWhenNotRecorded() = runBlocking {
        val withId = repo.postGstOnlyPurchase(companyId, fyId, supplierA, listOf(lineInput()), LocalDate.of(2026, 6, 11),
            supplierDocumentNumber = "GO-17", supplierDocumentDate = LocalDate.of(2026, 6, 1)) as AccountingResult.Success
        val without = repo.postGstOnlyPurchase(companyId, fyId, supplierA, listOf(lineInput()), LocalDate.of(2026, 6, 11)) as AccountingResult.Success
        val byGroup = syncGstDtos().associateBy { it["transactionGroupId"] }
        val a = byGroup.getValue(withId.data.single().transactionGroupId)
        assertEquals("GO-17", a["supplierDocumentNumber"]); assertEquals("2026-06-01", a["supplierDocumentDate"]); assertEquals(gstinA, a["partyGstin"])
        val b = byGroup.getValue(without.data.single().transactionGroupId)
        assertNull(b["supplierDocumentNumber"]); assertNull(b["supplierDocumentDate"])
    }

    @Test
    fun s17_nullStaysNull_inSyncExportAndFacts_forAPurchaseWithoutIdentity() = runBlocking {
        assertTrue(postPurchase("Z2", number = null) is AccountingResult.Success)
        val dto = syncGstDtos().single()
        assertNull(dto["supplierDocumentNumber"]); assertNull(dto["supplierDocumentDate"])
        val exported = repo.exportGstTransactions(companyId, fyId).single()
        assertNull(exported.supplierDocumentNumber); assertNull(exported.supplierDocumentDate)
        assertEquals(gstinA, exported.partyGstin)
    }

    @Test
    fun s17_exports_json_csv_gstrJson_carryTheIdentity_andTheGstin() = runBlocking {
        assertTrue(postPurchase("E1", number = "INV-E1", date = LocalDate.of(2026, 6, 2)) is AccountingResult.Success)
        assertTrue(postPurchase("E2", number = null) is AccountingResult.Success)

        val json = mapAdapter.fromJson((repo.exportGstTransactionsAs(companyId, fyId, ExportFormat.JSON) as AccountingResult.Success).data.content)
        val rows = findLists(json, "transactions").single().associateBy { it["voucherId"] }
        assertEquals("INV-E1", rows.getValue("E1")["supplierDocumentNumber"]); assertEquals("2026-06-02", rows.getValue("E1")["supplierDocumentDate"])
        assertEquals(gstinA, rows.getValue("E1")["partyGstin"])
        assertTrue("null must be exported as an explicit null, not omitted", rows.getValue("E2").containsKey("supplierDocumentNumber"))
        assertNull(rows.getValue("E2")["supplierDocumentNumber"]); assertNull(rows.getValue("E2")["supplierDocumentDate"])

        val csv = (repo.exportGstTransactionsAs(companyId, fyId, ExportFormat.CSV) as AccountingResult.Success).data.content.trim().lines()
        val header = csv.first().split(",")
        val iNum = header.indexOf("supplierDocumentNumber"); val iDate = header.indexOf("supplierDocumentDate"); val iVch = header.indexOf("voucherId"); val iGstin = header.indexOf("partyGstin")
        assertTrue("CSV must have the new columns: $header", iNum >= 0 && iDate >= 0)
        val byVoucher = csv.drop(1).map { it.split(",") }.associateBy { it[iVch] }
        assertEquals("INV-E1", byVoucher.getValue("E1")[iNum]); assertEquals("2026-06-02", byVoucher.getValue("E1")[iDate]); assertEquals(gstinA, byVoucher.getValue("E1")[iGstin])
        assertEquals("", byVoucher.getValue("E2")[iNum]); assertEquals("", byVoucher.getValue("E2")[iDate])

        val gstr = mapAdapter.fromJson((repo.exportGstTransactionsAs(companyId, fyId, ExportFormat.GSTR_JSON) as AccountingResult.Success).data.content)
        val inward = findLists(gstr, "inwardSupplies").single().associateBy { it["voucherId"] }
        assertEquals("INV-E1", inward.getValue("E1")["supplierDocumentNumber"]); assertEquals("2026-06-02", inward.getValue("E1")["supplierDocumentDate"])
        assertEquals(gstinA, inward.getValue("E1")["partyGstin"])
        assertNull(inward.getValue("E2")["supplierDocumentNumber"])
    }

    @Test
    fun s17_aSaleAndADebitNote_neverCarryPurchaseIdentity_inFactsSyncOrExports() = runBlocking {
        assertTrue(postPurchase("P17", number = "INV-17", date = LocalDate.of(2026, 5, 29)) is AccountingResult.Success)
        assertTrue(postSale("SALE17") is AccountingResult.Success)
        assertTrue(postDebitNoteFor("P17", "DN17") is AccountingResult.Success)

        // persisted facts
        assertEquals("INV-17", factsOf("P17").single().supplierDocumentNumber)
        listOf("SALE17", "DN17").forEach { v -> assertTrue("$v must have GST facts", factsOf(v).isNotEmpty())
            factsOf(v).forEach { assertNull(it.supplierDocumentNumber); assertNull(it.supplierDocumentDate) } }
        // the debit note kept the supplier GSTIN of the purchase it adjusts (identity != GSTIN)
        assertEquals(gstinA, factsOf("DN17").single().partyGstin)
        // sync
        val sync = syncGstDtos().groupBy { it["voucherType"] }
        sync.getValue("PURCHASE").forEach { assertEquals("INV-17", it["supplierDocumentNumber"]) }
        (sync.getValue("SALES") + sync.getValue("DEBIT_NOTE")).forEach { assertNull(it["supplierDocumentNumber"]); assertNull(it["supplierDocumentDate"]) }
        // exports
        val dtos = repo.exportGstTransactions(companyId, fyId)
        assertEquals(listOf("INV-17"), dtos.filter { it.supplierDocumentNumber != null }.map { it.supplierDocumentNumber })
        dtos.filter { it.voucherType != VoucherType.PURCHASE }.forEach { assertNull(it.supplierDocumentNumber); assertNull(it.supplierDocumentDate) }
        // the GSTR-shaped export has the keys on inward purchase lines only; outward lines never carry them
        val gstr = mapAdapter.fromJson((repo.exportGstTransactionsAs(companyId, fyId, ExportFormat.GSTR_JSON) as AccountingResult.Success).data.content)
        findLists(gstr, "outwardSupplies").single().forEach {
            assertFalse("outward ${it["voucherId"]} must not have supplier keys", it.containsKey("supplierDocumentNumber") || it.containsKey("supplierDocumentDate"))
        }
        // and the duplicate guard is not tripped by the note (a note has no supplier document number)
        assertTrue(postPurchase("P17B", number = "INV-17B") is AccountingResult.Success)
    }

    @Test
    fun s17_aQueuedPreStep13SyncEvent_stillDeserialisesAndReadsAsNotRecorded() {
        // Backward compatibility of the DTO: an event serialised before the fields existed has no such keys.
        val old = """{"gstTransactionId":"G","voucherType":"PURCHASE","partyLedgerId":"L","partyGstin":"$gstinA","placeOfSupply":"27","supplyType":"INTRA_STATE",
            "itemId":null,"hsnSacCode":"8471","quantityRaw":null,"taxableAmountPaise":100,"gstRatePercent":18.0,"cgstPaise":9,"sgstPaise":9,"igstPaise":0,"cessPaise":0,
            "direction":"INPUT","lineOrder":1}"""
        val dto = Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
            .adapter(com.example.accounting.domain.sync.SyncGstTransactionDto::class.java).fromJson(old)!!
        assertNull(dto.supplierDocumentNumber); assertNull(dto.supplierDocumentDate)
        assertEquals(gstinA, dto.partyGstin)
    }
    // ---------------------------------------------------------------- Step 18: correction flow (repository half)

    @Test
    fun s18_correction_carriesTheOriginalsIdentityToTheNewGstFact_andCancelsTheOriginal() = runBlocking {
        assertTrue(postPurchase("CR1", number = "INV-CR", date = LocalDate.of(2026, 5, 28)) is AccountingResult.Success)
        // exactly what AccountingViewModel.correctVoucher does: read the fact BEFORE cancelling
        val fact = factsOf("CR1").first()
        assertTrue(repo.deleteVoucherSafely(companyId, fyId, "CR1") is AccountingResult.Success)
        // ...then the prefilled form reposts with that identity (a different booking date must not matter)
        assertTrue(
            postPurchase("CR2", number = fact.supplierDocumentNumber, date = fact.supplierDocumentDate, bookingDate = LocalDate.of(2026, 6, 20)) is AccountingResult.Success
        )
        val corrected = factsOf("CR2").single()
        assertEquals("INV-CR", corrected.supplierDocumentNumber)
        assertEquals(LocalDate.of(2026, 5, 28), corrected.supplierDocumentDate)
        assertEquals(gstinA, corrected.partyGstin)
        assertTrue("original is cancelled, not edited in place", db.accountingDao().getVoucherById(companyId, "CR1")!!.isCancelled)
        assertEquals(listOf("CR2"), repo.getGstTransactionsForCompanyFY(companyId, fyId).map { it.voucherId }.distinct())
    }

    @Test
    fun s18_correction_ofAPurchaseWithoutADate_staysNotRecorded_notTheBookingDate() = runBlocking {
        assertTrue(postPurchase("CN1", number = "INV-CN", date = null, bookingDate = LocalDate.of(2026, 6, 10)) is AccountingResult.Success)
        val fact = factsOf("CN1").first()
        assertNull(fact.supplierDocumentDate)
        repo.deleteVoucherSafely(companyId, fyId, "CN1")
        assertTrue(postPurchase("CN2", number = fact.supplierDocumentNumber, date = fact.supplierDocumentDate, bookingDate = LocalDate.of(2026, 6, 25)) is AccountingResult.Success)
        val corrected = factsOf("CN2").single()
        assertEquals("INV-CN", corrected.supplierDocumentNumber)
        assertNull(corrected.supplierDocumentDate)
        assertEquals(DocumentIdentityStatus.PARTIAL, corrected.documentIdentityStatus)
    }

    @Test
    fun s18_duplicateProtection_stillWorksAfterACorrection() = runBlocking {
        postPurchase("CD1", number = "INV-CD", date = LocalDate.of(2026, 5, 1))
        val fact = factsOf("CD1").first()
        repo.deleteVoucherSafely(companyId, fyId, "CD1")
        assertTrue(postPurchase("CD2", number = fact.supplierDocumentNumber, date = fact.supplierDocumentDate) is AccountingResult.Success)
        // the live corrected purchase now owns the number: a further booking of it is a duplicate...
        assertTrue(postPurchase("CD3", number = "inv-cd") is AccountingResult.Failure)
        // ...also against another supplier it is not
        assertTrue(postPurchase("CD4", supplierId = supplierB, gstin = gstinB, number = "INV-CD") is AccountingResult.Success)
        // and correcting the corrected one again is still allowed (its own number never blocks itself)
        repo.deleteVoucherSafely(companyId, fyId, "CD2")
        assertTrue(postPurchase("CD5", number = "INV-CD", date = LocalDate.of(2026, 5, 2)) is AccountingResult.Success)
    }

    // ---------------------------------------------------------------- Step 19: GST rate/HSN through a correction (repository half)

    @Test
    fun s19_purchaseCorrection_readsTheOriginalGstDetailFromItsFact_andTheRepostPostsThem() = runBlocking {
        assertTrue(postPurchase("GP1", number = "INV-GP", date = LocalDate.of(2026, 5, 18), gstRate = 12.0, hsn = "9954") is AccountingResult.Success)
        // exactly what correctVoucher captures before cancelling: (rate, HSN) of the first GST fact
        val detail = factsOf("GP1").first().let { it.gstRatePercent to it.hsnSacCode }
        assertEquals(12.0 to "9954", detail)
        repo.deleteVoucherSafely(companyId, fyId, "GP1")
        assertTrue(postPurchase("GP2", number = "INV-GP", date = LocalDate.of(2026, 5, 18), gstRate = detail.first, hsn = detail.second) is AccountingResult.Success)
        val fact = factsOf("GP2").single()
        assertEquals(12.0, fact.gstRatePercent, 0.0); assertEquals("9954", fact.hsnSacCode)
        assertEquals("tax is the engine's own 12% of 1000.00 (IGST or CGST+SGST), unchanged", 120_00L, (fact.cgst + fact.sgst + fact.igst).paise)
        // and the identity is exactly as before (Steps 14/18)
        assertEquals("INV-GP", fact.supplierDocumentNumber); assertEquals(LocalDate.of(2026, 5, 18), fact.supplierDocumentDate)
    }

    @Test
    fun s19_saleCorrection_keepsItsOwnGstDetail_andAPurchasesDetailNeverLeaksIn() = runBlocking {
        assertTrue(postPurchase("GPX", number = "INV-GPX", gstRate = 5.0, hsn = "1001") is AccountingResult.Success)
        assertTrue(postSale("GS1", gstRate = 28.0, hsn = "8703") is AccountingResult.Success)
        val saleDetail = factsOf("GS1").first().let { it.gstRatePercent to it.hsnSacCode }
        assertEquals(28.0 to "8703", saleDetail)
        repo.deleteVoucherSafely(companyId, fyId, "GS1")
        assertTrue(postSale("GS2", gstRate = saleDetail.first, hsn = saleDetail.second) is AccountingResult.Success)
        val fact = factsOf("GS2").single()
        assertEquals(28.0, fact.gstRatePercent, 0.0); assertEquals("8703", fact.hsnSacCode)
        assertEquals(280_00L, (fact.cgst + fact.sgst + fact.igst).paise)
        assertNull(fact.supplierDocumentNumber)
        // the purchase booked alongside keeps its own detail
        assertEquals(5.0 to "1001", factsOf("GPX").first().let { it.gstRatePercent to it.hsnSacCode })
    }

    @Test
    fun s19_aVoucherWithNoGstFacts_hasNoGstDetailToPrefill() = runBlocking {
        // correctVoucher's read is `getGstTransactionsForVoucher(id).firstOrNull()?.let { rate to hsn }`: nothing recorded -> null
        assertTrue(factsOf("NO_SUCH_VOUCHER").firstOrNull()?.let { it.gstRatePercent to it.hsnSacCode } == null)
    }
    // ---------------------------------------------------------------- migration

    private fun columnInfo(db: SupportSQLiteDatabase, column: String): List<String> =
        db.query("PRAGMA table_info(gst_transactions)").use { c ->
            while (c.moveToNext()) {
                if (c.getString(c.getColumnIndexOrThrow("name")) == column) {
                    return listOf(c.getString(c.getColumnIndexOrThrow("type")), c.getInt(c.getColumnIndexOrThrow("notnull")).toString(), c.getString(c.getColumnIndexOrThrow("dflt_value")) ?: "null")
                }
            }
            emptyList()
        }

    @Test
    fun migration29To30_addsNullableColumns_keepsHistoricalRowsUntouched_andNeverBackfills() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(object : SupportSQLiteOpenHelper.Callback(29) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // gst_transactions exactly as it was at schema version 29
                    db.execSQL(
                        "CREATE TABLE gst_transactions (gstTransactionId TEXT NOT NULL PRIMARY KEY, companyId TEXT NOT NULL, financialYearId TEXT NOT NULL, " +
                            "voucherId TEXT, voucherType TEXT NOT NULL, partyLedgerId TEXT NOT NULL, partyGstin TEXT NOT NULL, placeOfSupply TEXT NOT NULL, " +
                            "supplyType TEXT NOT NULL, itemId TEXT, hsnSacCode TEXT NOT NULL, quantityRaw INTEGER, taxableAmountPaise INTEGER NOT NULL, " +
                            "gstRatePercent REAL NOT NULL, cgstPaise INTEGER NOT NULL, sgstPaise INTEGER NOT NULL, igstPaise INTEGER NOT NULL, cessPaise INTEGER NOT NULL, " +
                            "direction TEXT NOT NULL, lineOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, chargeType TEXT NOT NULL DEFAULT 'FORWARD_CHARGE', " +
                            "supplyNature TEXT NOT NULL DEFAULT 'NORMAL', transactionGroupId TEXT NOT NULL DEFAULT '', transactionDate TEXT, partyGstRegistrationStatus TEXT)"
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build()
        )
        val old = helper.writableDatabase
        // A historical purchase: it has a voucher whose free-text reference ("OLD-REF-7") exists elsewhere, but the fact itself has none.
        old.execSQL(
            "INSERT INTO gst_transactions (gstTransactionId, companyId, financialYearId, voucherId, voucherType, partyLedgerId, partyGstin, placeOfSupply, supplyType, " +
                "itemId, hsnSacCode, quantityRaw, taxableAmountPaise, gstRatePercent, cgstPaise, sgstPaise, igstPaise, cessPaise, direction, lineOrder, createdAt, " +
                "transactionGroupId) VALUES ('GT_OLD', 'C', 'FY', 'V_OLD', 'PURCHASE', 'LED_SUP_A', '29ABCDE1234F1Z5', '27', 'INTRA_STATE', NULL, '8471', NULL, 100000, 18.0, " +
                "9000, 9000, 0, 0, 'INPUT', 1, 1, 'V_OLD')"
        )

        AppDatabase.MIGRATION_29_30.migrate(old)

        old.query("SELECT supplierDocumentNumber, supplierDocumentDate, taxableAmountPaise, cgstPaise, partyGstin FROM gst_transactions WHERE gstTransactionId = 'GT_OLD'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("historical row must read NOT_RECORDED (NULL), never a fabricated number", c.isNull(0))
            assertTrue("historical row must read NOT_RECORDED (NULL), never a fabricated date", c.isNull(1))
            assertEquals("existing amounts are untouched", 100000L, c.getLong(2))
            assertEquals(9000L, c.getLong(3))
            assertEquals("29ABCDE1234F1Z5", c.getString(4))
        }

        // The migrated columns must match what Room itself creates for the entity (a mismatch crashes the app on open).
        val fresh = this.db.openHelper.writableDatabase
        listOf("supplierDocumentNumber", "supplierDocumentDate").forEach { col ->
            val migrated = columnInfo(old, col)
            assertEquals("column $col must exist after the migration", 3, migrated.size)
            assertEquals("migrated $col must equal Room's own definition", columnInfo(fresh, col), migrated)
            assertEquals(listOf("TEXT", "0", "null"), migrated)
        }
        old.close()
    }

    @Test
    fun appDatabaseVersion_andMigrationRegistry_includeTheNewStep() {
        assertEquals(29, AppDatabase.ALL_MIGRATIONS.size)
        val last = AppDatabase.ALL_MIGRATIONS.last()
        assertEquals(29, last.startVersion)
        assertEquals(30, last.endVersion)
        assertFalse(AppDatabase.ALL_MIGRATIONS.any { it.startVersion == it.endVersion })
    }
}
