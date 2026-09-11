"""Phone/OTP login (Week 1, Play Store update plan). Purely additive/relaxing:

- `users.email` and `users.hashed_password` become nullable - an existing email/password user's
  row is completely unaffected (both already have real values); only a new phone/OTP-only user
  (created for the first time by this feature) will ever have either as NULL.
- `users.phone` (nullable, unique) is new - NULL for every pre-existing row.
- `otp_codes` is a new table - short-lived, hashed, attempt-limited OTP codes per phone number.

No existing row's email, password, or any other column is ever rewritten or reinterpreted.

Revision ID: 0008
Revises: 0007
Create Date: 2026-09-11

"""
from alembic import op
import sqlalchemy as sa

revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("users") as batch_op:
        batch_op.alter_column("email", existing_type=sa.String, nullable=True)
        batch_op.alter_column("hashed_password", existing_type=sa.String, nullable=True)
        batch_op.add_column(sa.Column("phone", sa.String, nullable=True))
        batch_op.create_unique_constraint("uq_users_phone", ["phone"])

    op.create_table(
        "otp_codes",
        sa.Column("otp_id", sa.String, primary_key=True),
        sa.Column("phone", sa.String, nullable=False),
        sa.Column("code_hash", sa.String, nullable=False),
        sa.Column("expires_at", sa.Integer, nullable=False),
        sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("consumed", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.Integer, nullable=False),
    )
    op.create_index("ix_otp_codes_phone", "otp_codes", ["phone"])


def downgrade() -> None:
    op.drop_index("ix_otp_codes_phone", table_name="otp_codes")
    op.drop_table("otp_codes")
    with op.batch_alter_table("users") as batch_op:
        batch_op.drop_constraint("uq_users_phone", type_="unique")
        batch_op.drop_column("phone")
        batch_op.alter_column("hashed_password", existing_type=sa.String, nullable=False)
        batch_op.alter_column("email", existing_type=sa.String, nullable=False)
