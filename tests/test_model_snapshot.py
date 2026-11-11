"""模型目录快照复用回归测试。"""

import os
from pathlib import Path

os.environ.setdefault(
    "SPRINGAGENT_CONFIG", str(Path(__file__).resolve().parents[1] / "config.toml.example"),
)

from spring_agent.core.config import model as model_module
from spring_agent.core.config.model import Setting
from spring_agent.core.config.settings import Model as ModelConfig
from spring_agent.core.config.settings import Provider as ProviderConfig


class FakeCatalog:
    def __init__(self) -> None:
        self.reads = 0
        self.model = ModelConfig(
            provider="provider", model="model", max_context_size=1000,
            display_name="Model", max_output_size=100, capabilities=[],
            support_efforts=[], default_effort="",
        )
        self.provider = ProviderConfig(type="openai", api_key="key", base_url=None)

    def snapshot(self) -> dict:
        self.reads += 1
        return {
            "default_model": "model",
            "models": {"model": self.model},
            "providers": {"provider": self.provider},
        }

    def default_model(self, values: dict | None = None) -> str:
        assert values is not None
        return values["default_model"]

    def get_model_config(self, model_id: str, values: dict | None = None) -> ModelConfig | None:
        assert values is not None
        return values["models"].get(model_id)

    def get_provider_config(self, provider_name: str, values: dict | None = None) -> ProviderConfig | None:
        assert values is not None
        return values["providers"].get(provider_name)

    def list_model_configs(self, values: dict | None = None) -> list[tuple[str, ModelConfig]]:
        assert values is not None
        return sorted(values["models"].items())

    def subagent_model(self, name: str, values: dict | None = None) -> str:
        assert values is not None
        return values.get("subagents", {}).get(name, "")


def test_model_resolution_reuses_one_catalog_snapshot(monkeypatch) -> None:
    catalog = FakeCatalog()
    setting = Setting()
    setting.set_catalog(catalog)
    monkeypatch.setattr(model_module, "build_model", lambda config, provider: (config, provider))

    resolved = setting.get_model()

    assert resolved == (catalog.model, catalog.provider)
    assert catalog.reads == 1
