"""消息会话分页索引。

@author hanbing
@since 2026-11-09
"""

from alembic import op

revision = "0025"
down_revision = "0024"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_index("ix_messages_session_id", "messages", ["session_id", "id"])


def downgrade() -> None:
    op.drop_index("ix_messages_session_id", table_name="messages")
