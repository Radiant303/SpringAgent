"""内部工具的存储变更协调。"""

import asyncio
import threading
from dataclasses import dataclass
from typing import Any

from pydantic_ai import ModelRetry, RunContext
from pydantic_ai.toolsets import ToolsetTool, WrapperToolset

from spring_agent.core.agent.deps import CodingAgentDeps
from spring_agent.core.filesystem import run_file_operation
from spring_agent.core.storage_notifications import pending_notifications


@dataclass
class InternalStorageToolset(WrapperToolset[CodingAgentDeps]):
    """教学文件的配额检查与用量记录。

    @author hanbing
    @since 2026-10-17
    """

    async def call_tool(self, name: str, tool_args: dict[str, Any],
                        ctx: RunContext[CodingAgentDeps], tool: ToolsetTool[CodingAgentDeps]) -> Any:
        if not name.startswith("teach_") or name == "teach_state":
            return await self.wrapped.call_tool(name, tool_args, ctx, tool)
        guard = ctx.deps.storage_guard
        access = ctx.deps.file_access
        if ctx.deps.user_id is None and access is None:
            return await self.wrapped.call_tool(name, tool_args, ctx, tool)
        if access is None or guard is None or access.user_id != ctx.deps.user_id \
                or access.workspace_root != ctx.deps.workspace:
            raise ModelRetry("内部文件资源需要有效的用户会话绑定。")
        loop = asyncio.get_running_loop()
        notifications = []

        async def invoke() -> Any:
            token = pending_notifications.set(notifications)
            try:
                return await self.wrapped.call_tool(name, tool_args, ctx, tool)
            finally:
                pending_notifications.reset(token)

        def operation(cancelled: threading.Event) -> Any:
            with guard.internal_change(("teaching.db", "teaching")):
                if cancelled.is_set():
                    raise InterruptedError("文件操作已取消。")
                result = asyncio.run_coroutine_threadsafe(invoke(), loop).result()
                if cancelled.is_set():
                    raise InterruptedError("文件操作已取消。")
                return result

        try:
            result = await run_file_operation(operation)
        except (OSError, ValueError) as error:
            raise ModelRetry(str(error)) from error
        for notify in notifications:
            await notify()
        if ctx.deps.storage_on_change is not None:
            await ctx.deps.storage_on_change()
        return result
