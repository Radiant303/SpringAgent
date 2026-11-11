from typing import Any, Protocol

from anthropic import AsyncAnthropic
from pydantic_ai import ModelProfile
from pydantic_ai.models import Model
from pydantic_ai.models.anthropic import AnthropicModel
from pydantic_ai.models.openai import OpenAIChatModel, OpenAIResponsesModel
from pydantic_ai.providers.alibaba import AlibabaProvider
from pydantic_ai.providers.anthropic import AnthropicProvider
from pydantic_ai.providers.deepseek import DeepSeekProvider
from pydantic_ai.providers.openai import OpenAIProvider
from pydantic_ai.settings import ModelSettings

from spring_agent.core.config.settings import Model as ModelConfig
from spring_agent.core.config.settings import Provider as ProviderConfig


class ModelCatalog(Protocol):
    def snapshot(self) -> dict[str, Any]:
        ...

    def default_model(self, values: dict[str, Any] | None = None) -> str:
        ...

    def get_model_config(self, model_id: str,
                         values: dict[str, Any] | None = None) -> ModelConfig | None:
        ...

    def get_provider_config(self, provider_name: str,
                            values: dict[str, Any] | None = None) -> ProviderConfig | None:
        ...

    def list_model_configs(self, values: dict[str, Any] | None = None) -> list[tuple[str, ModelConfig]]:
        ...

    def subagent_model(self, name: str, values: dict[str, Any] | None = None) -> str:
        """子代理专用模型 ID；空串 = 继承主模型。"""
        ...


def _profile(model_config: ModelConfig, native: bool = False) -> ModelProfile:
    """统一的 profile 配置。"""
    return ModelProfile(
        supports_json_schema_output=native,
        supports_json_object_output=not native,
        default_structured_output_mode='native' if native else 'prompted',
        supports_thinking="thinking" in model_config.capabilities,
    )


def _settings(model_config: ModelConfig) -> ModelSettings | None:
    if model_config.max_output_size > 0:
        return ModelSettings(max_tokens=model_config.max_output_size)
    return None


def _provider(
    provider_type: type[OpenAIProvider | AlibabaProvider | DeepSeekProvider], **kwargs: Any,
) -> OpenAIProvider | AlibabaProvider | DeepSeekProvider:
    provider = provider_type(**kwargs)
    return provider_type(openai_client=provider.client.with_options(max_retries=0))


def _openai(model_name: str, provider_config: Any, model_config: Any) -> Model:
    return OpenAIChatModel(
        model_name,
        provider=_provider(OpenAIProvider,
                           base_url=provider_config.base_url, api_key=provider_config.api_key),
        profile=_profile(model_config),
        settings=_settings(model_config),
    )


def _alibaba(model_name: str, provider_config: Any, model_config: Any) -> Model:
    return OpenAIChatModel(
        model_name,
        provider=_provider(AlibabaProvider,
                           base_url=provider_config.base_url or "https://dashscope.aliyuncs.com/compatible-mode/v1",
                           api_key=provider_config.api_key),
        profile=_profile(model_config, native=True),
        settings=_settings(model_config),
    )


def _deepseek(model_name: str, provider_config: Any, model_config: Any) -> Model:
    return OpenAIChatModel(
        model_name,
        provider=_provider(DeepSeekProvider, api_key=provider_config.api_key),
        profile=_profile(model_config),
        settings=_settings(model_config),
    )


def _responses(model_name: str, provider_config: Any, model_config: Any) -> Model:
    return OpenAIResponsesModel(
        model_name,
        provider=_provider(OpenAIProvider,
                           base_url=provider_config.base_url, api_key=provider_config.api_key),
        profile=_profile(model_config),
        settings=_settings(model_config),
    )


def _anthropic(model_name: str, provider_config: Any, model_config: Any) -> Model:
    client = AsyncAnthropic(
        api_key=provider_config.api_key,
        base_url=provider_config.base_url or "https://api.anthropic.com",
        max_retries=0,
    )
    return AnthropicModel(
        model_name,
        provider=AnthropicProvider(anthropic_client=client),
        profile=ModelProfile(supports_thinking="thinking" in model_config.capabilities),
        settings=_settings(model_config),
    )


_MODEL_CREATORS = {
    "openai": _openai,
    "alibaba": _alibaba,
    "deepseek": _deepseek,
    "responses": _responses,
    "anthropic": _anthropic,
}


def build_model(model_config: ModelConfig, provider_config: ProviderConfig) -> Model:
    """按 Provider 类型构造 pydantic-ai Model。"""
    creator = _MODEL_CREATORS.get(provider_config.type)

    if not creator:
        raise ValueError(f"不支持的 Provider 类型: {provider_config.type}")

    return creator(
        model_name=model_config.model,
        provider_config=provider_config,
        model_config=model_config,
    )


class Setting:
    def __init__(self) -> None:
        self._catalog: ModelCatalog | None = None

    def set_catalog(self, catalog: ModelCatalog) -> None:
        self._catalog = catalog

    def _require_catalog(self) -> ModelCatalog:
        if self._catalog is None:
            raise RuntimeError("模型目录未初始化，请先调用 set_catalog()")
        return self._catalog

    def snapshot(self) -> dict[str, Any]:
        return self._require_catalog().snapshot()

    def default_model(self, values: dict[str, Any] | None = None) -> str:
        return self._require_catalog().default_model(values)

    def subagent_model(self, name: str, values: dict[str, Any] | None = None) -> str:
        """子代理专用模型 ID；空串 = 继承主模型。"""
        return self._require_catalog().subagent_model(name, values)

    def get_model(self, model_name: str | None = None,
                  values: dict[str, Any] | None = None) -> Model:
        catalog = self._require_catalog()
        values = self.snapshot() if values is None else values
        model_name = model_name or self.default_model(values)
        model_config = self.get_model_config(model_name, values)
        provider_config = catalog.get_provider_config(model_config.provider, values)
        if not provider_config:
            raise ValueError(f"Provider '{model_config.provider}' 不存在")

        return build_model(model_config, provider_config)

    def list_models(self, values: dict[str, Any] | None = None) -> list[str]:
        return [mid for mid, _ in self.list_model_configs(values)]

    def list_model_configs(self, values: dict[str, Any] | None = None) -> list[tuple[str, ModelConfig]]:
        return self._require_catalog().list_model_configs(values)

    def get_model_config(self, model_name: str | None = None,
                         values: dict[str, Any] | None = None) -> ModelConfig:
        catalog = self._require_catalog()
        values = self.snapshot() if values is None else values
        model_name = model_name or self.default_model(values)
        model_config = catalog.get_model_config(model_name, values)
        if not model_config:
            raise ValueError(f"模型 '{model_name}' 不存在")
        return model_config


setting = Setting()
get_model = setting.get_model
get_model_config = setting.get_model_config
set_catalog = setting.set_catalog
