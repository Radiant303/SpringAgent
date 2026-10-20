from os import PathLike
from pathlib import Path
from typing import Any

from pydantic_ai import Agent, UsageLimits
from pydantic_ai.tools import DeferredToolRequests
from pydantic_ai_harness import Shell
from pydantic_ai_harness.compaction import ClampOversizedMessages, ClearToolResults

from spring_agent.capabilities.code_mode import code_mode
from spring_agent.capabilities.compaction import (
    NotifyingCompaction,
    OnCompaction,
    OnCompactionStart,
)
from spring_agent.capabilities.planning import OnPlanChange, planning
from spring_agent.capabilities.repo_context import repo_context
from spring_agent.capabilities.research import researcher
from spring_agent.capabilities.subagents import subagents
from spring_agent.capabilities.teaching import (
    OnTeachingChange,
    teaching_store_for,
    teaching_toolset,
)
from spring_agent.core.agent import (
    shell_patch as _shell_patch,  # noqa: F401  # Windows 杀进程补丁，导入即生效
)
from spring_agent.core.agent.deps import CodingAgentDeps
from spring_agent.core.config.model import get_model, get_model_config, setting
from spring_agent.core.config.settings import loop_control
from spring_agent.core.config.thinking import resolve_effort, thinking_capability
from spring_agent.core.hooks.model import hooks, request_hooks
from spring_agent.core.hooks.usage import count_text_tokens
from spring_agent.core.workspace import FileAccessContext
from spring_agent.instructions.default import register_default_instructions
from spring_agent.toolsets.ask_user import ask_user_toolset
from spring_agent.toolsets.filesystem import filesystem
from spring_agent.toolsets.page_site import page_site_toolset
from spring_agent.toolsets.session_info import session_info_toolset
from spring_agent.toolsets.storage import InternalStorageToolset
from spring_agent.toolsets.view_image import view_image_toolset


class SpringAgent(Agent[CodingAgentDeps, DeferredToolRequests | str]):
    """云端编程代理。"""

    def iter(self, user_prompt: Any = None, **kwargs: Any):  # type: ignore[override]
        if kwargs.get("usage_limits") is None:
            kwargs["usage_limits"] = UsageLimits(request_limit=loop_control.request_limit)
        return super().iter(user_prompt, **kwargs)


def create_agent(
    root_dir: str | PathLike[str] | Path = ".",
    model_name: str | None = None,
    session_id: str = "default",
    plan_on_change: OnPlanChange | None = None,
    teach_on_change: OnTeachingChange | None = None,
    compact_on_change: OnCompaction | None = None,
    compact_on_start: OnCompactionStart | None = None,
    effort: str | None = None,
    page_id: int | None = None,
    file_access: FileAccessContext | None = None,
) -> SpringAgent:
    """
    创建 SpringAgent Agent
    """
    root = Path(root_dir).expanduser().resolve()
    model_id = model_name or setting.default_model()
    model = get_model(model_id)
    model_config = get_model_config(model_id)
    effort = resolve_effort(model_config, effort)
    toolsets: list[Any] = [
        filesystem(str(root), access=file_access),
        ask_user_toolset,
        InternalStorageToolset(teaching_toolset(teaching_store_for(root, on_change=teach_on_change))),
        session_info_toolset(session_id=session_id, workspace=root,
                             model_id=model_id, effort=effort),
    ]
    if "image_in" in model_config.capabilities:
        toolsets.append(view_image_toolset)
    if page_id is not None:
        toolsets.append(page_site_toolset(page_id=page_id))
    agent = SpringAgent(
        model=model,
        toolsets=toolsets,
        output_type=[
            str,
            DeferredToolRequests,
        ],
        deps_type=CodingAgentDeps,
        capabilities=[
            thinking_capability(model_config, effort),
            hooks,
            request_hooks(model_id),
            planning(session_id, on_change=plan_on_change, root=root),
            code_mode(),
            subagents(str(root), model_id, effort, file_access=file_access),
            researcher(),
            ClampOversizedMessages(max_part_chars=100_000, tokenizer=count_text_tokens),
            ClearToolResults(
                max_fraction=0.75,
                context_window=model_config.max_context_size,
                keep_pairs=6,
                clear_tool_inputs=True,
                min_clear_tokens=8_192,
                placeholder="（过旧的工具结果已清理，需要时可重新读取）",
                exclude_tools=frozenset({"ask_user"}),
                tokenizer=count_text_tokens,
            ),
            NotifyingCompaction(
                max_fraction=0.8,
                keep_tokens=24_000,
                context_window=model_config.max_context_size,
                tokenizer=count_text_tokens,
                on_compaction=compact_on_change,
                on_compaction_start=compact_on_start,
            )
        ],
        retries=20
    )

    register_default_instructions(agent, page_id=page_id)

    return agent
