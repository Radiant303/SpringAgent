"""统一模型配置 ID 作为计费业务主键。

Revision ID: 0011
Revises: 0010
Create Date: 2026-10-13
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op
from spring_harness.cloud.db import MicrosecondDateTime

revision: str = "0011"
down_revision: str | None = "0010"
branch_labels: str | None = None
depends_on: Sequence[str] | None = None

_TIMESTAMP_DEFAULT = sa.text("CURRENT_TIMESTAMP(6)")


def upgrade() -> None:
    op.drop_table("models")
    op.create_table(
        "models",
        sa.Column("model_id", sa.String(length=256), nullable=False),
        sa.Column("input_points", sa.DECIMAL(20, 6), nullable=False),
        sa.Column("cache_read_points", sa.DECIMAL(20, 6), nullable=False),
        sa.Column("cache_write_points", sa.DECIMAL(20, 6), nullable=False),
        sa.Column("output_points", sa.DECIMAL(20, 6), nullable=False),
        sa.Column("enabled", sa.Boolean(), nullable=False, server_default=sa.text("1")),
        sa.Column("created_at", MicrosecondDateTime(), nullable=False, server_default=_TIMESTAMP_DEFAULT),
        sa.Column("updated_at", MicrosecondDateTime(), nullable=False, server_default=_TIMESTAMP_DEFAULT),
        sa.PrimaryKeyConstraint("model_id"),
    )
    op.execute(
        "INSERT INTO models (model_id, input_points, cache_read_points, cache_write_points,"
        " output_points, enabled) VALUES ('__default__', 10, 1, 10, 30, 1)"
    )
    with op.batch_alter_table("usage_records") as batch:
        batch.drop_column("model_name")
        batch.add_column(sa.Column("model_id", sa.String(length=256), nullable=True))
    with op.batch_alter_table("points_ledger") as batch:
        batch.drop_column("model_name")
        batch.add_column(sa.Column("model_id", sa.String(length=256), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table("points_ledger") as batch:
        batch.drop_column("model_id")
        batch.add_column(sa.Column("model_name", sa.String(length=128), nullable=True))
    with op.batch_alter_table("usage_records") as batch:
        batch.drop_column("model_id")
        batch.add_column(sa.Column("model_name", sa.String(length=128), nullable=True))
    op.drop_table("models")
    op.create_table(
        "models",
        sa.Column("id", sa.BigInteger(), autoincrement=False, nullable=False),
        sa.Column("model_name", sa.String(length=128), nullable=False),
        sa.Column("input_points", sa.DECIMAL(20, 6), nullable=False),
        sa.Column("cache_read_points", sa.DECIMAL(20, 6), nullable=False),
        sa.Column("cache_write_points", sa.DECIMAL(20, 6), nullable=False),
        sa.Column("output_points", sa.DECIMAL(20, 6), nullable=False),
        sa.Column("enabled", sa.Boolean(), nullable=False, server_default=sa.text("1")),
        sa.Column("created_at", MicrosecondDateTime(), nullable=False, server_default=_TIMESTAMP_DEFAULT),
        sa.Column("updated_at", MicrosecondDateTime(), nullable=False, server_default=_TIMESTAMP_DEFAULT),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("model_name"),
    )
    op.execute(
        "INSERT INTO models (id, model_name, input_points, cache_read_points, cache_write_points,"
        " output_points, enabled) VALUES (1, 'default', 10, 1, 10, 30, 1)"
    )
