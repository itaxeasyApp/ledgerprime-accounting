package com.example.accounting

import com.example.accounting.automation.compliance.InvoiceReminderChecker
import com.example.accounting.automation.jobs.DailyUnreconciledCheckTask
import com.example.accounting.automation.jobs.MonthlyRecurringVoucherGenerationTask
import com.example.accounting.automation.jobs.YearlyClosingReminderTask
import com.example.accounting.automation.scheduler.AutomationRunGate
import com.example.accounting.automation.tasks.TaskExecutionStatus
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.InvoiceEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.RecurringVoucherDraftEntity
import com.example.accounting.data.local.entity.RecurringVoucherDraftLineEntity
import com.example.accounting.data.local.entity.RecurringVoucherLineEntity
import com.example.accounting.data.local.entity.RecurringVoucherScheduleEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.invoice.InvoiceType
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyEntityType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.recurring.RecurringFrequency
import com.example.accounting.domain.recurring.RecurringVoucherDraftStatus
import com.example.accounting.domain.recurring.RecurringVoucherGenerationOutcome
import com.example.accounting.domain.recurring.RecurringVoucherPeriod
import com.example.accounting.domain.recurring.RecurringVoucherSchedule
import com.example.accounting.domain.recurring.RecurringVoucherLine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Phase 7F (Automation Architecture: A - Invoice/Compliance Reminders, B - Recurring Voucher
 * Engine [draft-first], C - Real Scheduling Infrastructure, plus the two safety fixes) - pure JVM
 * tests.
 *
 * [Phase7FAwareDao] adds real in-memory backing for the four new recurring-voucher tables (the
 * base [FakeAccountingDao]'s own methods for them are permanent no-op stubs, added purely so the
 * interface compiles - matching every prior phase's decorator pattern, e.g.
 * [Phase7BTestSuite.Phase7BAwareDao]).
 *
 * [AccountingRepository.generateRecurringVoucherIfDue] NEVER calls [AccountingRepository.postVoucher]
 * - it only ever produces a review-only [com.example.accounting.domain.recurring.RecurringVoucherDraft]
 * row, so every outcome (`DraftGenerated`/`NotDue`/`AlreadyGenerated`/`ResourceNotFound`/no-lines-
 * `ValidationError`) is fully testable here with `db = null`. Only
 * [AccountingRepository.postRecurringVoucherDraft] - reachable exclusively from an explicit user
 * "Post" action, never from automation - calls `postVoucher`, which requires a real Room
 * `AppDatabase` (same constraint documented in [Phase7CTestSuite] and [Phase2AuditTestSuite]); that
 * path is covered separately in [Phase7FRecurringVoucherPostingTest] (Robolectric), which - like
 * the pre-existing [SuspenseControlArchitectureTest] - is currently blocked by this environment's
 * Robolectric SDK infrastructure, not by anything in the code under test.
 */
class Phase7FTestSuite {

    private class Phase7FAwareDao(delegate: AccountingDao) : AccountingDao by delegate {
        private val schedules = LinkedHashMap<String, RecurringVoucherScheduleEntity>()
        private val lines = mutableListOf<RecurringVoucherLineEntity>()
        private val drafts = LinkedHashMap<String, RecurringVoucherDraftEntity>()
        private val draftLines = mutableListOf<RecurringVoucherDraftLineEntity>()

        override fun getActiveRecurringVoucherSchedules(companyId: String) =
            flowOf(schedules.values.filter { it.companyId == companyId && it.isActive })

        override suspend fun getRecurringVoucherScheduleById(companyId: String, scheduleId: String) =
            schedules[scheduleId]?.takeIf { it.companyId == companyId }

        override suspend fun insertRecurringVoucherSchedule(schedule: RecurringVoucherScheduleEntity) {
            schedules[schedule.scheduleId] = schedule
        }

        override suspend fun getLinesForRecurringVoucherSchedule(scheduleId: String) =
            lines.filter { it.scheduleId == scheduleId }.sortedBy { it.lineOrder }

        override suspend fun insertRecurringVoucherLines(newLines: List<RecurringVoucherLineEntity>) {
            lines += newLines
        }

