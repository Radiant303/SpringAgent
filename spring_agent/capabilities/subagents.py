from dataclasses import replace

from pydantic_ai import Agent
from pydantic_ai.toolsets import AbstractToolset
from pydantic_ai.models import Model
from pydantic_ai_harness.subagents import SubAgent, SubAgents
from spring_agent.capabilities.research import researcher
from spring_agent.core.agent.deps import CodingAgentDeps
from spring_agent.core.workspace import FileAccessContext
from spring_agent.core.config.model import get_model, get_model_config, setting
from spring_agent.core.config.thinking import thinking_capability
from spring_agent.core.hooks.model import request_hooks
from spring_agent.core.log import logger
from spring_agent.toolsets.filesystem import filesystem


def _resolve_model(name: str) -> tuple[Model | None, str | None]:
    model_id = setting.subagent_model(name)
    if not model_id:
        return None, None
    try:
        return get_model(model_id), model_id
    except ValueError:
        logger.warning("子代理 {} 配置的模型 {} 不存在，改为继承主模型", name, model_id)
        return None, None


def _thinking(model_id: str | None, parent_model_id: str, effort: str | None):
    return thinking_capability(get_model_config(model_id or parent_model_id),
                               None if model_id else effort)


def searcher(parent_model_id: str, effort: str | None = None) -> SubAgent[CodingAgentDeps]:
    model, model_id = _resolve_model('searcher')
    model_config = get_model_config(model_id or parent_model_id)
    return SubAgent[CodingAgentDeps](
        agent=Agent(
            model,
            name='searcher',
            description='检索资料并整理来源证据，向主 Agent 提供研究结果的子代理。',
            instructions=(
                '根据主 Agent 的问题，使用当前可用的检索工具查找资料并归纳结论。'
                '将结论、来源链接或标识、关键证据及待核实之处返回主 Agent；'
                '区分检索所得与推断，说明资料覆盖范围与证据强度。'
            ),
            deps_type=CodingAgentDeps,
            capabilities=[_thinking(model_id, parent_model_id, effort),
                          request_hooks(model_id or parent_model_id, agent_name="searcher"),
                          researcher(native="native_search" in model_config.capabilities)],
            toolsets=[],
        ),
        timeout_seconds=360,
        max_calls=3,
    )


def reviewer(root_dir: str, parent_model_id: str, effort: str | None = None,
             file_access: FileAccessContext | None = None) -> SubAgent[CodingAgentDeps]:
    """代码审查子代理。"""
    model, model_id = _resolve_model('reviewer')
    return SubAgent[CodingAgentDeps](
        agent=Agent(
            model,
            name='reviewer',
            description='只读审查当前项目代码，向主 Agent 提供缺陷、边界遗漏和风险证据的子代理；仅在用户主动要求审查时使用。',
            instructions=(
                '审查主 Agent 指定的代码，先读相关文件再下结论。'
                '将每条意见的文件路径、行号、问题原因和修改建议返回主 Agent，'
                '保留定位问题所需的内部路径作为证据，由主 Agent 整理面向用户的说明。'
                '通过只读工具核对证据，将审查结果交由主 Agent 在聊天中展示。'
            ),
            deps_type=CodingAgentDeps,
            capabilities=[_thinking(model_id, parent_model_id, effort),
                          request_hooks(model_id or parent_model_id, agent_name="reviewer")],
            toolsets=[filesystem(root_dir, read_only=True, access=file_access)],
        ),
        timeout_seconds=3600,
        max_calls=3,
    )


class _ProjectSubAgents(SubAgents[CodingAgentDeps]):
    """项目子代理的协作说明。

    @author hanbing
    @since 2026-10-17
    """

    def get_instructions(self):
        instructions = super().get_instructions()
        if isinstance(instructions, str):
            return instructions.replace(
                'Each runs in its own fresh context and does not see this conversation, so pass everything it needs.',
                'Each starts with a fresh context; include the goal, relevant facts, and expected result in the task. '
                'Invoke `reviewer` only when the user explicitly asks for a review or names the review agent; '
                'do not start a review on your own initiative.',
            )
        return instructions

    def get_toolset(self):
        toolset = super().get_toolset()
        if isinstance(toolset, AbstractToolset):
            return toolset.prepared(lambda ctx, definitions: [
                replace(definition, description=(
                    '将独立任务委派给指定子代理并获取结果。任务说明应包含目标、相关事实、文件位置和预期产出。'
                    '可用子代理及其分工见当前指令。'
                )) for definition in definitions
            ])
        return toolset


def subagents(root_dir: str, parent_model_id: str, effort: str | None = None,
              file_access: FileAccessContext | None = None) -> SubAgents[CodingAgentDeps]:
    return _ProjectSubAgents(
        agents=[searcher(parent_model_id, effort), reviewer(root_dir, parent_model_id, effort, file_access)]
    )
