from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any

from pydantic_ai import RunContext
from pydantic_ai.exceptions import UserError
from pydantic_ai.toolsets import AbstractToolset, ToolsetTool, WrapperToolset
from pydantic_ai_harness import CodeMode
from pydantic_monty import MountDir

from spring_harness.core.config.settings import STATE_DIR

READ_ONLY_TOOLS = (
    "read_file",
    "list_directory",
    "search_files",
    "find_files",
    "file_info",
    "read_knowledge",
    "read_index_knowledge",
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


class _DualAccessCodeMode(CodeMode[Any]):
    """保留工具直调入口的 Code Mode。

    @author hanbing
    @since 2026-10-17
    """

    def get_wrapper_toolset(self, toolset: AbstractToolset[Any]) -> AbstractToolset[Any] | None:
        return super().get_wrapper_toolset(_SandboxAliases(wrapped=toolset))


def code_mode(root: Path) -> CodeMode:
    """提供只读工具直调与编排双入口。"""
    scratch = root / STATE_DIR / ".agent-scratch"
    scratch.mkdir(parents=True, exist_ok=True)
    return _DualAccessCodeMode(
        mount=[
            MountDir(virtual_path="/work", host_path=root, mode="overlay"),
            MountDir(virtual_path="/scratch", host_path=scratch, mode="read-write"),
        ],
        tools=list(SANDBOX_ALIASES),
    )
