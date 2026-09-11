"""
PostgreSQL schema, mirroring the Room schema table-for-table (Phase 6, Priority 6.21/docs/33) plus
server-only tables (users, user_company_roles, refresh_tokens, idempotency_keys). Column names
match the Android Entity field names (snake_case here, camelCase there) 1:1 so the mapping between
a `SyncEvent` and a row is mechanical, not a redesign of the shape.

Money is always integer paise (never a float) - the same authoritative-precision rule the Android
side follows. Dates are ISO-8601 strings (`YYYY-MM-DD`), matching how Room stores them.
"""
from __future__ import annotations

from sqlalchemy import Boolean, Float, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.infrastructure.database.base import Base


class User(Base):
    __tablename__ = "users"

    user_id: Mapped[str] = mapped_column(String, primary_key=True)
    # Phone/OTP login (Week 1) - nullable because a pre-existing email/password user has none;
    # unique but not `index=True` here (the migration adds its own explicit index - see the
    # run-server skill's documented 0005 duplicate-index gotcha for why the two must never both
    # declare the same index).
    email: Mapped[str | None] = mapped_column(String, unique=True, index=True, nullable=True)
    phone: Mapped[str | None] = mapped_column(String, unique=True, nullable=True)
    # Nullable: a phone/OTP-only user (Week 1) never sets a password at all.
    hashed_password: Mapped[str | None] = mapped_column(String, nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[int] = mapped_column(Integer)


class OtpCode(Base):
    """Phone/OTP login (Week 1, Play Store update plan) - a short-lived, hashed, attempt-limited
    code per phone number. Never stores the plaintext code (same bcrypt hashing as passwords, via
    the same hash_password/verify_password helpers - one hashing scheme, not two)."""
    __tablename__ = "otp_codes"

    otp_id: Mapped[str] = mapped_column(String, primary_key=True)
    phone: Mapped[str] = mapped_column(String, index=True)
    code_hash: Mapped[str] = mapped_column(String)
    expires_at: Mapped[int] = mapped_column(Integer)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    consumed: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[int] = mapped_column(Integer)


class UserCompanyRole(Base):
    __tablename__ = "user_company_roles"
    __table_args__ = (UniqueConstraint("user_id", "company_id", name="uq_user_company"),)

    id: Mapped[str] = mapped_column(String, primary_key=True)
    user_id: Mapped[str] = mapped_column(String, ForeignKey("users.user_id"), index=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    role: Mapped[str] = mapped_column(String)  # OWNER / ADMIN / ACCOUNTANT / VIEWER


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    user_id: Mapped[str] = mapped_column(String, ForeignKey("users.user_id"), index=True)
    token_hash: Mapped[str] = mapped_column(String, unique=True, index=True)
    expires_at: Mapped[int] = mapped_column(Integer)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[int] = mapped_column(Integer)


class IdempotencyKeyRecord(Base):
    """Server-side counterpart to `OutboxSyncEntity.idempotencyKey`'s unique index on Android
    (Phase 6, Priority 6.5). A repeat key for the same company returns the stored response instead
    of reprocessing - checked before any command handler runs."""
    __tablename__ = "idempotency_keys"
    __table_args__ = (UniqueConstraint("company_id", "idempotency_key", name="uq_company_idempotency_key"),)

    id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, index=True)
    idempotency_key: Mapped[str] = mapped_column(String, index=True)
    response_json: Mapped[str] = mapped_column(String)
    created_at: Mapped[int] = mapped_column(Integer)


class Company(Base):
    __tablename__ = "companies"

    company_id: Mapped[str] = mapped_column(String, primary_key=True)
    name: Mapped[str] = mapped_column(String)
    trade_name: Mapped[str] = mapped_column(String, default="")
    gstin: Mapped[str] = mapped_column(String, default="")
    pan: Mapped[str] = mapped_column(String, default="")
    state_code: Mapped[str] = mapped_column(String, default="")
    state_name: Mapped[str] = mapped_column(String, default="")
    email: Mapped[str] = mapped_column(String, default="")
    phone: Mapped[str] = mapped_column(String, default="")
    address: Mapped[str] = mapped_column(String, default="")
    currency: Mapped[str] = mapped_column(String, default="INR")
    financial_year_start_month: Mapped[int] = mapped_column(Integer, default=4)
    accounting_mode: Mapped[str] = mapped_column(String, default="ACCOUNT_ONLY")
    business_type: Mapped[str] = mapped_column(String, default="TRADING")
    is_default: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[int] = mapped_column(Integer)


class FinancialYear(Base):
    __tablename__ = "financial_years"

    financial_year_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    fy_code: Mapped[str] = mapped_column(String)
    start_date: Mapped[str] = mapped_column(String)
    end_date: Mapped[str] = mapped_column(String)
    is_current: Mapped[bool] = mapped_column(Boolean, default=False)
    is_locked: Mapped[bool] = mapped_column(Boolean, default=False)
    locked_at: Mapped[int | None] = mapped_column(Integer, nullable=True)
    locked_by: Mapped[str | None] = mapped_column(String, nullable=True)


class AccountingPeriod(Base):
    __tablename__ = "accounting_periods"

    period_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    financial_year_id: Mapped[str] = mapped_column(String, ForeignKey("financial_years.financial_year_id"), index=True)
    name: Mapped[str] = mapped_column(String)
    start_date: Mapped[str] = mapped_column(String)
    end_date: Mapped[str] = mapped_column(String)
    status: Mapped[str] = mapped_column(String, default="OPEN")
    locked_at: Mapped[int | None] = mapped_column(Integer, nullable=True)
    locked_by: Mapped[str | None] = mapped_column(String, nullable=True)


class Group(Base):
    __tablename__ = "account_groups"

    group_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    name: Mapped[str] = mapped_column(String)
    primary_group: Mapped[str] = mapped_column(String)
    parent_group_id: Mapped[str | None] = mapped_column(String, nullable=True)
    is_system: Mapped[bool] = mapped_column(Boolean, default=False)
    affects_gross_profit: Mapped[bool] = mapped_column(Boolean, default=False)
    display_order: Mapped[int] = mapped_column(Integer, default=0)


class Ledger(Base):
    __tablename__ = "ledgers"

    ledger_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    group_id: Mapped[str] = mapped_column(String, ForeignKey("account_groups.group_id"), index=True)
    name: Mapped[str] = mapped_column(String)
    code: Mapped[str] = mapped_column(String, default="")
    opening_balance_paise: Mapped[int] = mapped_column(Integer, default=0)
    opening_balance_type: Mapped[str] = mapped_column(String, default="DEBIT")
    current_balance_paise: Mapped[int] = mapped_column(Integer, default=0)
    current_balance_type: Mapped[str] = mapped_column(String, default="DEBIT")
    gstin: Mapped[str] = mapped_column(String, default="")
    pan: Mapped[str] = mapped_column(String, default="")
    state_code: Mapped[str] = mapped_column(String, default="")
    email: Mapped[str] = mapped_column(String, default="")
    phone: Mapped[str] = mapped_column(String, default="")
    address: Mapped[str] = mapped_column(String, default="")
    bank_account_number: Mapped[str] = mapped_column(String, default="")
    bank_ifsc: Mapped[str] = mapped_column(String, default="")
    is_system: Mapped[bool] = mapped_column(Boolean, default=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    hsn_sac_code: Mapped[str] = mapped_column(String, default="")
    default_tax_rate: Mapped[float] = mapped_column(Float, default=0.0)


class Voucher(Base):
    __tablename__ = "vouchers"
    __table_args__ = (UniqueConstraint("company_id", "financial_year_id", "voucher_number", name="uq_voucher_number"),)

    voucher_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    financial_year_id: Mapped[str] = mapped_column(String, ForeignKey("financial_years.financial_year_id"), index=True)
    voucher_number: Mapped[str] = mapped_column(String)
    voucher_type: Mapped[str] = mapped_column(String)
    date: Mapped[str] = mapped_column(String)
    reference_number: Mapped[str] = mapped_column(String, default="")
    narration: Mapped[str] = mapped_column(String, default="")
    total_amount_paise: Mapped[int] = mapped_column(Integer, default=0)
    is_posted: Mapped[bool] = mapped_column(Boolean, default=True)
    is_cancelled: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[int] = mapped_column(Integer)
    created_by: Mapped[str] = mapped_column(String, default="")
    party_gstin: Mapped[str] = mapped_column(String, default="")
    is_gst_applicable: Mapped[bool] = mapped_column(Boolean, default=False)
    reference_voucher_id: Mapped[str | None] = mapped_column(String, nullable=True)
    payment_mode: Mapped[str] = mapped_column(String, default="")


class JournalItem(Base):
    __tablename__ = "journal_items"

    item_id: Mapped[str] = mapped_column(String, primary_key=True)
    voucher_id: Mapped[str] = mapped_column(String, ForeignKey("vouchers.voucher_id"), index=True)
    company_id: Mapped[str] = mapped_column(String, index=True)
    financial_year_id: Mapped[str] = mapped_column(String)
    ledger_id: Mapped[str] = mapped_column(String, ForeignKey("ledgers.ledger_id"), index=True)
    type: Mapped[str] = mapped_column(String)  # DEBIT / CREDIT
    amount_paise: Mapped[int] = mapped_column(Integer)
    narration: Mapped[str] = mapped_column(String, default="")
    line_order: Mapped[int] = mapped_column(Integer, default=0)


class StockItem(Base):
    __tablename__ = "stock_items"

    item_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    name: Mapped[str] = mapped_column(String)
    sku: Mapped[str] = mapped_column(String, default="")
    hsn_code: Mapped[str] = mapped_column(String, default="")
    unit: Mapped[str] = mapped_column(String, default="Nos")
    gst_rate_percent: Mapped[float] = mapped_column(Float, default=0.0)
    opening_quantity: Mapped[int] = mapped_column(Integer, default=0)
    opening_rate_paise: Mapped[int] = mapped_column(Integer, default=0)
    current_quantity: Mapped[int] = mapped_column(Integer, default=0)
    standard_cost_paise: Mapped[int] = mapped_column(Integer, default=0)
    standard_selling_price_paise: Mapped[int] = mapped_column(Integer, default=0)
    current_avg_cost_paise: Mapped[int] = mapped_column(Integer, default=0)


class VoucherStockLine(Base):
    __tablename__ = "voucher_stock_lines"

    line_id: Mapped[str] = mapped_column(String, primary_key=True)
    voucher_id: Mapped[str] = mapped_column(String, ForeignKey("vouchers.voucher_id"), index=True)
    company_id: Mapped[str] = mapped_column(String, index=True)
    financial_year_id: Mapped[str] = mapped_column(String)
    item_id: Mapped[str] = mapped_column(String, ForeignKey("stock_items.item_id"), index=True)
    direction: Mapped[str] = mapped_column(String)  # IN / OUT
    quantity_raw: Mapped[int] = mapped_column(Integer)
    rate_paise: Mapped[int] = mapped_column(Integer)
    amount_paise: Mapped[int] = mapped_column(Integer)
    line_order: Mapped[int] = mapped_column(Integer, default=0)


class StockMovement(Base):
    __tablename__ = "stock_movements"

    movement_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, index=True)
    financial_year_id: Mapped[str] = mapped_column(String, index=True)
    item_id: Mapped[str] = mapped_column(String, ForeignKey("stock_items.item_id"), index=True)
    voucher_id: Mapped[str | None] = mapped_column(String, ForeignKey("vouchers.voucher_id"), nullable=True, index=True)
    date: Mapped[str] = mapped_column(String, index=True)
    direction: Mapped[str] = mapped_column(String)
    movement_type: Mapped[str] = mapped_column(String)
    quantity_raw: Mapped[int] = mapped_column(Integer)
    rate_paise: Mapped[int] = mapped_column(Integer)
    amount_paise: Mapped[int] = mapped_column(Integer)
    running_avg_cost_after_paise: Mapped[int] = mapped_column(Integer)
    reference: Mapped[str] = mapped_column(String, default="")
    narration: Mapped[str] = mapped_column(String, default="")
    created_at: Mapped[int] = mapped_column(Integer)
    created_by: Mapped[str] = mapped_column(String, default="")


class GstTransaction(Base):
    __tablename__ = "gst_transactions"

    gst_transaction_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, index=True)
    financial_year_id: Mapped[str] = mapped_column(String, index=True)
    # GST-only Sync Path: nullable (was NOT NULL) - a GST-only company's transaction has no
    # accounting Voucher at all. Mirrors the Android Room schema's identical relaxation. NULL
    # bypasses SQL foreign-key enforcement (a NULL child key is never checked against the parent),
    # so every existing row (always a real voucher_id) is unaffected.
    voucher_id: Mapped[str | None] = mapped_column(String, ForeignKey("vouchers.voucher_id"), nullable=True, index=True)
    voucher_type: Mapped[str] = mapped_column(String)
    party_ledger_id: Mapped[str] = mapped_column(String, index=True)
    party_gstin: Mapped[str] = mapped_column(String, default="")
    place_of_supply: Mapped[str] = mapped_column(String, default="")
    supply_type: Mapped[str] = mapped_column(String)
    item_id: Mapped[str | None] = mapped_column(String, nullable=True)
    hsn_sac_code: Mapped[str] = mapped_column(String, default="")
    quantity_raw: Mapped[int | None] = mapped_column(Integer, nullable=True)
    taxable_amount_paise: Mapped[int] = mapped_column(Integer)
    gst_rate_percent: Mapped[float] = mapped_column(Float)
    cgst_paise: Mapped[int] = mapped_column(Integer, default=0)
    sgst_paise: Mapped[int] = mapped_column(Integer, default=0)
    igst_paise: Mapped[int] = mapped_column(Integer, default=0)
    cess_paise: Mapped[int] = mapped_column(Integer, default=0)
    direction: Mapped[str] = mapped_column(String, index=True)  # OUTPUT / INPUT
    line_order: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[int] = mapped_column(Integer)
    # D1b (GST-Only Purchase + Sales/Purchase Return + GST Fact Hardening) - mirrors the Android
    # Room migration (18 -> 19) column-for-column; see migrations/versions/0007_gst_fact_hardening.py
    # for the exact backfill semantics of each.
    charge_type: Mapped[str] = mapped_column(String, default="FORWARD_CHARGE")
    supply_nature: Mapped[str] = mapped_column(String, default="NORMAL")
    transaction_group_id: Mapped[str] = mapped_column(String, default="", index=True)
    transaction_date: Mapped[str | None] = mapped_column(String, nullable=True)
    party_gst_registration_status: Mapped[str | None] = mapped_column(String, nullable=True)


