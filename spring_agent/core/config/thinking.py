from pydantic_ai.capabilities import Thinking
from pydantic_ai.settings import ThinkingLevel

from spring_agent.core.config.settings import Model

EFFORTS = ("minimal", "low", "medium", "high", "xhigh", "on", "off")


def resolve_effort(model: Model, effort: str | None = None) -> str | None:
    if "thinking" not in model.capabilities:
        return None
    levels = model.support_efforts
    if not levels or len(levels) != len(set(levels)) or any(level not in EFFORTS for level in levels):
        raise ValueError("模型的思考挡位配置不合法")
    if model.default_effort not in levels:
        raise ValueError("模型的默认思考挡位不在已选挡位中")
    selected = model.default_effort if effort is None else effort
    if selected not in levels:
        raise ValueError(f"模型不支持思考挡位 '{selected}'")
    return selected


def thinking_capability(model: Model, effort: str | None = None) -> Thinking:
    selected = resolve_effort(model, effort)
    if selected is None or selected == "off":
        return Thinking(effort=False)
    if selected == "on":
        return Thinking(effort=True)
    from typing import cast

    return Thinking(effort=cast(ThinkingLevel, selected))
