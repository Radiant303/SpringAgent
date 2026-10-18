"""files 表去掉 uuid：雪花 ID 即文件名，主键查询免回表。旧数据不保留。"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op
from spring_agent.cloud.db import MicrosecondDateTime

revision: str = "0016"
down_revision: str | None = "0015"
branch_labels: str | None = None
depends_on: Sequence[str] | None = None

_TIMESTAMP_DEFAULT = sa.text("CURRENT_TIMESTAMP(6)")  # DATETIME(6) 的默认值 fsp 必须与列一致


def upgrade() -> None:
    op.drop_table("files")
    # 用户上传的文件；id 是雪花 ID，同时是对外扁平 URL 与存储 key 里的文件名
    op.create_table(
        "files",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=False),
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


def downgrade() -> None:
    op.drop_table("files")
    op.create_table(
        "files",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=False),
        sa.Column("uuid", sa.String(32), nullable=False, unique=True),
        sa.Column("user_id", sa.BigInteger(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("session_id", sa.String(36), nullable=False),
        sa.Column("storage_type", sa.String(32), nullable=False),
        sa.Column("path", sa.String(512), nullable=False),
        sa.Column("size", sa.BigInteger(), nullable=False),
        sa.Column("mime_type", sa.String(128), nullable=False),
        sa.Column("created_at", MicrosecondDateTime(), nullable=False, server_default=_TIMESTAMP_DEFAULT),
    )
    op.create_index("ix_files_user_id", "files", ["user_id"])