class SettlementAllocation(Base):
    __tablename__ = "settlement_allocations"

    allocation_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, index=True)
    financial_year_id: Mapped[str] = mapped_column(String)
    settlement_voucher_id: Mapped[str] = mapped_column(String, ForeignKey("vouchers.voucher_id"), index=True)
    invoice_voucher_id: Mapped[str | None] = mapped_column(String, nullable=True, index=True)
    allocated_amount_paise: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[int] = mapped_column(Integer)


class GstFilingPeriod(Base):
    """Deliberately isolated (Phase 6, Priority 6.9 - never referenced by accounting-period or
    posting logic anywhere on the server, mirroring the Android-side isolation)."""
    __tablename__ = "gst_filing_periods"

    filing_period_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, index=True)
    period_label: Mapped[str] = mapped_column(String)
    start_date: Mapped[str] = mapped_column(String)
    end_date: Mapped[str] = mapped_column(String)
    is_locked: Mapped[bool] = mapped_column(Boolean, default=False)
    locked_at: Mapped[int | None] = mapped_column(Integer, nullable=True)
    locked_by: Mapped[str | None] = mapped_column(String, nullable=True)


class AuditLog(Base):
    __tablename__ = "audit_logs"

    log_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, index=True)
    financial_year_id: Mapped[str] = mapped_column(String, default="")
    action: Mapped[str] = mapped_column(String)
    entity_type: Mapped[str] = mapped_column(String)
    entity_id: Mapped[str] = mapped_column(String)
    description: Mapped[str] = mapped_column(String, default="")
    performed_by: Mapped[str] = mapped_column(String, default="")
    timestamp: Mapped[int] = mapped_column(Integer, index=True)
    payload_json: Mapped[str] = mapped_column(String, default="{}")