        override suspend fun getRecurringVoucherDraftForPeriod(scheduleId: String, periodKey: String) =
            drafts.values.firstOrNull { it.scheduleId == scheduleId && it.periodKey == periodKey }

        override fun getRecurringVoucherDraftsByStatus(companyId: String, status: RecurringVoucherDraftStatus) =
            flowOf(drafts.values.filter { it.companyId == companyId && it.status == status })

        override suspend fun getRecurringVoucherDraftById(companyId: String, draftId: String) =
            drafts[draftId]?.takeIf { it.companyId == companyId }

        override suspend fun insertRecurringVoucherDraft(draft: RecurringVoucherDraftEntity) {
            check(drafts.values.none { it.scheduleId == draft.scheduleId && it.periodKey == draft.periodKey }) {
                "Duplicate (scheduleId, periodKey) - simulates the unique DB index violation"
            }
            drafts[draft.draftId] = draft
        }

        override suspend fun updateRecurringVoucherDraft(draft: RecurringVoucherDraftEntity) {
            drafts[draft.draftId] = draft
        }

        override suspend fun getLinesForRecurringVoucherDraft(draftId: String) =
            draftLines.filter { it.draftId == draftId }.sortedBy { it.lineOrder }

        override suspend fun insertRecurringVoucherDraftLines(newLines: List<RecurringVoucherDraftLineEntity>) {
            draftLines += newLines
        }

        override suspend fun deleteLinesForRecurringVoucherDraft(draftId: String) {
            draftLines.removeAll { it.draftId == draftId }
        }
    }

    private val companyId = "COMP_P7F"
    private val fyId = "FY_P7F_2026_27"

    private fun freshDao() = Phase7FAwareDao(Phase7BTestSuite.Phase7BAwareDao(FakeAccountingDao()))

    private suspend fun AccountingDao.seed(): Map<String, String> {
        insertCompany(CompanyEntity(
            companyId = companyId, name = "Company", tradeName = "Company", gstin = "27AAAAA0000A1Z5",
            pan = "AAAAA0000A", stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
            currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
            accountingMode = AccountingMode.ACCOUNT_ONLY, businessType = BusinessType.TRADING
        ))
        insertFinancialYear(FinancialYearEntity(fyId, companyId, "FY 2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_1", companyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))

        val cashId = "LED_CASH"
        val rentId = "LED_RENT"
        val debtorsId = "LED_DEBTOR"
        val salesId = "LED_SALES"
        insertLedger(ledger(cashId, StandardSystemGroups.CASH_GROUP_ID))
        insertLedger(ledger(rentId, StandardSystemGroups.INDIRECT_EXPENSE_GROUP_ID))
        insertLedger(ledger(debtorsId, StandardSystemGroups.DEBTORS_GROUP_ID))
        insertLedger(ledger(salesId, StandardSystemGroups.SALES_GROUP_ID, DrCr.CREDIT))
        return mapOf("cash" to cashId, "rent" to rentId, "debtors" to debtorsId, "sales" to salesId)
    }

    private fun ledger(id: String, bareGroup: String, openingType: DrCr = DrCr.DEBIT) = LedgerEntity(
        id, companyId, "${bareGroup}_$companyId", id, "", 0L, openingType, 0L, openingType,
        "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0
    )

    private fun voucher(id: String, type: VoucherType, date: String, referenceNumber: String = "", isCancelled: Boolean = false) = VoucherEntity(
        voucherId = id, companyId = companyId, financialYearId = fyId, voucherNumber = id, voucherType = type,
        date = date, referenceNumber = referenceNumber, narration = "", totalAmountPaise = 1_000_00L,
        isPosted = true, isCancelled = isCancelled, syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L,
        createdBy = "TESTER", partyGstin = "", isGstApplicable = false
    )

    private fun invoiceEntity(invoiceId: String, partyId: String, voucherId: String, date: String, dueDate: String?) = InvoiceEntity(
        invoiceId = invoiceId, companyId = companyId, financialYearId = fyId, invoiceType = InvoiceType.SALES_INVOICE,
        invoiceNumber = "$invoiceId-NUM", partyId = partyId, date = date, dueDate = dueDate, voucherId = voucherId,
        referenceInvoiceId = null, sourceTradeDocumentId = null, narration = "", createdAt = 0L, updatedAt = 0L
    )

