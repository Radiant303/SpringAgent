from os import PathLike
from pathlib import Path
from typing import Any

from pydantic_ai import Agent, UsageLimits
from pydantic_ai.tools import DeferredToolRequests
from pydantic_ai_harness import Shell
from spring_harness.capabilities.research import researcher
from spring_harness.capabilities.code_mode import code_mode
from spring_harness.capabilities.compaction import NotifyingCompaction, OnCompaction
from spring_harness.capabilities.planning import OnPlanChange, planning
from spring_harness.capabilities.repo_context import repo_context
from spring_harness.capabilities.skills import skills
from spring_harness.capabilities.subagents import subagents
from spring_harness.capabilities.teaching import (
    OnTeachingChange,
    teaching_store_for,
    teaching_toolset,
)
from spring_harness.core.agent import (
    shell_patch as _shell_patch,  # noqa: F401  # Windows 杀进程补丁，导入即生效
)
from spring_harness.core.agent.deps import CodingAgentDeps
from spring_harness.core.workspace import FileAccessContext
from spring_harness.core.config.model import get_model, get_model_config, setting
from spring_harness.core.config.settings import loop_control
from spring_harness.core.config.thinking import resolve_effort, thinking_capability
from spring_harness.core.hooks.model import hooks, request_hooks
from spring_harness.instructions.default import register_default_instructions
from spring_harness.toolsets.ask_user import ask_user_toolset
from spring_harness.toolsets.filesystem import filesystem
from spring_harness.toolsets.page_site import page_site_toolset
from spring_harness.toolsets.session_info import session_info_toolset
from spring_harness.toolsets.view_image import view_image_toolset


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
    effort: str | None = None,
    page_id: int | None = None,
    file_access: FileAccessContext | None = None,
) -> SpringAgent:
    """
    创建 Spring Harness Agent
    """
    root = Path(root_dir).expanduser().resolve()
    model_id = model_name or setting.default_model()
    model = get_model(model_id)
    model_config = get_model_config(model_id)
    effort = resolve_effort(model_config, effort)
    toolsets: list[Any] = [
        filesystem(str(root), access=file_access),
        ask_user_toolset,
        teaching_toolset(teaching_store_for(root, on_change=teach_on_change)),
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
            skills(),
            planning(session_id, on_change=plan_on_change),
            code_mode(root),
            subagents(str(root), model_id, effort, file_access=file_access),
            researcher(),
            NotifyingCompaction(
                max_fraction=0.8,
                keep_messages=4,
                fallback_context_window=model_config.max_context_size,
                on_compaction=compact_on_change,
            )
        ],
        retries=20
    )

    register_default_instructions(agent, page_id=page_id)

    return agent