class Party(Base):
    """Phase 7A - a thin 1:1 extension of an existing Ledger row, never a replacement. GSTIN/PAN/
    address/bank fields deliberately stay on Ledger, not duplicated here."""
    __tablename__ = "parties"

    party_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    ledger_id: Mapped[str] = mapped_column(String, ForeignKey("ledgers.ledger_id"), unique=True, index=True)
    role: Mapped[str] = mapped_column(String)  # CUSTOMER / SUPPLIER
    entity_type: Mapped[str] = mapped_column(String)  # INDIVIDUAL / BUSINESS
    display_name: Mapped[str] = mapped_column(String)
    contact_name: Mapped[str] = mapped_column(String, default="")
    credit_limit_paise: Mapped[int | None] = mapped_column(Integer, nullable=True)
    payment_terms_type: Mapped[str] = mapped_column(String, default="DUE_ON_RECEIPT")
    payment_terms_custom_days: Mapped[int | None] = mapped_column(Integer, nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)


class Invoice(Base):
    """Phase 7A - a pre-posting document, separate from Voucher. `voucher_id` is null while DRAFT
    (zero accounting effect) and is set exactly once by a LINK_INVOICE_VOUCHER event once the
    linked Voucher is posted through the completely unmodified `apply_voucher_event`. Deliberately
    no declared foreign key on voucher_id/reference_invoice_id - same convention already used by
    Voucher.reference_voucher_id/SettlementAllocation.invoice_voucher_id (a plain indexed pointer,
    not an enforced FK)."""
    __tablename__ = "invoices"

    invoice_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    financial_year_id: Mapped[str] = mapped_column(String)
    invoice_type: Mapped[str] = mapped_column(String)
    invoice_number: Mapped[str | None] = mapped_column(String, nullable=True)
    party_id: Mapped[str] = mapped_column(String, ForeignKey("parties.party_id"), index=True)
    date: Mapped[str] = mapped_column(String)
    due_date: Mapped[str | None] = mapped_column(String, nullable=True)
    voucher_id: Mapped[str | None] = mapped_column(String, nullable=True, unique=True, index=True)
    reference_invoice_id: Mapped[str | None] = mapped_column(String, nullable=True, index=True)
    # Phase 7B - the TradeDocument this Invoice was converted from (Sales Order -> Sales Invoice,
    # etc). Null when created directly. No declared FK - same convention as reference_invoice_id.
    source_trade_document_id: Mapped[str | None] = mapped_column(String, nullable=True, index=True)
    narration: Mapped[str] = mapped_column(String, default="")
    created_at: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[int] = mapped_column(Integer)


