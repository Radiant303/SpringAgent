"""文件存储：files 表 + users.used_bytes。"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op
from spring_harness.cloud.db import MicrosecondDateTime

revision: str = "0015"
down_revision: str | None = "0014"
branch_labels: str | None = None
depends_on: Sequence[str] | None = None

_TIMESTAMP_DEFAULT = sa.text("CURRENT_TIMESTAMP(6)")  # DATETIME(6) 的默认值 fsp 必须与列一致


def upgrade() -> None:
    # 用户上传的文件。
    op.create_table(
        "files",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=False),  # 雪花 ID
        # uuid4 hex，无连字符
        sa.Column("uuid", sa.String(32), nullable=False, unique=True),
        sa.Column("user_id", sa.BigInteger(), sa.ForeignKey("users.id"), nullable=False),
        # 上传时所属会话；会话软删除后行保留
        sa.Column("session_id", sa.String(36), nullable=False),
        sa.Column("storage_type", sa.String(32), nullable=False),
        # 存储内地址：本地 = 相对 data-root 的 key；OSS = object key
        sa.Column("path", sa.String(512), nullable=False),
        sa.Column("size", sa.BigInteger(), nullable=False),
        sa.Column("mime_type", sa.String(128), nullable=False),
        sa.Column("created_at", MicrosecondDateTime(), nullable=False, server_default=_TIMESTAMP_DEFAULT),
    )
    op.create_index("ix_files_user_id", "files", ["user_id"])
    # 上传字节的实时计数
    op.add_column("users", sa.Column("used_bytes", sa.BigInteger(), nullable=False, server_default="0"))


def downgrade() -> None:
    op.drop_column("users", "used_bytes")
    op.drop_index("ix_files_user_id", "files")
    op.drop_table("files")
