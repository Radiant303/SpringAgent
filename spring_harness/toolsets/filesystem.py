"""项目与个人知识工作区的文件工具。"""

from functools import wraps
from pathlib import Path
from typing import Any

from pydantic_ai import FunctionToolset, ModelRetry, RunContext
from pydantic_ai.toolsets import ApprovalRequiredToolset

from spring_harness.core.agent.deps import CodingAgentDeps
from spring_harness.core.filesystem import WorkspaceFiles, run_file_operation
from spring_harness.core.workspace import FileAccessContext, FileScope, WorkspaceResource


def _recoverable(function):
    @wraps(function)
    async def wrapped(*args, **kwargs):
        try:
            return await function(*args, **kwargs)
        except ValueError as error:
            raise ModelRetry(str(error)) from error
        except OSError as error:
            raise ModelRetry("文件操作需要有效的文件状态、文本编码和存储权限，请核对后重试。") from error
    return wrapped


def filesystem(root_dir: str, read_only: bool = False, *, access: FileAccessContext | None = None):
    root = Path(root_dir).absolute()
    resources = {FileScope.WORKSPACE: WorkspaceFiles(WorkspaceResource(FileScope.WORKSPACE, root))}
    if access is not None:
        if root != access.workspace_root:
            raise ValueError("文件工具与会话项目目录绑定不一致。")
        resources = {scope: WorkspaceFiles(access.resource(scope)) for scope in FileScope}

    def selected(ctx: RunContext[CodingAgentDeps], scope: FileScope) -> WorkspaceFiles:
        if access is not None:
            if ctx.deps.file_access != access or ctx.deps.user_id != access.user_id or ctx.deps.workspace != root:
                raise ValueError("文件工具与当前用户资源绑定不一致。")
            if ctx.deps.storage_guard is None:
                raise ValueError("当前用户文件资源需要存储准入服务。")
        elif ctx.deps.file_access is not None or ctx.deps.user_id is not None:
            raise ValueError("当前用户文件资源需要完成会话绑定。")
        if scope not in resources:
            raise ValueError("当前会话的个人知识工作区尚未绑定。")
        return resources[scope]

    async def change(ctx: RunContext[CodingAgentDeps], scope: FileScope, name: str, path: str, **args: Any):
        files = selected(ctx, scope)
        return await run_file_operation(lambda cancelled: files.change(
            name, path, args, ctx.deps.storage_guard, cancelled,
        ))

    @_recoverable
    async def read_file(ctx: RunContext[CodingAgentDeps], path: str, *, offset: int = 0,
                        limit: int | None = None, scope: FileScope = FileScope.WORKSPACE) -> str:
        """读取所选文件工作区的文本，返回行号与内容哈希；offset 从零开始。"""
        return await selected(ctx, scope).read("read_file", path, offset=offset, limit=limit)

    @_recoverable
    async def list_directory(ctx: RunContext[CodingAgentDeps], path: str = ".", *,
                             scope: FileScope = FileScope.WORKSPACE) -> str:
        """列出所选文件工作区的目录子项与大小。"""
        return await selected(ctx, scope).read("list_directory", path)

    @_recoverable
    async def search_files(ctx: RunContext[CodingAgentDeps], pattern: str, *, path: str = ".",
                           include_glob: str | None = None, scope: FileScope = FileScope.WORKSPACE) -> str:
        """在所选文件工作区中按正则搜索文本，可用 include_glob 筛选文件。"""
        return await selected(ctx, scope).read("search_files", path, pattern=pattern, include_glob=include_glob)

    @_recoverable
    async def find_files(ctx: RunContext[CodingAgentDeps], pattern: str, *, path: str = ".",
                         scope: FileScope = FileScope.WORKSPACE) -> str:
        """在所选文件工作区中按相对于 path 的 glob 查找文件与目录。"""
        return await selected(ctx, scope).read("find_files", path, pattern=pattern)

    @_recoverable
    async def file_info(ctx: RunContext[CodingAgentDeps], path: str, *,
                        scope: FileScope = FileScope.WORKSPACE) -> str:
        """查看所选文件工作区的文件类型、字节数、行数与哈希。"""
        return await selected(ctx, scope).read("file_info", path)

    @_recoverable
    async def write_file(ctx: RunContext[CodingAgentDeps], path: str, content: str, *,
                         expected_hash: str | None = None, scope: FileScope = FileScope.WORKSPACE) -> str:
        """新建或覆盖所选工作区的文本文件；覆盖时提供读取所得的 expected_hash。"""
        return await change(ctx, scope, "write_file", path, content=content, expected_hash=expected_hash)

    @_recoverable
    async def edit_file(ctx: RunContext[CodingAgentDeps], path: str, old_text: str, new_text: str, *,
                        expected_hash: str, scope: FileScope = FileScope.WORKSPACE) -> str:
        """精确替换所选工作区的文本；old_text 恰好出现一次，expected_hash 使用读取所得版本。"""
        return await change(ctx, scope, "edit_file", path, old_text=old_text, new_text=new_text,
                            expected_hash=expected_hash)

    @_recoverable
    async def create_directory(ctx: RunContext[CodingAgentDeps], path: str, *,
                               scope: FileScope = FileScope.WORKSPACE) -> str:
        """在所选工作区创建目录及所需父目录。"""
        return await change(ctx, scope, "create_directory", path)

    @_recoverable
    async def delete_path(ctx: RunContext[CodingAgentDeps], path: str, *,
                          scope: FileScope = FileScope.WORKSPACE) -> dict[str, Any]:
        """删除所选工作区内的文件或子目录，返回实际删除项数和释放字节数。"""
        return await change(ctx, scope, "delete_path", path)

    tools = FunctionToolset(tools=[read_file, list_directory, search_files, find_files, file_info])
    if not read_only:
        tools.add_function(write_file, sequential=True)
        tools.add_function(edit_file, sequential=True)
        tools.add_function(create_directory, sequential=True)
        tools.add_function(delete_path, sequential=True)
    return ApprovalRequiredToolset(
        tools,
        approval_required_func=lambda ctx, tool, args:
            args.get("scope", FileScope.WORKSPACE) == FileScope.KNOWLEDGE
            and tool.name in {"write_file", "edit_file", "delete_path", "create_directory"},
    )
