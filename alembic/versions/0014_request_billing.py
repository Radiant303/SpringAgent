"""单次模型请求计费。"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0014"
down_revision: str | None = "0013"
branch_labels: str | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    connection = op.get_bind()
    if connection.execute(sa.text("SELECT COUNT(*) FROM points_holds WHERE status = 'HELD'")).scalar():
        raise RuntimeError("存在未结算的轮次预扣，请先完成结算后迁移")
    with op.batch_alter_table("points_holds") as batch:
        batch.alter_column("turn_id", new_column_name="request_id", existing_type=sa.String(64), nullable=False)
        batch.add_column(sa.Column("turn_id", sa.String(64), nullable=True))
        batch.add_column(sa.Column("session_id", sa.String(36), nullable=True))
        batch.add_column(sa.Column("model_id", sa.String(256), nullable=True))
        batch.add_column(sa.Column("agent_name", sa.String(32), nullable=False, server_default="legacy"))
        batch.add_column(sa.Column("is_wake", sa.Boolean(), nullable=False, server_default=sa.false()))
        for name in ("input_points", "cache_read_points", "cache_write_points", "output_points"):
            batch.add_column(sa.Column(name, sa.DECIMAL(20, 6), nullable=False, server_default="0"))
    op.execute("UPDATE points_holds SET turn_id = request_id")
    with op.batch_alter_table("usage_records") as batch:
        batch.drop_constraint("uq_usage_turn_model", type_="unique")
        batch.add_column(sa.Column("request_id", sa.String(64), nullable=True))
        batch.add_column(sa.Column("agent_name", sa.String(32), nullable=False, server_default="legacy"))
        batch.add_column(sa.Column("usage_known", sa.Boolean(), nullable=False, server_default=sa.true()))
        batch.add_column(sa.Column("cost_points", sa.DECIMAL(20, 6), nullable=True))
    rows = connection.execute(sa.text("SELECT id FROM usage_records")).scalars().all()
    for row_id in rows:
        connection.execute(sa.text("UPDATE usage_records SET request_id = :request WHERE id = :id"),
                           {"request": f"legacy-{row_id}", "id": row_id})
    with op.batch_alter_table("usage_records") as batch:
        batch.alter_column("request_id", existing_type=sa.String(64), nullable=False)
        batch.alter_column("turn_id", existing_type=sa.String(32), type_=sa.String(64), nullable=False)
        batch.create_unique_constraint("uq_usage_request", ["request_id"])


def downgrade() -> None:
    raise RuntimeError("请求级计费不能降级为轮次计费")
