"""文件工作区增量计量。"""

import sqlalchemy as sa
from alembic import op

revision = "0020"
down_revision = "0019"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("users", sa.Column("knowledge_bytes", sa.BigInteger(), nullable=False, server_default="0"))
    op.add_column("users", sa.Column("storage_initialized", sa.Boolean(), nullable=False, server_default=sa.false()))
    op.add_column("works", sa.Column("internal_bytes", sa.BigInteger(), nullable=False, server_default="0"))


def downgrade() -> None:
    op.drop_column("works", "internal_bytes")
    op.drop_column("users", "storage_initialized")
    op.drop_column("users", "knowledge_bytes")
