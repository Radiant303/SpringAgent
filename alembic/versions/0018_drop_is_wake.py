"""删除后台任务催醒轮的 is_wake 列。"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0018"
down_revision: str | None = "0017"
branch_labels: str | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    with op.batch_alter_table("points_holds") as batch:
        batch.drop_column("is_wake")
    with op.batch_alter_table("usage_records") as batch:
        batch.drop_column("is_wake")


def downgrade() -> None:
    with op.batch_alter_table("points_holds") as batch:
        batch.add_column(sa.Column("is_wake", sa.Boolean(), nullable=False, server_default=sa.false()))
    with op.batch_alter_table("usage_records") as batch:
        batch.add_column(sa.Column("is_wake", sa.Boolean(), nullable=False, server_default=sa.false()))
