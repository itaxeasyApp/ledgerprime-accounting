"""
Structured error contract (Phase 6, Priority 6.11) - one exception hierarchy mapping 1:1 to the
codes the user specified, so the Android side never has to parse a free-text message to decide
what happened. Every route handler that can fail raises one of these; a single FastAPI exception
handler (registered in main.py) turns it into `{"code": "...", "message": "..."}` with the right
HTTP status.
"""
from __future__ import annotations


class AppError(Exception):
    code: str = "VALIDATION_ERROR"
    status_code: int = 400

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class TenantMismatch(AppError):
    code = "TENANT_MISMATCH"
    status_code = 403

    def __init__(self, message: str = "This request's company does not belong to the authenticated user."):
        super().__init__(message)


class Forbidden(AppError):
    code = "FORBIDDEN"
    status_code = 403


class AuthenticationRequired(AppError):
    code = "AUTHENTICATION_REQUIRED"
    status_code = 401

    def __init__(self, message: str = "A valid access token is required."):
        super().__init__(message)


class PeriodLocked(AppError):
    code = "PERIOD_LOCKED"
    status_code = 409


class FinancialYearMismatch(AppError):
    code = "FINANCIAL_YEAR_MISMATCH"
    status_code = 400


class DoubleEntryNotBalanced(AppError):
    code = "DOUBLE_ENTRY_NOT_BALANCED"
    status_code = 400


class DuplicateVoucher(AppError):
    code = "DUPLICATE_VOUCHER"
    status_code = 409


class DuplicateIdempotencyKey(AppError):
    """Not actually an error path in practice - the idempotency service intercepts a replay and
    returns the stored prior result instead of re-raising. Kept as a named type for symmetry with
    the user's 6.11 list and for any caller that wants to distinguish "replay" from "new"."""
    code = "DUPLICATE_IDEMPOTENCY_KEY"
    status_code = 200


class OverAllocation(AppError):
    code = "OVER_ALLOCATION"
    status_code = 400


class InvalidGst(AppError):
    code = "INVALID_GST"
    status_code = 400


class InvalidContra(AppError):
    code = "INVALID_CONTRA"
    status_code = 400


class LedgerHasEntries(AppError):
    code = "LEDGER_HAS_ENTRIES"
    status_code = 409


class SystemLedgerProtected(AppError):
    code = "SYSTEM_LEDGER_PROTECTED"
    status_code = 403


class SuspenseProtected(AppError):
    code = "SUSPENSE_PROTECTED"
    status_code = 403


class RoundOffInvalid(AppError):
    code = "ROUND_OFF_INVALID"
    status_code = 400


class ValidationError(AppError):
    code = "VALIDATION_ERROR"
    status_code = 400


# ==================== Phase 7A: Party + Invoice Domain Foundation ====================
class PartyNotFound(AppError):
    code = "PARTY_NOT_FOUND"
    status_code = 404


class InvoiceNotFound(AppError):
    code = "INVOICE_NOT_FOUND"
    status_code = 404


class InvoiceAlreadyPosted(AppError):
    code = "INVOICE_ALREADY_POSTED"
    status_code = 409


# ==================== Phase 7B: Document/Voucher Lifecycle Architecture ====================
class TradeDocumentNotFound(AppError):
    code = "TRADE_DOCUMENT_NOT_FOUND"
    status_code = 404


class TradeDocumentAlreadyConverted(AppError):
    code = "TRADE_DOCUMENT_ALREADY_CONVERTED"
    status_code = 409


# ==================== Phase 7E: Export Architecture & Data Interchange ====================
class ExportFormatUnsupported(AppError):
    code = "EXPORT_FORMAT_UNSUPPORTED"
    status_code = 400


class ExportSchemaUnsupported(AppError):
    code = "EXPORT_SCHEMA_UNSUPPORTED"
    status_code = 400


class ResourceNotFound(AppError):
    code = "RESOURCE_NOT_FOUND"
    status_code = 404


class InvalidFinancialYear(AppError):
    code = "INVALID_FINANCIAL_YEAR"
    status_code = 400


# ==================== Phase 7J-B: Management Layer ====================
class SubscriptionAlreadyExists(AppError):
    """The `(company_id, financial_year_id)` unique-constraint violation case - a company already
    has a subscription row for this financial year; use the renew path instead of create."""
    code = "SUBSCRIPTION_ALREADY_EXISTS"
    status_code = 409


# ==================== Phone/OTP login (Week 1, Play Store update plan) ====================
class OtpRateLimited(AppError):
    """A request for a new OTP arrived while an unexpired, unconsumed one already exists for this
    phone - never send a second SMS within the same short window."""
    code = "OTP_RATE_LIMITED"
    status_code = 429


class OtpInvalidOrExpired(AppError):
    code = "OTP_INVALID_OR_EXPIRED"
    status_code = 400
