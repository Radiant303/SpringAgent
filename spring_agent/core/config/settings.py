import os
import tomllib
from pathlib import Path
from typing import Any, Self

from pydantic import BaseModel, ConfigDict, Field

STATE_DIR = ".springagent"
CONFIG_PATH = Path(os.environ.get(
    "SPRINGAGENT_CONFIG", str(Path.home() / ".springagent" / "config.toml"),
))


def _load_toml_config(config_path: Path) -> dict[str, Any]:
    if not config_path.exists():
        raise FileNotFoundError(f"Configuration file not found: {config_path}")
    with config_path.open("rb") as f:
        return tomllib.load(f)


class ConfigBase(BaseModel):
    # 打错 key 直接报错，不静默忽略
    model_config = ConfigDict(extra="forbid")


class Provider(ConfigBase):
    type: str
    api_key: str
    base_url: str | None = None


class Model(ConfigBase):
    provider: str
    model: str
    max_context_size: int
    display_name: str
    max_output_size: int = 0
    capabilities: list[str] = []
    support_efforts: list[str] = []
    default_effort: str = ""


class LoopControl(ConfigBase):
    max_retries_per_step: int = 3
    reserved_context_size: int = 50000
    # 单次运行的模型请求上限
    request_limit: int = 120


class Log(ConfigBase):
    log_level: str = "INFO"


class Cloud(ConfigBase):
    database_url: str = "mysql+pymysql://root:root@127.0.0.1:18900/springagent?charset=utf8mb4"
    data_root: str = str(Path.home() / ".springagent" / "cloud")
    host: str = "0.0.0.0"
    port: int = 15900
    rabbitmq_url: str = "amqp://guest:guest@127.0.0.1:20900/"
    redis_url: str = "redis://127.0.0.1:19900/0"
    # 后台空闲会话的保留时长，单位为秒。
    session_idle_ttl_seconds: float = Field(default=600, gt=0, allow_inf_nan=False)
    # 可回收空闲会话的缓存数量上限。
    session_max_idle_handles: int = Field(default=128, ge=1)
    # 空闲会话的扫描间隔，单位为秒。
    session_sweep_interval_seconds: float = Field(default=30, gt=0, allow_inf_nan=False)
    gateway_base_url: str = "http://127.0.0.1:16900"
    internal_token: str = "springagent-internal-dev-token-0123456789"


class Config(ConfigBase):
    loop_control: LoopControl = LoopControl()
    log: Log = Log()
    cloud: Cloud = Cloud()

    @classmethod
    def from_toml(cls, config_path: Path | None = None) -> Self:
        data = _load_toml_config(config_path or CONFIG_PATH)
        cloud = data.setdefault("cloud", {})
        for field in Cloud.model_fields:
            name = f"SPRINGAGENT_CLOUD_{field.upper()}"
            if name in os.environ:
                cloud[field] = os.environ[name]
        return cls.model_validate({
            key: data[key] for key in ("loop_control", "log", "cloud") if key in data
        })


config = Config.from_toml()

loop_control = config.loop_control
log = config.log
