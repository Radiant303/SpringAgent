from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field

from spring_agent.core.config.model import setting as model_setting
from spring_agent.core.rpc.schema import InitializeResult

DEFAULT_FRONTEND_DIR = Path(__file__).resolve().parents[2] / "frontend"


class _WireModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True)


class ModelInfo(_WireModel):
    id: str
    display_name: str = Field(validation_alias="displayName", serialization_alias="displayName")
    provider: str
    max_context_size: int = Field(validation_alias="maxContextSize", serialization_alias="maxContextSize")
    thinking_enabled: bool = Field(serialization_alias="thinkingEnabled")
    support_efforts: list[str] = Field(serialization_alias="supportEfforts")
    default_effort: str | None = Field(serialization_alias="defaultEffort")


class ModelsListResult(_WireModel):
    models: list[ModelInfo]
    default_model: str = Field(validation_alias="defaultModel", serialization_alias="defaultModel")


class WebInitializeResult(InitializeResult):
    workspace: str
    models: list[ModelInfo]
    default_model: str = Field(validation_alias="defaultModel", serialization_alias="defaultModel")
    message_edit_limit: int = Field(serialization_alias="messageEditLimit")
    answer_retry_limit: int = Field(serialization_alias="answerRetryLimit")


def conversation_limits() -> dict[str, int]:
    from spring_agent.core.config.platform import platform_settings

    values = platform_settings().snapshot()
    result = {}
    for key in ("message_edit_limit", "answer_retry_limit"):
        try:
            value = int(values.get(key, "6"))
        except ValueError:
            value = 6
        result[key] = max(0, min(6, value))
    return result


def _model_infos(values: dict | None = None) -> list[ModelInfo]:
    return [
        ModelInfo(
            id=model_id,
            display_name=model.display_name,
            provider=model.provider,
            max_context_size=model.max_context_size,
            thinking_enabled="thinking" in model.capabilities,
            support_efforts=model.support_efforts if "thinking" in model.capabilities else [],
            default_effort=model.default_effort if "thinking" in model.capabilities else None,
        )
        for model_id, model in model_setting.list_model_configs(values)
    ]
