package com.example.accounting.domain.sandbox

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.Money
import com.example.accounting.domain.rendering.BusinessProfile
import java.time.LocalDate

/**
 * The assessment year a Form 26AS statement is filed for (Phase 7H, Module 1) - deliberately its
 * own type, never [com.example.accounting.domain.financialyear.FinancialYear] and never
 * [com.example.accounting.domain.taxation.gst.GstFilingPeriod]. All three track genuinely
 * different calendars for genuinely different authorities:
 * - [com.example.accounting.domain.financialyear.FinancialYear] governs ledger/voucher posting
 *   (accounting-period locking).
 * - [com.example.accounting.domain.taxation.gst.GstFilingPeriod] governs GST return-filing
 *   tracking, already explicitly kept separate from accounting-period locking.
 * - [AssessmentYear] is an Income Tax Department concept - the year *following* the financial year
 *   the income was earned in (income earned in FY 2025-26 is assessed in AY 2026-27) - and applies
 *   only to Form 26AS/income-tax lookups, never to GST or ledger posting.
 *
 * [startYear] is the calendar year the assessment year begins in (e.g. `2026` for "AY 2026-27").
 */
data class AssessmentYear(val startYear: Int) {
    init {
        require(startYear in 2000..2100) { "Assessment year startYear looks invalid: $startYear" }
    }

    /** e.g. `"2026-27"` for `AssessmentYear(2026)`. */
    val label: String get() = "$startYear-${(startYear + 1).toString().takeLast(2)}"
}

/** A GSTIN's live registration status per the GST portal, as returned by Sandbox's GSTIN
 * verification service. [UNKNOWN] covers any provider-reported status this closed set doesn't
 * already name - never silently coerced into [ACTIVE]. */
enum class GstinStatus { ACTIVE, CANCELLED, SUSPENDED, UNKNOWN }

/** Result of verifying a GSTIN against the GST portal (via Sandbox) - read-only lookup data, never
 * written back to [com.example.accounting.domain.party.Party]/[com.example.accounting.domain.accounting.Ledger]
 * by this adapter itself; a caller decides what, if anything, to do with it. */
data class GstinVerificationResult(
    val gstin: String,
    val legalName: String,
    val tradeName: String,
    val status: GstinStatus,
    val stateJurisdiction: String,
    val registrationDate: LocalDate? = null
)

/** Whether a generated e-Invoice IRN is still valid or has been cancelled on the Invoice
 * Registration Portal (via Sandbox) - IRN cancellation is a government-side action; this enum only
 * records the status Sandbox reports, it does not itself cancel anything. */
enum class EInvoiceIrnStatus { ACTIVE, CANCELLED }

/**
 * Result of requesting an e-Invoice Reference Number (IRN) for an already-existing
 * [com.example.accounting.domain.invoice.Invoice] (referenced by [invoiceId], never duplicated
 * here as a second copy of invoice/line data). [signedQrCode] is the government-signed QR payload
 * a document renderer would print on the invoice; this adapter only fetches it, it never renders
 * or stores it anywhere itself.
 */
data class EInvoiceIrnResult(
    val invoiceId: String,
    val irn: String,
    val ackNumber: String,
    val ackDate: LocalDate,
    val signedQrCode: String,
    val status: EInvoiceIrnStatus
)

/** One TDS/TCS line item within a Form 26AS statement - deductor identity plus the amount paid and
 * tax deducted for that entry, both in exact paise via [Money] (never a floating-point rupee
 * amount, matching this project's monetary-precision rule everywhere else). */
data class Form26AsEntry(
    val deductorName: String,
    val deductorTan: String,
    val section: String,
    val amountPaid: Money,
    val taxDeducted: Money
)

/** A PAN's consolidated Form 26AS statement for one [AssessmentYear] - deliberately a simplified,
 * single flat list of [entries] rather than modeling the government form's full Part A/B/C/D
 * section structure, which is out of scope for this interface-definition step. */
data class Form26AsResult(
    val pan: String,
    val assessmentYear: AssessmentYear,
    val totalTaxDeducted: Money,
    val entries: List<Form26AsEntry>
)

