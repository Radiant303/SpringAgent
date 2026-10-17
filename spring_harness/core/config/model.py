from typing import Any, Protocol

from pydantic_ai import ModelProfile
from pydantic_ai.models import Model
from pydantic_ai.models.openai import OpenAIChatModel, OpenAIResponsesModel
from pydantic_ai.providers.alibaba import AlibabaProvider
from pydantic_ai.providers.deepseek import DeepSeekProvider
from pydantic_ai.providers.openai import OpenAIProvider

from spring_harness.core.config.settings import Model as ModelConfig
from spring_harness.core.config.settings import Provider as ProviderConfig


class ModelCatalog(Protocol):
    def default_model(self) -> str:
        ...

    def get_model_config(self, model_id: str) -> ModelConfig | None:
        ...

    def get_provider_config(self, provider_name: str) -> ProviderConfig | None:
        ...

    def list_model_configs(self) -> list[tuple[str, ModelConfig]]:
        ...

    def subagent_model(self, name: str) -> str:
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
    )


def _alibaba(model_name: str, provider_config: Any, model_config: Any) -> Model:
    return OpenAIChatModel(
        model_name,
        provider=_provider(AlibabaProvider,
                           base_url=provider_config.base_url or "https://dashscope.aliyuncs.com/compatible-mode/v1",
                           api_key=provider_config.api_key),
        profile=_profile(model_config, native=True),
    )


def _deepseek(model_name: str, provider_config: Any, model_config: Any) -> Model:
    return OpenAIChatModel(
        model_name,
        provider=_provider(DeepSeekProvider, api_key=provider_config.api_key),
        profile=_profile(model_config),
    )


def _responses(model_name: str, provider_config: Any, model_config: Any) -> Model:
    return OpenAIResponsesModel(
        model_name,
        provider=_provider(OpenAIProvider,
                           base_url=provider_config.base_url, api_key=provider_config.api_key),
        profile=_profile(model_config),
    )


_MODEL_CREATORS = {
    "openai": _openai,
    "alibaba": _alibaba,
    "deepseek": _deepseek,
    "responses": _responses,
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

    def default_model(self) -> str:
        return self._require_catalog().default_model()

    def subagent_model(self, name: str) -> str:
        """子代理专用模型 ID；空串 = 继承主模型。"""
        return self._require_catalog().subagent_model(name)

    def get_model(self, model_name: str | None = None) -> Model:
        catalog = self._require_catalog()
        model_config = self.get_model_config(model_name)
        provider_config = catalog.get_provider_config(model_config.provider)
        if not provider_config:
            raise ValueError(f"Provider '{model_config.provider}' 不存在")

        return build_model(model_config, provider_config)

    def list_models(self) -> list[str]:
        return [mid for mid, _ in self.list_model_configs()]

    def list_model_configs(self) -> list[tuple[str, ModelConfig]]:
        return self._require_catalog().list_model_configs()

    def get_model_config(self, model_name: str | None = None) -> ModelConfig:
        catalog = self._require_catalog()
        model_name = model_name or catalog.default_model()
        model_config = catalog.get_model_config(model_name)
        if not model_config:
            raise ValueError(f"模型 '{model_name}' 不存在")
        return model_config


setting = Setting()
get_model = setting.get_model
get_model_config = setting.get_model_config
set_catalog = setting.set_catalog
