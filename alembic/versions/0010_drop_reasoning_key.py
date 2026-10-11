"""model_definitions 删除 reasoning_key 列：该配置无任何消费方，属死字段。

Revision ID: 0010
Revises: 0009
Create Date: 2026-10-11
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0010"
down_revision: str | None = "0009"
branch_labels: str | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.drop_column("model_definitions", "reasoning_key")


def downgrade() -> None:
    op.add_column(
        "model_definitions",
        sa.Column("reasoning_key", sa.String(length=64), nullable=True),
    )
