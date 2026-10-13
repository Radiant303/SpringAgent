"""模型分组用量记录。"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0012"
down_revision: str | None = "0011"
branch_labels: str | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    constraints = sa.inspect(op.get_bind()).get_unique_constraints("usage_records")
    op.execute("UPDATE usage_records SET model_id = '__default__' WHERE model_id IS NULL")
    with op.batch_alter_table("usage_records") as batch:
        for constraint in constraints:
            if constraint["column_names"] == ["turn_id"]:
                batch.drop_constraint(constraint["name"], type_="unique")
        batch.alter_column("model_id", existing_type=sa.String(256), nullable=False)
        batch.create_unique_constraint("uq_usage_turn_model", ["turn_id", "model_id"])


def downgrade() -> None:
    connection = op.get_bind()
    duplicates = connection.execute(sa.text(
        "SELECT turn_id FROM usage_records GROUP BY turn_id HAVING COUNT(*) > 1 LIMIT 1"
    )).first()
    if duplicates is not None:
        raise RuntimeError("同一轮存在多个模型用量，无法降级为单模型记录")
    with op.batch_alter_table("usage_records") as batch:
        batch.drop_constraint("uq_usage_turn_model", type_="unique")
        batch.alter_column("model_id", existing_type=sa.String(256), nullable=True)
        batch.create_unique_constraint("uq_usage_turn", ["turn_id"])
