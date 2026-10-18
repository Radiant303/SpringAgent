"""用量记录保存模型展示名。"""

import sqlalchemy as sa
from alembic import op

revision = "0021"
down_revision = "0020"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("usage_records", sa.Column("model_name", sa.String(64), nullable=False, server_default=""))


def downgrade() -> None:
    op.drop_column("usage_records", "model_name")