class InvoiceLine(Base):
    """Phase 7A - the pre-posting working copy of what becomes real JournalItem/VoucherStockLine/
    GstTransaction rows once the parent Invoice is posted."""
    __tablename__ = "invoice_lines"

    line_id: Mapped[str] = mapped_column(String, primary_key=True)
    invoice_id: Mapped[str] = mapped_column(String, ForeignKey("invoices.invoice_id"), index=True)
    item_id: Mapped[str] = mapped_column(String)
    item_name: Mapped[str] = mapped_column(String)
    hsn_sac_code: Mapped[str] = mapped_column(String, default="")
    quantity_raw: Mapped[int] = mapped_column(Integer)
    rate_paise: Mapped[int] = mapped_column(Integer)
    gst_rate_percent: Mapped[float] = mapped_column(Float, default=0.0)
    cess_rate_percent: Mapped[float] = mapped_column(Float, default=0.0)
    line_order: Mapped[int] = mapped_column(Integer, default=0)


class TradeDocument(Base):
    """Phase 7B - a non-posting trade document (Quotation, Proforma Invoice, Sales/Purchase
    Order, Delivery Note, Receipt Note). Never creates any Ledger/JournalItem/Voucher row merely
    by existing; `status` is a genuine stored column here (unlike Invoice, whose status is always
    derived) since there is no accounting state to keep in sync against."""
    __tablename__ = "trade_documents"

    trade_document_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    financial_year_id: Mapped[str] = mapped_column(String)
    document_type: Mapped[str] = mapped_column(String)
    document_number: Mapped[str] = mapped_column(String)
    party_id: Mapped[str] = mapped_column(String, ForeignKey("parties.party_id"), index=True)
    date: Mapped[str] = mapped_column(String)
    status: Mapped[str] = mapped_column(String, default="DRAFT")
    source_trade_document_id: Mapped[str | None] = mapped_column(String, nullable=True, index=True)
    narration: Mapped[str] = mapped_column(String, default="")
    created_at: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[int] = mapped_column(Integer)


