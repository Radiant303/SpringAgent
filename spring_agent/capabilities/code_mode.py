from dataclasses import dataclass, replace
from typing import Any

from pydantic_ai import RunContext
from pydantic_ai.exceptions import UserError
from pydantic_ai.tools import ToolDefinition
from pydantic_ai.toolsets import AbstractToolset, ToolsetTool, WrapperToolset
from pydantic_ai_harness import CodeMode

READ_ONLY_TOOLS = (
    "read_file",
    "list_directory",
    "search_files",
    "find_files",
    "file_info",
    "current_session",
)
SANDBOX_ALIASES = {f"batch_{name}": name for name in READ_ONLY_TOOLS}


@dataclass
class _SandboxAliases(WrapperToolset[Any]):
    """只读工具的编排别名。

    @author hanbing
    @since 2026-10-17
    """

    async def get_tools(self, ctx: RunContext[Any]) -> dict[str, ToolsetTool[Any]]:
        tools = dict(await self.wrapped.get_tools(ctx))
        for alias, original in SANDBOX_ALIASES.items():
            if original not in tools:
                continue
            if alias in tools:
                raise UserError(f"编排工具别名冲突：{alias}")
            tool = tools[original]
            tools[alias] = replace(
                tool, toolset=self, tool_def=replace(tool.tool_def, name=alias),
            )
        return tools

    async def call_tool(
        self, name: str, tool_args: dict[str, Any], ctx: RunContext[Any], tool: ToolsetTool[Any],
    ) -> Any:
        original = SANDBOX_ALIASES.get(name)
        if original is not None:
            name = original
            ctx = replace(ctx, tool_name=original)
            tool = replace(tool, tool_def=replace(tool.tool_def, name=original))
        return await self.wrapped.call_tool(name, tool_args, ctx, tool)


def _prepare_description(
    ctx: RunContext[Any], tool_defs: list[ToolDefinition],
) -> list[ToolDefinition]:
    guidance = (
        "批量调用下列 batch_ 只读工具并整理结果。"
        "使用关键字参数调用，异步函数通过 await 获取结果；"
        "独立调用可用 await asyncio.gather(...) 组合，按位置传入各个调用。"
        "文件工具的 scope 选择 workspace 项目空间或 knowledge 个人知识空间，path 相对于所选空间。片段末尾的表达式作为返回值。"
        "同一次 Agent 运行内，连续的 run_code 调用可复用内存变量；restart=true 重置本次编排状态。"
        "开始新的对话轮次、恢复用户回答或工具审批后，先通过只读工具重新获取所需数据。"
        "将后续需要的关键信息作为片段返回值，便于后续运行依据对话历史重新构建数据。"
        "可按名称导入 asyncio、json、re、math、typing、sys、unicodedata、datetime。"
        "文件读取通过 batch_ 工具完成，文件修改通过独立文件工具完成。\n\n"
    )
    prepared = []
    for tool_def in tool_defs:
        if tool_def.name != "run_code":
            prepared.append(tool_def)
            continue
        description = tool_def.description or ""
        _, marker, catalog = description.partition("The following functions are available inside the sandbox.")
        catalog_start = catalog.find("```python\n")
        if not marker or catalog_start < 0:
            raise UserError("代码模式需要包含函数签名的工具目录。")
        schema = dict(tool_def.parameters_json_schema)
        properties = {key: dict(value) for key, value in schema.get("properties", {}).items()}
        properties["code"]["description"] = "包含工具调用与结果整理步骤的编排片段。"
        properties["restart"]["description"] = "设为 true 时重置本次 Agent 运行内的编排状态；默认在同次运行的连续调用间复用状态。"
        schema["properties"] = properties
        prepared.append(replace(
            tool_def, description=guidance + catalog[catalog_start:], parameters_json_schema=schema,
        ))
    return prepared


class _DualAccessCodeMode(CodeMode[Any]):
    """保留工具直调入口的 Code Mode。

    @author hanbing
    @since 2026-10-17
    """

    def get_wrapper_toolset(self, toolset: AbstractToolset[Any]) -> AbstractToolset[Any] | None:
        wrapped = super().get_wrapper_toolset(_SandboxAliases(wrapped=toolset))
        return wrapped.prepared(_prepare_description) if wrapped is not None else None


def code_mode() -> CodeMode:
    """提供只读工具直调与编排双入口。"""
    return _DualAccessCodeMode(tools=list(SANDBOX_ALIASES))
