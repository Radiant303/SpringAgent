"""page_mappings 表：页面访问 ID（雪花）→ work 的一对一映射。"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op
from spring_agent.cloud.db import MicrosecondDateTime

revision: str = "0017"
down_revision: str | None = "0016"
branch_labels: str | None = None
depends_on: Sequence[str] | None = None

_TIMESTAMP_DEFAULT = sa.text("CURRENT_TIMESTAMP(6)")  # DATETIME(6) 的默认值 fsp 必须与列一致


def upgrade() -> None:
    # URL 只暴露雪花 ID，磁盘路径不落库
    op.create_table(
        "page_mappings",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=False),
        sa.Column("work_id", sa.CHAR(36), sa.ForeignKey("works.id"), nullable=False, unique=True),
        sa.Column("created_at", MicrosecondDateTime(), nullable=False, server_default=_TIMESTAMP_DEFAULT),
    )


def downgrade() -> None:
    op.drop_table("page_mappings")