class TradeDocumentLine(Base):
    __tablename__ = "trade_document_lines"

    line_id: Mapped[str] = mapped_column(String, primary_key=True)
    trade_document_id: Mapped[str] = mapped_column(String, ForeignKey("trade_documents.trade_document_id"), index=True)
    item_id: Mapped[str] = mapped_column(String)
    item_name: Mapped[str] = mapped_column(String)
    hsn_sac_code: Mapped[str] = mapped_column(String, default="")
    quantity_raw: Mapped[int] = mapped_column(Integer)
    rate_paise: Mapped[int] = mapped_column(Integer)
    gst_rate_percent: Mapped[float] = mapped_column(Float, default=0.0)
    cess_rate_percent: Mapped[float] = mapped_column(Float, default=0.0)
    line_order: Mapped[int] = mapped_column(Integer, default=0)


class DocumentTemplate(Base):
    """Phase 7D - a company-scoped, versioned document template. `id` is `"{template_id}_v{version}"`;
    `template_id` groups every version of the same template lineage. Once inserted, a row's
    `config_json`/`template_name`/`is_default` are never mutated except the `status` flip from
    ACTIVE to ARCHIVED when superseded - this is what keeps a historical render reproducible under
    the exact version it used (see `RenderedDocumentRecord`)."""
    __tablename__ = "document_templates"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    template_id: Mapped[str] = mapped_column(String, index=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    document_type: Mapped[str] = mapped_column(String, index=True)
    template_name: Mapped[str] = mapped_column(String)
    version: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String, default="ACTIVE")
    is_default: Mapped[bool] = mapped_column(Boolean, default=False)
    config_json: Mapped[str] = mapped_column(String)
    created_at: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[int] = mapped_column(Integer)


