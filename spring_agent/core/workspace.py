"""文件工作区资源与存储准入协议。"""

from contextlib import AbstractContextManager
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Protocol

KNOWLEDGE_FILES = ("declarative.md", "procedural.md", "conditional.md")


class FileScope(StrEnum):
    """文件工作区的作用域。

    @author hanbing
    @since 2026-10-17
    """

    WORKSPACE = "workspace"
    KNOWLEDGE = "knowledge"


@dataclass(frozen=True)
class WorkspaceResource:
    """绑定根目录的文件工作区。

    @author hanbing
    @since 2026-10-17
    """

    scope: FileScope
    root: Path
    allowed_files: tuple[str, ...] = ()


@dataclass(frozen=True)
class FileAccessContext:
    """用户会话的文件资源归属。

    @author hanbing
    @since 2026-10-17
    """

    user_id: int
    work_id: str
    workspace_root: Path
    knowledge_root: Path

    def __post_init__(self) -> None:
        if type(self.user_id) is not int or self.user_id <= 0 or not self.work_id:
            raise ValueError("文件资源需要有效的用户和项目归属。")
        if not self.workspace_root.is_absolute() or not self.knowledge_root.is_absolute():
            raise ValueError("文件资源需要服务端提供的绝对根目录。")
        base = self.workspace_root.parent.parent
        if self.workspace_root != base / "works" / self.work_id:
            raise ValueError("项目文件资源的目录与归属不一致。")
        if self.knowledge_root != base / "users" / str(self.user_id) / "knowledge":
            raise ValueError("知识文件资源的目录与归属不一致。")

    @property
    def data_root(self) -> Path:
        return self.workspace_root.parent.parent

    def resource(self, scope: FileScope) -> WorkspaceResource:
        if scope == FileScope.KNOWLEDGE:
            return WorkspaceResource(scope, self.knowledge_root, KNOWLEDGE_FILES)
        return WorkspaceResource(scope, self.workspace_root)


class StorageChange(Protocol):
    """文件变更的配额检查与用量记录。

    @author hanbing
    @since 2026-10-17
    """

    def check_write(self) -> None: ...
    def prepare(self) -> None: ...
    def record(self, path: str, old_size: int, new_size: int | None) -> None: ...


class StorageGuard(Protocol):
    """文件变更的存储准入协议。

    @author hanbing
    @since 2026-10-17
    """

    def change(self, resource: WorkspaceResource) -> AbstractContextManager[StorageChange]: ...
    def internal_change(self, paths: tuple[str, ...]) -> AbstractContextManager[None]: ...
