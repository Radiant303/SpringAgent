"""保留删除项目后的用量历史。"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0013"
down_revision: str | None = "0012"
branch_labels: str | None = None
depends_on: Sequence[str] | None = None


def _usage_session_foreign_key() -> dict | None:
    for foreign_key in sa.inspect(op.get_bind()).get_foreign_keys("usage_records"):
        if (foreign_key.get("constrained_columns") == ["session_id"]
                and foreign_key.get("referred_table") == "sessions"
                and foreign_key.get("referred_columns") == ["id"]):
            return foreign_key
    return None


def upgrade() -> None:
    foreign_key = _usage_session_foreign_key()
    if foreign_key is None:
        return
    with op.batch_alter_table("usage_records") as batch:
        batch.drop_constraint(foreign_key["name"], type_="foreignkey")


def downgrade() -> None:
    connection = op.get_bind()
    orphaned = connection.execute(sa.text(
        "SELECT 1 FROM usage_records u "
        "LEFT JOIN sessions s ON s.id = u.session_id "
        "WHERE s.id IS NULL LIMIT 1"
    )).first()
    if orphaned is not None:
        raise RuntimeError("存在已删除会话的用量历史，无法恢复外键")
    if _usage_session_foreign_key() is not None:
        return
    with op.batch_alter_table("usage_records") as batch:
        batch.create_foreign_key(
            "usage_records_ibfk_1", "sessions", ["session_id"], ["id"])
