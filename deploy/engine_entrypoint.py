"""SpringAgent 容器启动入口。

@author hanbing
@since 2026-10-18
"""

import os
import sys
from urllib.parse import quote


def main() -> None:
    required = (
        "MYSQL_USER", "MYSQL_PASSWORD", "RABBITMQ_PASSWORD",
        "SPRINGHARNESS_CLOUD_JWT_SECRET", "SPRINGHARNESS_CLOUD_INTERNAL_TOKEN",
    )
    for name in required:
        value = os.environ.get(name, "")
        if not value.strip():
            raise SystemExit(f"docker-compose.yml 中的 {name} 不能为空。")
    for name in required[-2:]:
        if len(os.environ[name].encode("utf-8")) < 32:
            raise SystemExit(f"{name} 必须至少为 32 字节。")
    username = quote(os.environ["MYSQL_USER"], safe="")
    password = quote(os.environ["MYSQL_PASSWORD"], safe="")
    os.environ.setdefault(
        "SPRINGHARNESS_CLOUD_DATABASE_URL",
        f"mysql+pymysql://{username}:{password}@mysql:3306/springharness?charset=utf8mb4",
    )
    if "RABBITMQ_PASSWORD" in os.environ:
        username = quote(os.environ.get("RABBITMQ_USER", "guest"), safe="")
        password = quote(os.environ["RABBITMQ_PASSWORD"], safe="")
        os.environ.setdefault(
            "SPRINGHARNESS_CLOUD_RABBITMQ_URL",
            f"amqp://{username}:{password}@rabbitmq:5672/",
        )
    os.execvp(sys.argv[1], sys.argv[1:])


if __name__ == "__main__":
    main()
