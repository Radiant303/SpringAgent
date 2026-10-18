"""模型结算失败审计。

@author hanbing
@since 2026-10-19
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import mysql

revision = "0023"
down_revision = "0022"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "search_billing_failures",
        sa.Column("failure_type", sa.String(32), nullable=False, server_default="SEARCH_BILLING"),
    )
    for name, length in (
        ("request_id", 64),
        ("model_id", 256),
        ("settlement_status", 16),
        ("delivery_status", 16),
    ):
        op.add_column("search_billing_failures", sa.Column(name, sa.String(length)))
    for name in ("first_failed_at", "last_failed_at", "completed_at"):
        op.add_column(
            "search_billing_failures",
            sa.Column(name, sa.DateTime().with_variant(mysql.DATETIME(fsp=6), "mysql")),
        )
    op.alter_column(
        "search_billing_failures", "max_retries", existing_type=sa.Integer(),
        existing_nullable=False, nullable=True, existing_server_default="3",
    )
    op.create_index(
        "ix_failure_type_request", "search_billing_failures", ["failure_type", "request_id"],
    )


def downgrade() -> None:
    raise RuntimeError("模型结算审计不可安全降级，请恢复升级前备份")