class BusinessProfile(Base):
    """Phase 7D - document-branding identity, one per company, deliberately separate from
    Company's authoritative statutory fields (gstin/pan/etc. stay on Company; this row's own
    copies are independently editable rendering preferences, never a correction to Company)."""
    __tablename__ = "business_profiles"

    business_profile_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), unique=True, index=True)
    business_name: Mapped[str] = mapped_column(String)
    legal_name: Mapped[str] = mapped_column(String, default="")
    address: Mapped[str] = mapped_column(String, default="")
    phone: Mapped[str] = mapped_column(String, default="")
    email: Mapped[str] = mapped_column(String, default="")
    website: Mapped[str] = mapped_column(String, default="")
    gstin: Mapped[str] = mapped_column(String, default="")
    pan: Mapped[str] = mapped_column(String, default="")
    logo_asset_id: Mapped[str | None] = mapped_column(String, nullable=True)
    bank_name: Mapped[str] = mapped_column(String, default="")
    bank_account_number: Mapped[str] = mapped_column(String, default="")
    bank_ifsc: Mapped[str] = mapped_column(String, default="")
    bank_branch: Mapped[str] = mapped_column(String, default="")
    upi_id: Mapped[str] = mapped_column(String, default="")
    qr_code_asset_id: Mapped[str | None] = mapped_column(String, nullable=True)
    signature_asset_id: Mapped[str | None] = mapped_column(String, nullable=True)
    terms_and_conditions: Mapped[str] = mapped_column(String, default="")
    created_at: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[int] = mapped_column(Integer)


class IndividualProfile(Base):
    """Phase 7D - document identity for an individual/proprietor, one per company, structurally
    separate from BusinessProfile (Section 7 - never conflated with the capital-account naming
    rule, which stays independent of both)."""
    __tablename__ = "individual_profiles"

    individual_profile_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), unique=True, index=True)
    name: Mapped[str] = mapped_column(String)
    address: Mapped[str] = mapped_column(String, default="")
    pan: Mapped[str] = mapped_column(String, default="")
    phone: Mapped[str] = mapped_column(String, default="")
    email: Mapped[str] = mapped_column(String, default="")
    signature_asset_id: Mapped[str | None] = mapped_column(String, nullable=True)
    terms_and_conditions: Mapped[str] = mapped_column(String, default="")
    created_at: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[int] = mapped_column(Integer)


