from pydantic_ai import Agent
from pydantic_ai.models import Model
from pydantic_ai_harness.subagents import SubAgent, SubAgents
from spring_harness.capabilities.research import researcher
from spring_harness.core.agent.deps import CodingAgentDeps
from spring_harness.core.config.model import get_model, get_model_config, setting
from spring_harness.core.config.thinking import thinking_capability
from spring_harness.core.hooks.model import request_hooks
from spring_harness.core.log import logger
from spring_harness.toolsets.filesystem import filesystem


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
    return SubAgent[CodingAgentDeps](
        agent=Agent(
            model,
            name='searcher',
            description='强大的搜索Agent，具有世界上最丰富的知识库',
            instructions='你是一个知识搜索子代理Agent,善于科普，你的科普对象是大模型，你需要让你的输出能够让大模型听懂即可',
            deps_type=CodingAgentDeps,
            capabilities=[_thinking(model_id, parent_model_id, effort),
                          request_hooks(model_id or parent_model_id, agent_name="searcher"), researcher()],
            toolsets=[],
        ),
        timeout_seconds=360,
        max_calls=3,
    )


def reviewer(root_dir: str, parent_model_id: str, effort: str | None = None) -> SubAgent[CodingAgentDeps]:
    """代码审查子代理。"""
    model, model_id = _resolve_model('reviewer')
    return SubAgent[CodingAgentDeps](
        agent=Agent(
            model,
            name='reviewer',
            description='代码审查Agent，拥有项目文件的只读访问权限，擅长发现 bug、边界遗漏、风格不一致和潜在风险',
            instructions='你是一个代码审查子代理Agent,你的审查对象是大模型产出的代码，你的读者也是大模型。'
            '审查时先读相关文件再下结论，每条意见给出文件与位置、问题原因、修改建议；只读，不要尝试修改任何文件',
            deps_type=CodingAgentDeps,
            capabilities=[_thinking(model_id, parent_model_id, effort),
                          request_hooks(model_id or parent_model_id, agent_name="reviewer")],
            toolsets=[filesystem(root_dir, read_only=True)],
        ),
        timeout_seconds=3600,
        max_calls=3,
    )


def subagents(root_dir: str, parent_model_id: str, effort: str | None = None) -> SubAgents[CodingAgentDeps]:
    return SubAgents[CodingAgentDeps](
        agents=[searcher(parent_model_id, effort), reviewer(root_dir, parent_model_id, effort)]
    )
