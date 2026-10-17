from typing import Any

from pydantic_ai.toolsets import AbstractToolset, ApprovalRequiredToolset, CombinedToolset
from pydantic_ai_harness import FileSystem

from spring_harness.toolsets.workspace_delete import workspace_delete_toolset


def filesystem(root_dir: str, read_only: bool = False):
    fs = FileSystem(root_dir=root_dir, read_only=read_only).get_toolset()
    toolsets: list[AbstractToolset[Any]] = [fs]
    if not read_only:
        toolsets.append(workspace_delete_toolset(root_dir))

    return ApprovalRequiredToolset(
        CombinedToolset(toolsets),
        approval_required_func=lambda ctx, tool, args:
            tool.name in {

            },
    )