class DocumentAsset(Base):
    """Phase 7D - a reference to one binary branding asset (logo/signature/QR). The image bytes
    themselves are never stored here (Section 8) - only a storage reference plus enough metadata
    to detect a stale/corrupted reference."""
    __tablename__ = "document_assets"

    asset_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    type: Mapped[str] = mapped_column(String)
    storage_reference: Mapped[str] = mapped_column(String)
    checksum: Mapped[str] = mapped_column(String, default="")
    mime_type: Mapped[str] = mapped_column(String, default="")
    size_bytes: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[int] = mapped_column(Integer)


class CompanySubscription(Base):
    """Phase 7J-B - real persistence for the Android `domain.subscription.CompanySubscription`
    (Phase 7J domain model, previously unpersisted on either platform). One row per company per
    financial year (the unique constraint below enforces this), keyed by `financial_year_id` -
    never a raw date range - so paid validity is always derived from the referenced
    `FinancialYear`'s own `start_date`/`end_date`, never a hardcoded "1 Apr-31 Mar" literal anywhere
    in this schema or the service that reads it. `entitlements_csv` mirrors the Android entity's own
    plain comma-joined-string convention rather than a separate join table."""
    __tablename__ = "company_subscriptions"
    __table_args__ = (UniqueConstraint("company_id", "financial_year_id", name="uq_company_subscription_fy"),)

    subscription_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    financial_year_id: Mapped[str] = mapped_column(String, ForeignKey("financial_years.financial_year_id"), index=True)
    plan_type: Mapped[str] = mapped_column(String)  # FREE / PAID
    plan_name: Mapped[str] = mapped_column(String)
    entitlements_csv: Mapped[str] = mapped_column(String, default="")
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[int] = mapped_column(Integer)


class BankUpiProfile(Base):
    """Phase 7J-B - real persistence for the Android `domain.banking.BankUpiProfile` (Phase 7G
    domain model, previously unpersisted on either platform). Settlement/contact metadata,
    deliberately outside the double-entry stream - no Ledger/Voucher/JournalItem foreign key
    anywhere in this table. `party_id` is null for the company's own profile, non-null when scoped
    to one `Party`."""
    __tablename__ = "bank_upi_profiles"

    bank_upi_profile_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    party_id: Mapped[str | None] = mapped_column(String, ForeignKey("parties.party_id"), nullable=True, index=True)
    bank_name: Mapped[str] = mapped_column(String, default="")
    account_holder_name: Mapped[str] = mapped_column(String, default="")
    account_number: Mapped[str] = mapped_column(String, default="")
    ifsc_code: Mapped[str] = mapped_column(String, default="")
    branch_name: Mapped[str] = mapped_column(String, default="")
    upi_id: Mapped[str | None] = mapped_column(String, nullable=True)
    upi_payee_name: Mapped[str] = mapped_column(String, default="")
    upi_is_verified: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[int] = mapped_column(Integer)


class RenderedDocumentRecord(Base):
    """Phase 7D - logs which exact template version rendered a document, without adding any field
    to the frozen Invoice/TradeDocument tables - what makes 'an already-generated document must
    remain reproducible using the template/version associated with it' checkable after later
    template edits."""
    __tablename__ = "rendered_document_records"

    record_id: Mapped[str] = mapped_column(String, primary_key=True)
    company_id: Mapped[str] = mapped_column(String, ForeignKey("companies.company_id"), index=True)
    document_id: Mapped[str] = mapped_column(String, index=True)
    document_type: Mapped[str] = mapped_column(String)
    template_id: Mapped[str] = mapped_column(String)
    template_version: Mapped[int] = mapped_column(Integer)
    format: Mapped[str] = mapped_column(String)
    storage_reference: Mapped[str | None] = mapped_column(String, nullable=True)
    generated_at: Mapped[int] = mapped_column(Integer)
