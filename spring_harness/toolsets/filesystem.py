from dataclasses import replace
from typing import Any

from pydantic_ai import RunContext
from pydantic_ai.tools import ToolDefinition
from pydantic_ai.toolsets import AbstractToolset, ApprovalRequiredToolset, CombinedToolset
from pydantic_ai_harness import FileSystem

from spring_harness.toolsets.workspace_delete import workspace_delete_toolset


_DESCRIPTIONS = {
    "read_file": (
        "读取当前项目工作区的文本文件，返回行号和内容哈希。"
        "offset 为从零开始的行偏移，按返回提示分段读取。"
    ),
    "write_file": (
        "新建或完整覆盖工作区文本文件；父目录须已存在。"
        "覆盖前先读取原内容，可用 expected_hash 校验版本。"
    ),
    "edit_file": (
        "对工作区文本文件做精确文本替换。先读取文件，old_text 须恰好出现一次；"
        "可加入上下文确保唯一，使用 expected_hash 校验版本。"
    ),
    "list_directory": "列出工作区目录的可见直接子项及大小；path 默认为工作区根目录。",
    "search_files": (
        "按正则表达式搜索工作区的可见文本文件，返回路径、行号和匹配文本。"
        "可用 include_glob 筛选文件。"
    ),
    "find_files": (
        "按 glob 模式查找工作区可见文件和目录，返回相对路径。"
        "pattern 使用相对于搜索目录的模式。"
    ),
    "create_directory": "在工作区创建目录及所需父目录；同名目录已存在时可重复调用。",
    "file_info": "查看工作区文件或目录的类型、大小；文本文件还返回行数和内容哈希。",
}


def _prepare_descriptions(
    ctx: RunContext[Any], tool_defs: list[ToolDefinition],
) -> list[ToolDefinition]:
    return [
        replace(tool_def, description=_DESCRIPTIONS[tool_def.name])
        if tool_def.name in _DESCRIPTIONS else tool_def
        for tool_def in tool_defs
    ]


def filesystem(root_dir: str, read_only: bool = False):
    fs = FileSystem(root_dir=root_dir, read_only=read_only).get_toolset().prepared(_prepare_descriptions)
    toolsets: list[AbstractToolset[Any]] = [fs]
    if not read_only:
        toolsets.append(workspace_delete_toolset(root_dir))

    return ApprovalRequiredToolset(
        CombinedToolset(toolsets),
        approval_required_func=lambda ctx, tool, args:
            tool.name in {

            },
    )