/**
 * Adapter boundary for the Sandbox.co.in provider (Phase 7H, Module 1) - a pure Kotlin interface
 * only. **No implementation lives here**: no Retrofit/OkHttp/HTTP client, no Android dependency, no
 * JSON (de)serialization, and no call into `AccountingDao`/`AccountingRepository` - an
 * implementation of this interface is exactly where that would go, in a later module.
 *
 * Every operation is a **read/fetch from an external government-linked service**, never an
 * accounting mutation - nothing here creates, edits, or posts a [com.example.accounting.domain.accounting.Voucher],
 * changes a ledger balance, or writes GST/inventory facts. Government-tax-return submission and
 * any other write-side government API remain explicitly out of scope for this interface.
 *
 * [environment] is passed explicitly on every call ([SandboxEnvironment.TEST]/`LIVE`) - this
 * interface never hardcodes or stores a credential; an implementation resolves the correct
 * TEST/LIVE API key from [com.example.accounting.core.security.SecureStorage] per
 * [requestingCompany]'s `companyId`, never from a field on any type in this file.
 *
 * [requestingCompany] (a [BusinessProfile], not a bespoke `BusinessGstProfile` type) is the
 * tenant/company context every call carries - it is the existing, already-Phase-7D type that
 * already carries `companyId`, `gstin`, `pan`, `legalName`, and `businessName`, which is exactly
 * the seller/registrant identity Sandbox's GSTIN-verification, e-Invoice, and Form-26AS services
 * need; there is no separate context type to keep in sync with it.
 */
interface SandboxProviderAdapter {

    /** Verifies [gstin] against the GST portal (via Sandbox) for [requestingCompany]'s tenant
     * context. [gstin] is the GSTIN being looked up - a party's GSTIN before it's saved to a
     * [com.example.accounting.domain.party.Party], or [requestingCompany]'s own GSTIN for
     * self-verification - never assumed to equal `requestingCompany.gstin`. */
    suspend fun verifyGstin(
        requestingCompany: BusinessProfile,
        environment: SandboxEnvironment,
        gstin: String
    ): AccountingResult<GstinVerificationResult>

    /** Requests an e-Invoice IRN (via Sandbox) for the already-existing
     * [com.example.accounting.domain.invoice.Invoice] identified by [invoiceId], filed under
     * [requestingCompany]'s registered identity. An implementation is responsible for assembling
     * the actual e-Invoice payload from that Invoice's existing lines/GST facts - this interface
     * does not model or duplicate the e-Invoice JSON schema itself. */
    suspend fun requestEInvoiceIrn(
        requestingCompany: BusinessProfile,
        environment: SandboxEnvironment,
        invoiceId: String
    ): AccountingResult<EInvoiceIrnResult>

    /** Fetches the consolidated Form 26AS statement (via Sandbox) for [pan] and [assessmentYear] -
     * an [AssessmentYear], never a [com.example.accounting.domain.financialyear.FinancialYear] or
     * a [com.example.accounting.domain.taxation.gst.GstFilingPeriod]. [pan] is the PAN the
     * statement is being fetched for - not assumed to equal `requestingCompany.pan`, since a
     * proprietor's personal PAN and a company's PAN are legitimately different assessees. */
    suspend fun fetchForm26As(
        requestingCompany: BusinessProfile,
        environment: SandboxEnvironment,
        pan: String,
        assessmentYear: AssessmentYear
    ): AccountingResult<Form26AsResult>
}

/**
 * The honest default (same shape as [com.example.accounting.domain.taxation.gstreturn.UnconfiguredGstOnlineFilingGateway])
 * - there is no genuinely public, keyless GSTIN-lookup API (every real option, official GSP or
 * third-party aggregator, requires its own signup/API key), so this NEVER fabricates a legal
 * name/status for a GSTIN. It always reports the integration as unconfigured; a real
 * [SandboxProviderAdapter] implementation (Sandbox.co.in or an equivalent licensed GSP, configured
 * with THIS company's own API key via [com.example.accounting.core.security.SecureStorage] - never
 * a shared/hardcoded one) is a real, separate integration step, not a UI change.
 */
class UnconfiguredSandboxProviderAdapter : SandboxProviderAdapter {
    private val notConfigured = com.example.accounting.core.common.AccountingResult.Failure(
        com.example.accounting.core.common.AppError.ValidationError(
            "GSTIN lookup is not configured in this application. There is no free, keyless public API for this - " +
                "connect a licensed GST Suvidha Provider (e.g. Sandbox.co.in) with your own API key to enable it."
        )
    )

    override suspend fun verifyGstin(requestingCompany: BusinessProfile, environment: SandboxEnvironment, gstin: String): AccountingResult<GstinVerificationResult> = notConfigured
    override suspend fun requestEInvoiceIrn(requestingCompany: BusinessProfile, environment: SandboxEnvironment, invoiceId: String): AccountingResult<EInvoiceIrnResult> = notConfigured
    override suspend fun fetchForm26As(requestingCompany: BusinessProfile, environment: SandboxEnvironment, pan: String, assessmentYear: AssessmentYear): AccountingResult<Form26AsResult> = notConfigured
}