    private fun postedVoucher(dao: AccountingDao, id: String, date: String, debitLedgerId: String, creditLedgerId: String, amountPaise: Long) = runBlocking {
        val entity = voucher(id, VoucherType.SALES, date).copy(totalAmountPaise = amountPaise)
        val items = listOf(
            JournalItemEntity("$id-1", id, companyId, fyId, debitLedgerId, DrCr.DEBIT, amountPaise, "", 1),
            JournalItemEntity("$id-2", id, companyId, fyId, creditLedgerId, DrCr.CREDIT, amountPaise, "", 2)
        )
        VoucherPostingEngine.post(dao, entity, items, "IK_$id", "TESTER")
    }

    // ==========================================
    // RecurringVoucherPeriod (pure)
    // ==========================================
    @Test
    fun testPeriodKeyFor_monthlyAndYearly() {
        val date = LocalDate.of(2026, 6, 15)
        assertEquals("2026-06", RecurringVoucherPeriod.periodKeyFor(RecurringFrequency.MONTHLY, date))
        assertEquals("2026", RecurringVoucherPeriod.periodKeyFor(RecurringFrequency.YEARLY, date))
    }

    @Test
    fun testIsDue_inactiveSchedule_neverDue() {
        val schedule = schedule(dayOfMonth = 5, frequency = RecurringFrequency.MONTHLY, isActive = false)
        assertFalse(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 6, 10)))
    }

    @Test
    fun testIsDue_beforeStartDate_notDue() {
        val schedule = schedule(dayOfMonth = 5, frequency = RecurringFrequency.MONTHLY, startDate = LocalDate.of(2026, 7, 1))
        assertFalse(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 6, 10)))
    }

    @Test
    fun testIsDue_afterEndDate_notDue() {
        val schedule = schedule(dayOfMonth = 5, frequency = RecurringFrequency.MONTHLY, endDate = LocalDate.of(2026, 5, 31))
        assertFalse(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 6, 10)))
    }

    @Test
    fun testIsDue_monthly_dueOnOrAfterScheduledDay() {
        val schedule = schedule(dayOfMonth = 5, frequency = RecurringFrequency.MONTHLY)
        assertFalse(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 6, 4)))
        assertTrue(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 6, 5)))
        assertTrue(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 6, 30)))
    }

    @Test
    fun testIsDue_monthly_dayOfMonth31ClampsToShortMonth() {
        val schedule = schedule(dayOfMonth = 31, frequency = RecurringFrequency.MONTHLY)
        // February 2026 (non-leap) has 28 days - day 31 clamps to 28, never a spurious "never due".
        assertTrue(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 2, 28)))
        assertFalse(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 2, 27)))
    }

    @Test
    fun testIsDue_yearly_dueOnOrAfterAnniversary() {
        val schedule = schedule(dayOfMonth = 15, frequency = RecurringFrequency.YEARLY, startDate = LocalDate.of(2026, 6, 15))
        assertFalse(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 6, 14)))
        assertTrue(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 6, 15)))
        assertTrue(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 7, 1)))
        assertFalse(RecurringVoucherPeriod.isDue(schedule, LocalDate.of(2026, 5, 1)))
    }

    private fun schedule(
        dayOfMonth: Int,
        frequency: RecurringFrequency,
        isActive: Boolean = true,
        startDate: LocalDate = LocalDate.of(2026, 1, 1),
        endDate: LocalDate? = null
    ) = RecurringVoucherSchedule(
        scheduleId = "SCH_1", companyId = companyId, financialYearId = fyId, name = "Rent",
        voucherType = VoucherType.JOURNAL, frequency = frequency, dayOfMonth = dayOfMonth,
        startDate = startDate, endDate = endDate,
        lines = listOf(RecurringVoucherLine("LED_RENT", DrCr.DEBIT, 10_000_00L), RecurringVoucherLine("LED_CASH", DrCr.CREDIT, 10_000_00L)),
        isActive = isActive
    )

    // ==========================================
    // AutomationRunGate (pure, "C" - deterministic decision core)
    // ==========================================
    @Test
    fun testAutomationRunGate_dailyRunsOnceThenSkipsSameDay() {
        val today = LocalDate.of(2026, 6, 10)
        assertTrue(AutomationRunGate.decide(today, null, null, null).runDaily)
        assertFalse(AutomationRunGate.decide(today, today, null, null).runDaily)
        assertTrue(AutomationRunGate.decide(today, today.minusDays(1), null, null).runDaily)
    }

    @Test
    fun testAutomationRunGate_monthlyRunsOncePerCalendarMonth() {
        val today = LocalDate.of(2026, 6, 10)
        assertFalse(AutomationRunGate.decide(today, null, LocalDate.of(2026, 6, 1), null).runMonthly)
        assertTrue(AutomationRunGate.decide(today, null, LocalDate.of(2026, 5, 30), null).runMonthly)
        assertTrue(AutomationRunGate.decide(today, null, null, null).runMonthly)
    }

    @Test
    fun testAutomationRunGate_yearlyRunsOncePerCalendarYear() {
        val today = LocalDate.of(2026, 6, 10)
        assertFalse(AutomationRunGate.decide(today, null, null, LocalDate.of(2026, 1, 1)).runYearly)
        assertTrue(AutomationRunGate.decide(today, null, null, LocalDate.of(2025, 12, 31)).runYearly)
    }

    // ==========================================
    // Safety Item 1: FY close = MANUAL ONLY
    // ==========================================
    @Test
    fun testYearlyClosingReminderTask_hasNoAccountingRepositoryDependency() {
        // Structural guarantee: this task cannot call AccountingRepository.closeFinancialYear
        // because it never holds a reference to AccountingRepository at all - only to AccountingDao.
        val ctor = YearlyClosingReminderTask::class.java.constructors.single()
        assertFalse(
            "YearlyClosingReminderTask must not depend on AccountingRepository (Option X: automation can never reach closeFinancialYear)",
            ctor.parameterTypes.any { it.simpleName == "AccountingRepository" }
        )
    }

    @Test
    fun testYearlyClosingReminderTask_allPeriodsLocked_remindsButNeverCloses() = runBlocking {
        val dao = freshDao()
        dao.seed()
        dao.insertPeriods(listOf(AccountingPeriodEntity("PER_1", companyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.LOCKED, 0L, "TESTER")))
        val task = YearlyClosingReminderTask(dao)

        val result = task.execute(companyId, fyId)
        assertEquals(TaskExecutionStatus.SUCCESS, result.status)
        assertTrue(result.message.contains("ready to close", ignoreCase = true))
        // Not-yet-closed check: closeFinancialYear is never called from this codepath, so the FY
        // row this task reads its periods from is left entirely untouched by this call.
        val fy = dao.getFinancialYearById(fyId)
        assertFalse(fy!!.isLocked)
    }

    @Test
    fun testYearlyClosingReminderTask_notAllPeriodsLocked_reportsNotReady() = runBlocking {
        val dao = freshDao()
        dao.seed() // seeds an OPEN period
        val task = YearlyClosingReminderTask(dao)

        val result = task.execute(companyId, fyId)
        assertEquals(TaskExecutionStatus.SUCCESS, result.status)
        assertTrue(result.message.contains("not yet ready", ignoreCase = true))
    }

    @Test
    fun testYearlyClosingReminderTask_nullFinancialYear_skipped() = runBlocking {
        val dao = freshDao()
        val task = YearlyClosingReminderTask(dao)
        val result = task.execute(companyId, null)
        assertEquals(TaskExecutionStatus.SKIPPED, result.status)
    }

    // ==========================================
    // Safety Item 2: Bank reconciliation = QUARANTINED (mislabeling bug fix)
    // ==========================================
    @Test
    fun testDailyUnreconciledCheckTask_flagsMissingReferenceOnBankCashVouchersOnly() = runBlocking {
        val dao = freshDao()
        dao.seed()
        dao.insertVoucher(voucher("V_PMT_NOREF", VoucherType.PAYMENT, "2026-05-01", referenceNumber = ""))
        dao.insertVoucher(voucher("V_RCPT_OK", VoucherType.RECEIPT, "2026-05-02", referenceNumber = "REF-001"))
        dao.insertVoucher(voucher("V_CONTRA_NOREF", VoucherType.CONTRA, "2026-05-03", referenceNumber = ""))
        dao.insertVoucher(voucher("V_CANCELLED_NOREF", VoucherType.PAYMENT, "2026-05-04", referenceNumber = "", isCancelled = true))
        dao.insertVoucher(voucher("V_SALES_NOREF", VoucherType.SALES, "2026-05-05", referenceNumber = ""))
        val task = DailyUnreconciledCheckTask(dao)

        val result = task.execute(companyId, fyId)
        assertEquals(TaskExecutionStatus.WARNING, result.status)
        assertEquals(2, result.itemsProcessed)
        assertEquals(setOf("V_PMT_NOREF", "V_CONTRA_NOREF"), result.details.keys)
    }

    @Test
    fun testDailyUnreconciledCheckTask_allReferenced_success() = runBlocking {
        val dao = freshDao()
        dao.seed()
        dao.insertVoucher(voucher("V_PMT_OK", VoucherType.PAYMENT, "2026-05-01", referenceNumber = "REF-1"))
        val task = DailyUnreconciledCheckTask(dao)

        val result = task.execute(companyId, fyId)
        assertEquals(TaskExecutionStatus.SUCCESS, result.status)
        assertTrue(result.details.isEmpty())
    }

    // ==========================================
    // A: Invoice & Compliance Reminders
    // ==========================================
    @Test
    fun testInvoiceReminderChecker_overdueAndDueSoon_classifiedSeparately() = runBlocking {
        val dao = freshDao()
        val ledgers = dao.seed()
        val repo = AccountingRepository(dao)
        val customer = (repo.createParty(Party(partyId = "", companyId = companyId, ledgerId = ledgers.getValue("debtors"), role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Acme Corp")) as AccountingResult.Success).data
        val today = LocalDate.of(2026, 6, 15)

        postedVoucher(dao, "V_OVERDUE", "2026-05-01", ledgers.getValue("debtors"), ledgers.getValue("sales"), 5_000_00L)
        dao.insertInvoice(invoiceEntity("INV_OVERDUE", customer.partyId, "V_OVERDUE", "2026-05-01", today.minusDays(5).toString()))

        postedVoucher(dao, "V_DUESOON", "2026-06-10", ledgers.getValue("debtors"), ledgers.getValue("sales"), 2_000_00L)
        dao.insertInvoice(invoiceEntity("INV_DUESOON", customer.partyId, "V_DUESOON", "2026-06-10", today.plusDays(2).toString()))

        postedVoucher(dao, "V_NOTDUE", "2026-06-12", ledgers.getValue("debtors"), ledgers.getValue("sales"), 1_000_00L)
        dao.insertInvoice(invoiceEntity("INV_NOTDUE", customer.partyId, "V_NOTDUE", "2026-06-12", today.plusDays(30).toString()))

        val checker = InvoiceReminderChecker(repo)
        val result = checker.checkInvoiceReminders(companyId, today)

        assertEquals(TaskExecutionStatus.WARNING, result.status)
        assertEquals("1", result.details["overdueCount"])
        assertEquals("1", result.details["dueSoonCount"])
    }

    @Test
    fun testInvoiceReminderChecker_noOutstanding_success() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val checker = InvoiceReminderChecker(repo)

        val result = checker.checkInvoiceReminders(companyId, LocalDate.of(2026, 6, 15))
        assertEquals(TaskExecutionStatus.SUCCESS, result.status)
        assertEquals(0, result.itemsProcessed)
    }

    // ==========================================
    // B: Recurring Voucher Engine - persistence + non-posting outcomes
    // ==========================================
    @Test
    fun testCreateRecurringVoucherSchedule_persistsScheduleAndLines_thenListable() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)

        val schedule = schedule(dayOfMonth = 5, frequency = RecurringFrequency.MONTHLY)
        val created = repo.createRecurringVoucherSchedule(schedule)
        assertTrue(created is AccountingResult.Success)

        val listed = repo.getRecurringVoucherSchedules(companyId).first()
        assertEquals(1, listed.size)
        assertEquals("Rent", listed[0].name)
        assertEquals(2, listed[0].lines.size)
    }

    @Test
    fun testCreateRecurringVoucherSchedule_rejectsEmptyLines() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val result = repo.createRecurringVoucherSchedule(schedule(dayOfMonth = 5, frequency = RecurringFrequency.MONTHLY).copy(lines = emptyList()))
        assertTrue(result is AccountingResult.Failure)
    }

    @Test
    fun testGenerateRecurringVoucherIfDue_unknownSchedule_returnsResourceNotFound() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val result = repo.generateRecurringVoucherIfDue(companyId, "SCH_MISSING", LocalDate.of(2026, 6, 5))
        assertTrue(result is AccountingResult.Failure)
    }

    @Test
    fun testGenerateRecurringVoucherIfDue_notDue_returnsNotDue_withNoSideEffects() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        repo.createRecurringVoucherSchedule(schedule(dayOfMonth = 20, frequency = RecurringFrequency.MONTHLY))

        val result = repo.generateRecurringVoucherIfDue(companyId, "SCH_1", LocalDate.of(2026, 6, 5))
        val outcome = (result as AccountingResult.Success).data
        assertTrue(outcome is RecurringVoucherGenerationOutcome.NotDue)
        assertNull("A NotDue outcome must never write a draft row", dao.getRecurringVoucherDraftForPeriod("SCH_1", "2026-06"))
    }

    @Test
    fun testGenerateRecurringVoucherIfDue_alreadyGenerated_returnsAlreadyGenerated_regardlessOfDraftStatus() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao) // db = null - postVoucher would fail loudly if reached
        repo.createRecurringVoucherSchedule(schedule(dayOfMonth = 5, frequency = RecurringFrequency.MONTHLY))
        dao.insertRecurringVoucherDraft(
            RecurringVoucherDraftEntity(
                "DRAFT_1", companyId, "SCH_1", fyId, "2026-06", VoucherType.JOURNAL, "2026-06-05", "",
                RecurringVoucherDraftStatus.DISCARDED, null, 0L, 0L
            )
        )

        val result = repo.generateRecurringVoucherIfDue(companyId, "SCH_1", LocalDate.of(2026, 6, 10))
        val outcome = (result as AccountingResult.Success).data
        assertTrue(outcome is RecurringVoucherGenerationOutcome.AlreadyGenerated)
        assertEquals("2026-06", (outcome as RecurringVoucherGenerationOutcome.AlreadyGenerated).periodKey)
    }

    @Test
    fun testGenerateRecurringVoucherIfDue_dueSchedule_createsDraftOnly_zeroAccountingEffect() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao) // db = null - a call to postVoucher would fail loudly
        repo.createRecurringVoucherSchedule(schedule(dayOfMonth = 5, frequency = RecurringFrequency.MONTHLY))

        val result = repo.generateRecurringVoucherIfDue(companyId, "SCH_1", LocalDate.of(2026, 6, 5))
        val outcome = (result as AccountingResult.Success).data
        assertTrue(outcome is RecurringVoucherGenerationOutcome.DraftGenerated)
        val draftId = (outcome as RecurringVoucherGenerationOutcome.DraftGenerated).draftId

        val draft = dao.getRecurringVoucherDraftById(companyId, draftId)
        assertEquals(RecurringVoucherDraftStatus.PENDING_REVIEW, draft?.status)
        assertNull("A freshly generated draft must not reference any voucher yet", draft?.generatedVoucherId)
        // Structural guarantee, not just an assertion: no voucher table was ever touched.
        assertTrue("A generated draft must have zero journal/ledger effect", dao.getVouchersByCompany(companyId).first().isEmpty())
    }

    @Test
    fun testPostRecurringVoucherDraft_alreadyPosted_rejected() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        dao.insertRecurringVoucherDraft(
            RecurringVoucherDraftEntity(
                "DRAFT_POSTED", companyId, "SCH_1", fyId, "2026-06", VoucherType.JOURNAL, "2026-06-05", "",
                RecurringVoucherDraftStatus.POSTED, "V_ALREADY", 0L, 0L
            )
        )

        val result = repo.postRecurringVoucherDraft(companyId, "DRAFT_POSTED")
        assertTrue("Re-posting an already-posted draft must be rejected", result is AccountingResult.Failure)
    }

    @Test
    fun testDiscardRecurringVoucherDraft_pending_marksDiscarded_andRejectsSecondDiscard() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        repo.createRecurringVoucherSchedule(schedule(dayOfMonth = 5, frequency = RecurringFrequency.MONTHLY))
        val draftId = ((repo.generateRecurringVoucherIfDue(companyId, "SCH_1", LocalDate.of(2026, 6, 5)) as AccountingResult.Success).data
            as RecurringVoucherGenerationOutcome.DraftGenerated).draftId

        val discardResult = repo.discardRecurringVoucherDraft(companyId, draftId)
        assertTrue(discardResult is AccountingResult.Success)
        val discarded = dao.getRecurringVoucherDraftById(companyId, draftId)
        assertEquals(RecurringVoucherDraftStatus.DISCARDED, discarded?.status)

        val secondDiscard = repo.discardRecurringVoucherDraft(companyId, draftId)
        assertTrue("Discarding an already-discarded draft must be rejected", secondDiscard is AccountingResult.Failure)

        // A discarded period must never be re-proposed.
        val regenerate = repo.generateRecurringVoucherIfDue(companyId, "SCH_1", LocalDate.of(2026, 6, 20))
        val outcome = (regenerate as AccountingResult.Success).data
        assertTrue(outcome is RecurringVoucherGenerationOutcome.AlreadyGenerated)
    }

    @Test
    fun testUpdateRecurringVoucherDraft_pending_editsLines_rejectsOnPostedDraft() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        repo.createRecurringVoucherSchedule(schedule(dayOfMonth = 5, frequency = RecurringFrequency.MONTHLY))
        val draftId = ((repo.generateRecurringVoucherIfDue(companyId, "SCH_1", LocalDate.of(2026, 6, 5)) as AccountingResult.Success).data
            as RecurringVoucherGenerationOutcome.DraftGenerated).draftId
        val pendingDraft = repo.getRecurringVoucherDrafts(companyId).first().first { it.draftId == draftId }

        val edited = pendingDraft.copy(narration = "Edited rent narration")
        val updateResult = repo.updateRecurringVoucherDraft(companyId, edited)
        assertTrue(updateResult is AccountingResult.Success)
        assertEquals("Edited rent narration", (updateResult as AccountingResult.Success).data.narration)

        dao.updateRecurringVoucherDraft(dao.getRecurringVoucherDraftById(companyId, draftId)!!.copy(status = RecurringVoucherDraftStatus.POSTED))
        val editAfterPosted = repo.updateRecurringVoucherDraft(companyId, edited.copy(narration = "Too late"))
        assertTrue("Editing an already-posted draft must be rejected", editAfterPosted is AccountingResult.Failure)
    }

    @Test
    fun testMonthlyRecurringVoucherGenerationTask_noActiveSchedules_success() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao)
        val task = MonthlyRecurringVoucherGenerationTask(repo)

        val result = task.execute(companyId, fyId)
        assertEquals(TaskExecutionStatus.SUCCESS, result.status)
        assertEquals(0, result.itemsProcessed)
    }

    @Test
    fun testMonthlyRecurringVoucherGenerationTask_dueSchedule_createsDraft_neverPosts() = runBlocking {
        val dao = freshDao()
        dao.seed()
        val repo = AccountingRepository(dao) // db = null - a call to postVoucher would fail loudly
        repo.createRecurringVoucherSchedule(schedule(dayOfMonth = 1, frequency = RecurringFrequency.MONTHLY, startDate = LocalDate.of(2026, 1, 1)))
        val task = MonthlyRecurringVoucherGenerationTask(repo)

        val result = task.execute(companyId, fyId)
        assertEquals(TaskExecutionStatus.SUCCESS, result.status)
        assertEquals("1", result.details["draftedCount"])
        assertTrue(dao.getVouchersByCompany(companyId).first().isEmpty())
        assertEquals(1, repo.getRecurringVoucherDrafts(companyId).first().size)
    }
}
