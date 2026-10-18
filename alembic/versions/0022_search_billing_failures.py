"""搜索计费失败事件。

@author hanbing
@since 2026-10-18
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import mysql

revision = "0022"
down_revision = "0021"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "search_billing_failures",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=False),
        sa.Column("dedup_key", sa.String(64), nullable=False),
        sa.Column("raw_body", sa.LargeBinary().with_variant(mysql.LONGBLOB(), "mysql"), nullable=False),
        sa.Column("headers", sa.Text().with_variant(mysql.LONGTEXT(), "mysql"), nullable=False),
        sa.Column("event_id", sa.String(64)),
        sa.Column("provider", sa.String(32)),
        sa.Column("user_id", sa.BigInteger()),
        sa.Column("session_id", sa.String(36)),
        sa.Column("turn_id", sa.String(64)),
        sa.Column("searches", sa.BigInteger()),
        sa.Column("unit_points", sa.DECIMAL(20, 6)),
        sa.Column("cost_points", sa.DECIMAL(20, 6)),
        sa.Column("status", sa.String(16), nullable=False, server_default="PENDING"),
        sa.Column("retry_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("max_retries", sa.Integer(), nullable=False, server_default="3"),
        sa.Column("next_retry_at", sa.DateTime().with_variant(mysql.DATETIME(fsp=6), "mysql")),
        sa.Column("lease_token", sa.String(36)),
        sa.Column("lease_until", sa.DateTime().with_variant(mysql.DATETIME(fsp=6), "mysql")),
        sa.Column("error_type", sa.String(256), nullable=False),
        sa.Column("error_message", sa.Text().with_variant(mysql.LONGTEXT(), "mysql"), nullable=False),
        sa.Column("created_at", sa.DateTime().with_variant(mysql.DATETIME(fsp=6), "mysql"), nullable=False),
        sa.Column("updated_at", sa.DateTime().with_variant(mysql.DATETIME(fsp=6), "mysql"), nullable=False),
        sa.Column("handled_by", sa.BigInteger()),
        sa.Column("handled_at", sa.DateTime().with_variant(mysql.DATETIME(fsp=6), "mysql")),
        sa.Column("remark", sa.Text().with_variant(mysql.LONGTEXT(), "mysql")),
        sa.UniqueConstraint("dedup_key", name="uq_search_failure_dedup"),
    )
    op.create_index("ix_search_failure_retry", "search_billing_failures", ["status", "next_retry_at", "lease_until"])
    op.create_index("ix_search_failure_user", "search_billing_failures", ["user_id", "id"])
    op.create_index("ix_search_failure_event", "search_billing_failures", ["event_id"])


def downgrade() -> None:
    op.drop_table("search_billing_failures")
