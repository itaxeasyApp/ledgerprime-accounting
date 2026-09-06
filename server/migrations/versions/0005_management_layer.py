"""Phase 7J-B - Management Layer. Purely additive: two new tables (`company_subscriptions`,
`bank_upi_profiles`), neither of which alters, drops, or renames any existing column or row.
Neither table is read by `apply_voucher_event` or any GST/inventory/settlement command - this
migration cannot affect any accounting calculation.

Revision ID: 0005
Revises: 0004
Create Date: 2026-08-24

"""
from alembic import op
import sqlalchemy as sa

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "company_subscriptions",
        sa.Column("subscription_id", sa.String, primary_key=True),
        sa.Column("company_id", sa.String, sa.ForeignKey("companies.company_id"), nullable=False, index=True),
        sa.Column("financial_year_id", sa.String, sa.ForeignKey("financial_years.financial_year_id"), nullable=False, index=True),
        sa.Column("plan_type", sa.String, nullable=False),
        sa.Column("plan_name", sa.String, nullable=False),
        sa.Column("entitlements_csv", sa.String, nullable=False, server_default=""),
        sa.Column("is_active", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column("created_at", sa.Integer, nullable=False),
        sa.Column("updated_at", sa.Integer, nullable=False),
        sa.UniqueConstraint("company_id", "financial_year_id", name="uq_company_subscription_fy"),
    )

    op.create_table(
        "bank_upi_profiles",
        sa.Column("bank_upi_profile_id", sa.String, primary_key=True),
        sa.Column("company_id", sa.String, sa.ForeignKey("companies.company_id"), nullable=False, index=True),
        sa.Column("party_id", sa.String, sa.ForeignKey("parties.party_id"), nullable=True, index=True),
        sa.Column("bank_name", sa.String, nullable=False, server_default=""),
        sa.Column("account_holder_name", sa.String, nullable=False, server_default=""),
        sa.Column("account_number", sa.String, nullable=False, server_default=""),
        sa.Column("ifsc_code", sa.String, nullable=False, server_default=""),
        sa.Column("branch_name", sa.String, nullable=False, server_default=""),
        sa.Column("upi_id", sa.String, nullable=True),
        sa.Column("upi_payee_name", sa.String, nullable=False, server_default=""),
        sa.Column("upi_is_verified", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.Integer, nullable=False),
        sa.Column("updated_at", sa.Integer, nullable=False),
    )


def downgrade() -> None:
    op.drop_table("bank_upi_profiles")
    op.drop_table("company_subscriptions")
