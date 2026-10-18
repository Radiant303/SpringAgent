from pydantic_ai import (
    ModelRequestContext,
    ModelResponse,
    RunContext,
)
from pydantic_ai.capabilities import Hooks

from spring_agent.core.agent.deps import CodingAgentDeps

hooks = Hooks()

FILE_MONITOR_SOURCE = "file_monitor"

_AUTO_INJECTED_SOURCES = {FILE_MONITOR_SOURCE}


def is_auto_injected_message(message: object) -> bool:
    """判断一条消息是否是系统自动注入的"""
    metadata = getattr(message, "metadata", None)
    return isinstance(metadata, dict) and metadata.get("source") in _AUTO_INJECTED_SOURCES


@hooks.on.before_model_request
async def snapshot_messages(
    ctx: RunContext[CodingAgentDeps],
    request_context: ModelRequestContext
) -> ModelRequestContext:
    ctx.deps.last_messages = list(request_context.messages)
    return request_context


@hooks.on.after_model_request
async def snapshot_response(
    ctx: RunContext[CodingAgentDeps],
    *,
    request_context: ModelRequestContext,
    response: ModelResponse,
) -> ModelResponse:
    ctx.deps.last_messages = [*request_context.messages, response]
    return response


def request_hooks(model_id: str | None = None, *, agent_name: str = "main") -> Hooks:
    from spring_agent.core.hooks.billing import RequestBillingModel

    request_hooks = Hooks()

    @request_hooks.on.before_model_request
    async def prepare_billing(
        ctx: RunContext[CodingAgentDeps], request_context: ModelRequestContext,
    ) -> ModelRequestContext:
        if ctx.deps.billing is not None and (request_context.model_settings or {}).get("openai_background"):
            from spring_agent.core.billing import BillingError

            raise BillingError("请求级计费不支持提供商后台轮询模式，请使用普通流式请求")
        request_context.model = RequestBillingModel(
            request_context.model, model_id=model_id or ctx.deps.model_id,
            agent_name=agent_name, usage_log=ctx.deps.usage_log, billing=ctx.deps.billing,
        )
        return request_context

    return request_hooks
