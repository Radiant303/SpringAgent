"""model_definitions.id 加宽到 256：ID 改为服务端派生（provider + "/" + model），
provider(64) + 分隔符 + model(128) 最坏 193 字符，超出原 128 列宽。

Revision ID: 0009
Revises: 0008
Create Date: 2026-10-11
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0009"
down_revision: str | None = "0008"
branch_labels: str | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.alter_column("model_definitions", "id", type_=sa.String(length=256), nullable=False)


def downgrade() -> None:
    op.alter_column("model_definitions", "id", type_=sa.String(length=128), nullable=False)
