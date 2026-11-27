"""消息编辑版本与回答重试版本分组。

@author hanbing
@since 2026-11-27
"""

import sqlalchemy as sa

from alembic import op

revision = "0027"
down_revision = "0026"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("message_nodes") as batch:
        batch.add_column(sa.Column("answer_group_id", sa.CHAR(36), nullable=True))
    op.execute("UPDATE message_nodes SET answer_group_id = group_id WHERE answer_group_id IS NULL")
    with op.batch_alter_table("message_nodes") as batch:
        batch.alter_column("answer_group_id", existing_type=sa.CHAR(36), nullable=False)
    op.create_index(
        "ix_message_nodes_answer_group", "message_nodes",
        ["session_id", "answer_group_id", "created_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_message_nodes_answer_group", table_name="message_nodes")
    with op.batch_alter_table("message_nodes") as batch:
        batch.drop_column("answer_group_id")
