"""搜索服务配置及搜索用量。"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

from spring_agent.cloud.db import MicrosecondDateTime

revision: str = "0019"
down_revision: str | None = "0018"
branch_labels: str | None = None
depends_on: Sequence[str] | None = None

_DEFAULTS = {
    "search.local.enabled": "false",
    "search.local.provider": "tavily",
    "search.providers.tavily.api_key": "",
    "search.providers.tavily.points": "1",
    "search.providers.exa.api_key": "",
    "search.providers.exa.points": "1",
    "search.native.points": "1",
}


def upgrade() -> None:
    with op.batch_alter_table("system_settings") as batch:
        batch.alter_column("setting_value", existing_type=sa.String(255),
                           type_=sa.String(1024), existing_nullable=False)
    settings = sa.table("system_settings", sa.column("setting_key", sa.String(64)),
                        sa.column("setting_value", sa.String(1024)))
    existing = set(op.get_bind().execute(sa.select(settings.c.setting_key)).scalars())
    missing = [{"setting_key": key, "setting_value": value}
               for key, value in _DEFAULTS.items() if key not in existing]
    if missing:
        op.bulk_insert(settings, missing)
    op.create_table(
        "search_usage_records",
        sa.Column("event_id", sa.String(64), primary_key=True),
        sa.Column("source", sa.String(16), nullable=False),
        sa.Column("provider", sa.String(32), nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("session_id", sa.String(36), nullable=False),
        sa.Column("turn_id", sa.String(64), nullable=False),
        sa.Column("request_id", sa.String(64), nullable=True),
        sa.Column("agent_name", sa.String(64), nullable=True),
        sa.Column("searches", sa.BigInteger(), nullable=False),
        sa.Column("unit_points", sa.DECIMAL(20, 6), nullable=False),
        sa.Column("cost_points", sa.DECIMAL(20, 6), nullable=False),
        sa.Column("created_at", MicrosecondDateTime(), nullable=False,
                  server_default=sa.text("CURRENT_TIMESTAMP(6)")),
    )
    op.create_index("ix_search_usage_user_time", "search_usage_records", ["user_id", "created_at"])
    op.create_index("ix_search_usage_session", "search_usage_records", ["session_id"])


def downgrade() -> None:
    op.drop_table("search_usage_records")
    settings = sa.table("system_settings", sa.column("setting_key", sa.String(64)))
    op.execute(settings.delete().where(settings.c.setting_key.in_(list(_DEFAULTS))))
