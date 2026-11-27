"""消息分支与编辑重试配置。

@author hanbing
@since 2026-11-27
"""

import datetime

import sqlalchemy as sa

from alembic import op
from spring_agent.cloud.db import MicrosecondDateTime

revision = "0026"
down_revision = "0025"
branch_labels = None
depends_on = None

_DEFAULTS = {
    "message_edit_limit": "6",
    "answer_retry_limit": "6",
}


def upgrade() -> None:
    op.create_table(
        "message_nodes",
        sa.Column("id", sa.CHAR(36), nullable=False),
        sa.Column("session_id", sa.CHAR(36), nullable=False),
        sa.Column("parent_id", sa.CHAR(36), nullable=True),
        sa.Column("group_id", sa.CHAR(36), nullable=False),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("segment_no", sa.Integer(), nullable=False),
        sa.Column("legacy_anchor_id", sa.BigInteger(), nullable=True),
        sa.Column("legacy_end_id", sa.BigInteger(), nullable=True),
        sa.Column("legacy_target_id", sa.BigInteger(), nullable=True),
        sa.Column("created_at", MicrosecondDateTime(), nullable=False,
                  server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.ForeignKeyConstraint(["session_id"], ["sessions.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_message_nodes_session", "message_nodes", ["session_id", "created_at"])
    op.create_index("ix_message_nodes_group", "message_nodes", ["session_id", "group_id", "version"])
    op.create_index(
        "ix_message_nodes_legacy_target", "message_nodes", ["session_id", "legacy_target_id"],
    )
    op.add_column("sessions", sa.Column("active_node_id", sa.CHAR(36), nullable=True))
    with op.batch_alter_table("messages") as batch:
        batch.add_column(sa.Column("node_id", sa.CHAR(36), nullable=True))
        batch.add_column(sa.Column("legacy_id", sa.BigInteger(), nullable=True))
        batch.create_foreign_key("fk_messages_node", "message_nodes", ["node_id"], ["id"])
    op.create_index("ix_messages_session_node", "messages", ["session_id", "node_id", "id"])

    settings = sa.table(
        "system_settings",
        sa.column("setting_key", sa.String(64)),
        sa.column("setting_value", sa.String(1024)),
        sa.column("updated_at", MicrosecondDateTime()),
    )
    existing = set(op.get_bind().execute(sa.select(settings.c.setting_key)).scalars())
    missing = [
        {
            "setting_key": key,
            "setting_value": value,
            "updated_at": datetime.datetime.now(datetime.UTC).replace(tzinfo=None),
        }
        for key, value in _DEFAULTS.items() if key not in existing
    ]
    if missing:
        op.bulk_insert(settings, missing)


def downgrade() -> None:
    settings = sa.table("system_settings", sa.column("setting_key", sa.String(64)))
    op.execute(settings.delete().where(settings.c.setting_key.in_(list(_DEFAULTS))))
    op.drop_index("ix_messages_session_node", table_name="messages")
    with op.batch_alter_table("messages") as batch:
        batch.drop_constraint("fk_messages_node", type_="foreignkey")
        batch.drop_column("node_id")
        batch.drop_column("legacy_id")
    op.drop_column("sessions", "active_node_id")
    op.drop_index("ix_message_nodes_legacy_target", table_name="message_nodes")
    op.drop_index("ix_message_nodes_group", table_name="message_nodes")
    op.drop_index("ix_message_nodes_session", table_name="message_nodes")
    op.drop_table("message_nodes")
