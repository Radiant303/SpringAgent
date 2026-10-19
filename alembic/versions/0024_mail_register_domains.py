"""注册邮箱域名白名单。

@author hanbing
@since 2026-10-19
"""

import sqlalchemy as sa
from alembic import op

revision = "0024"
down_revision = "0023"
branch_labels = None
depends_on = None

_KEY = "mail.register_domains"


def upgrade() -> None:
    settings = sa.table(
        "system_settings",
        sa.column("setting_key", sa.String(64)),
        sa.column("setting_value", sa.String(1024)),
    )
    existing = op.get_bind().execute(
        sa.select(settings.c.setting_key).where(settings.c.setting_key == _KEY)
    ).scalar_one_or_none()
    if existing is None:
        op.bulk_insert(settings, [{"setting_key": _KEY, "setting_value": "[]"}])


def downgrade() -> None:
    raise RuntimeError("注册邮箱域名白名单不可安全降级，请恢复升级前备份")
