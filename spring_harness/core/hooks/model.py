import asyncio
from typing import Any

from pydantic_ai import (
    AgentRunResult,
    ModelRequest,
    ModelRequestContext,
    RunContext,
    UserPromptPart,
)
from pydantic_ai.capabilities import Hooks

from spring_harness.core.agent.deps import CodingAgentDeps

hooks = Hooks()

@hooks.on.after_run
async def start_monitor(
    ctx: RunContext[CodingAgentDeps],
    *,
    result: AgentRunResult[Any],
) -> AgentRunResult[Any]:
    await asyncio.to_thread(ctx.deps.monitor.start)
    return result


FILE_MONITOR_SOURCE = "file_monitor"
BACKGROUND_TASK_SOURCE = "background_task"
BACKGROUND_WAKE_SOURCE = "background_wake"

_AUTO_INJECTED_SOURCES = {FILE_MONITOR_SOURCE, BACKGROUND_TASK_SOURCE, BACKGROUND_WAKE_SOURCE}


def is_file_monitor_message(message: object) -> bool:
    """判断一条消息是否是由 file_monitor 自动注入的文件变动通知。"""
    metadata = getattr(message, "metadata", None)
    return isinstance(metadata, dict) and metadata.get("source") == FILE_MONITOR_SOURCE


def is_auto_injected_message(message: object) -> bool:
    """判断一条消息是否是系统自动注入的"""
    metadata = getattr(message, "metadata", None)
    return isinstance(metadata, dict) and metadata.get("source") in _AUTO_INJECTED_SOURCES


@hooks.on.before_model_request
async def stop_monitor(
    ctx: RunContext[CodingAgentDeps],
    request_context: ModelRequestContext
) -> ModelRequestContext:
    changes = await asyncio.to_thread(ctx.deps.monitor.stop)
    changes_text = ctx.deps.monitor.changes_to_string(changes)
    if changes_text is not None:
        request_context.messages.append(
            ModelRequest(
                parts=[UserPromptPart(changes_text)],
                metadata={"source": FILE_MONITOR_SOURCE},
            )
        )
    ctx.deps.last_messages = list(request_context.messages)
    return request_context

def request_hooks(model_id: str | None = None, *, agent_name: str = "main") -> Hooks:
    from spring_harness.core.hooks.billing import RequestBillingModel

    request_hooks = Hooks()

    @request_hooks.on.before_model_request
    async def prepare_billing(
        ctx: RunContext[CodingAgentDeps], request_context: ModelRequestContext,
    ) -> ModelRequestContext:
        if ctx.deps.billing is not None and (request_context.model_settings or {}).get("openai_background"):
            from spring_harness.core.billing import BillingError

            raise BillingError("请求级计费不支持提供商后台轮询模式，请使用普通流式请求")
        request_context.model = RequestBillingModel(
            request_context.model, model_id=model_id or ctx.deps.model_id,
            agent_name=agent_name, usage_log=ctx.deps.usage_log, billing=ctx.deps.billing,
        )
        return request_context

    return request_hooks
