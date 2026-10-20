"""文件工作区的路径校验与变更执行。"""

from __future__ import annotations

import asyncio
import hashlib
import os
import stat
import tempfile
import threading
from collections.abc import Callable
from contextlib import nullcontext
from pathlib import Path, PureWindowsPath
from typing import Any

from pydantic_ai_harness import FileSystem
from pydantic_ai_harness.filesystem import FileSystemToolset

from spring_agent.core.log import logger
from spring_agent.core.workspace import FileScope, StorageChange, StorageGuard, WorkspaceResource

# 私有文件权限：仅属主可读写
PRIVATE_FILE_MODE = 0o600
# 私有目录权限：仅属主可进入
PRIVATE_DIRECTORY_MODE = 0o700
# 公开页面文件权限：需被只读分发进程读取
PUBLIC_FILE_MODE = 0o644
# 公开页面目录权限：需被只读分发进程进入
PUBLIC_DIRECTORY_MODE = 0o755
# 页面子树的目录名，位于项目工作区根下
PAGE_DIRECTORY = "page"

PUBLIC_READ = stat.S_IRGRP | stat.S_IROTH
PUBLIC_TRAVERSE = stat.S_IXGRP | stat.S_IXOTH


def public_modes(resource: WorkspaceResource, path: Path) -> tuple[int, int] | None:
    """返回该路径适用的（文件权限, 目录权限）；不需要公开时返回 None。"""
    if resource.scope != FileScope.WORKSPACE:
        return None
    try:
        relative = path.relative_to(resource.root)
    except ValueError:
        return None
    if not relative.parts or relative.parts[0] != PAGE_DIRECTORY:
        return None
    if not _safe_to_open(resource.root / PAGE_DIRECTORY):
        return None
    return PUBLIC_FILE_MODE, PUBLIC_DIRECTORY_MODE


def _safe_to_open(path: Path) -> bool:
    """不存在或是普通目录；链接等异常节点返回 False。"""
    try:
        info = path.lstat()
    except FileNotFoundError:
        return True
    except OSError:
        return False
    return stat.S_ISDIR(info.st_mode)


def grant_public_read(path: Path, *, directory: bool = False, parents: Path | None = None) -> None:
    """补上组和其他读位，目录连同进入位；已有位保持不变。"""
    _grant(path, directory=directory)
    if parents is None:
        return
    current = path.parent
    while current.is_relative_to(parents):
        _grant(current, directory=True)
        if current == parents:
            return
        current = current.parent


def _grant(path: Path, *, directory: bool) -> None:
    try:
        info = path.lstat()
    except OSError:
        return
    if stat.S_ISLNK(info.st_mode):
        return
    if not directory and (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1):
        return
    extra = PUBLIC_READ | (PUBLIC_TRAVERSE if directory else 0)
    mode = stat.S_IMODE(info.st_mode)
    if mode & extra == extra:
        return
    try:
        path.chmod(mode | extra)
    except OSError as error:
        logger.warning("公开目录权限补齐失败: path={} error={}", path, error)


class _UnmeteredChange:
    """本地文件变更的空计量记录。

    @author hanbing
    @since 2026-10-17
    """

    def check_write(self) -> None:
        pass

    def prepare(self) -> None:
        pass

    def record(self, path: str, old_size: int, new_size: int | None) -> None:
        pass


def check_path(path: Path, *, directory: bool = False) -> None:
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        try:
            info = current.lstat()
        except FileNotFoundError:
            return
        if stat.S_ISLNK(info.st_mode) or (
            getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
        ):
            raise ValueError("文件路径须由普通目录和普通文件组成。")
        if current != path or directory:
            if not stat.S_ISDIR(info.st_mode):
                raise ValueError("路径中的目录节点需要为普通目录。")
        elif not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise ValueError("目标需要为独立的普通文件。")


def directory_size_bytes(root: Path) -> int:
    check_path(root, directory=True)
    try:
        entries = list(root.iterdir())
    except FileNotFoundError:
        return 0
    total = 0
    for path in entries:
        info = path.lstat()
        if stat.S_ISDIR(info.st_mode) and not path.is_junction():
            total += directory_size_bytes(path)
        else:
            check_path(path)
            total += info.st_size
    return total


def workspace_size_bytes(root: Path, internal_name: str) -> tuple[int, int]:
    check_path(root, directory=True)
    try:
        entries = list(root.iterdir())
    except FileNotFoundError:
        return 0, 0
    total = internal = 0
    for path in entries:
        check_path(path, directory=path.is_dir())
        size = directory_size_bytes(path) if path.is_dir() else path.stat().st_size
        total += size
        if path == root / internal_name:
            internal += size
    return total, internal


def resource_size_bytes(resource: WorkspaceResource) -> int:
    if not resource.allowed_files:
        return directory_size_bytes(resource.root)
    check_path(resource.root, directory=True)
    total = 0
    for name in resource.allowed_files:
        path = resource.root / name
        check_path(path)
        try:
            total += path.stat().st_size
        except FileNotFoundError:
            pass
    return total


def content_hash(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()[:12]


def _relative_path(path: str) -> str:
    windows = PureWindowsPath(path)
    if not path.strip() or Path(path).is_absolute() or windows.drive or windows.root:
        raise ValueError("请使用所选工作区内的相对路径。")
    parts = []
    for part in path.replace("\\", "/").split("/"):
        if part in ("", "."):
            continue
        if part == ".." or part.endswith((".", " ")) or any(
            ord(char) < 32 or char in '<>:"|?*' for char in part
        ):
            raise ValueError("请使用工作区内的直接相对路径和有效文件名。")
        stem = part.split(".", 1)[0].upper()
        if stem in {"CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"} or (
            len(stem) == 4 and stem[:3] in {"COM", "LPT"} and stem[3] in "123456789¹²³"
        ):
            raise ValueError("请使用普通文件名。")
        parts.append(part)
    return "/".join(parts) or "."


def _check_cancel(cancelled: threading.Event) -> None:
    if cancelled.is_set():
        raise InterruptedError("文件操作已取消。")


def _replace_file(path: Path, content: bytes, cancelled: threading.Event,
                  *, mode: int = PRIVATE_FILE_MODE) -> None:
    descriptor, temporary = tempfile.mkstemp(prefix=".file-change-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as file:
            file.write(content)
            file.flush()
            os.fsync(file.fileno())
            os.fchmod(file.fileno(), mode)
        _check_cancel(cancelled)
        check_path(path)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


async def run_file_operation[T](operation: Callable[[threading.Event], T]) -> T:
    cancelled = threading.Event()
    result = asyncio.gather(asyncio.to_thread(operation, cancelled), return_exceptions=True)
    interrupted = False
    while not result.done():
        try:
            await asyncio.shield(result)
        except asyncio.CancelledError:
            interrupted = True
            cancelled.set()
    if interrupted:
        raise asyncio.CancelledError
    outcome = result.result()[0]
    if isinstance(outcome, BaseException):
        raise outcome
    return outcome


class _WorkspaceReads(FileSystemToolset):
    """普通文件节点的读取工具集。

    @author hanbing
    @since 2026-10-17
    """

    def _resolve_walk_entry(self, entry: Path) -> Path | None:
        try:
            check_path(entry, directory=entry.is_dir())
        except (OSError, ValueError):
            return None
        return super()._resolve_walk_entry(entry)


class WorkspaceFiles:
    """按资源策略执行文件操作的文件系统。

    @author hanbing
    @since 2026-10-17
    """

    def __init__(self, resource: WorkspaceResource) -> None:
        self.resource = resource
        allowed = (*resource.allowed_files, ".") if resource.allowed_files else ()
        options = FileSystem(root_dir=resource.root, allowed_patterns=allowed)
        self._native = _WorkspaceReads(
            root_dir=resource.root, allowed_patterns=options.allowed_patterns,
            denied_patterns=options.denied_patterns, protected_patterns=options.protected_patterns,
            max_read_lines=options.max_read_lines, max_list_results=options.max_list_results,
            max_search_results=options.max_search_results, max_find_results=options.max_find_results,
        )

    def target(self, path: str, *, directory: bool = False, write: bool = False) -> Path:
        relative = _relative_path(path)
        if self.resource.allowed_files and relative != "." and relative not in self.resource.allowed_files:
            raise ValueError("请选择此工作区内的 declarative.md、procedural.md 或 conditional.md。")
        target = self.resource.root / relative
        check_path(target, directory=directory)
        getattr(self._native, "_check_access")(relative, write=write, check_allowed=relative != ".")
        return target

    async def read(self, operation: str, path: str, **args: Any) -> str:
        directory = operation in {"list_directory", "find_files"} or (
            operation in {"search_files", "file_info"} and (self.resource.root / _relative_path(path)).is_dir()
        )
        self.target(path, directory=directory)
        return await getattr(self._native, operation)(path=path, **args)

    def change(
        self, operation: str, path: str, args: dict[str, Any],
        guard: StorageGuard | None, cancelled: threading.Event,
    ) -> Any:
        context = guard.change(self.resource) if guard is not None else nullcontext(_UnmeteredChange())
        with context as change:
            _check_cancel(cancelled)
            if operation == "create_directory":
                return self._create_directory(path, cancelled, change)
            if operation == "delete_path":
                return self._delete(path, cancelled, change)
            target = self.target(path, write=True)
            if target == self.resource.root:
                raise ValueError("请选择工作区内的具体文件。")
            try:
                old_bytes = target.read_bytes()
                exists = True
            except FileNotFoundError:
                old_bytes = b""
                exists = False
            old = old_bytes.decode("utf-8")
            expected = args.get("expected_hash")
            if exists and expected is None:
                raise ValueError("请先读取文件，并提供读取所得的 expected_hash。")
            if expected is not None and expected != content_hash(old):
                raise ValueError("文件版本已有更新，请重新读取后使用最新 expected_hash。")
            if operation == "edit_file":
                if not exists:
                    raise FileNotFoundError("目标文件尚未创建，请先使用 write_file。")
                previous = args["old_text"]
                if not previous or old.count(previous) != 1:
                    raise ValueError("old_text 需要在文件中恰好出现一次。")
                content = old.replace(previous, args["new_text"], 1)
            else:
                content = args["content"]
            content = content.replace("\r\n", "\n").replace("\r", "\n")
            encoded = content.encode("utf-8")
            change.check_write()
            _check_cancel(cancelled)
            change.prepare()
            check_path(target.parent, directory=True)
            modes = public_modes(self.resource, target)
            file_mode, directory_mode = modes or (PRIVATE_FILE_MODE, PRIVATE_DIRECTORY_MODE)
            target.parent.mkdir(parents=True, exist_ok=True, mode=directory_mode)
            check_path(target.parent, directory=True)
            if modes is not None:
                grant_public_read(target.parent, directory=True, parents=self.resource.root)
            _replace_file(target, encoded, cancelled, mode=file_mode)
            change.record(_relative_path(path), len(old_bytes), len(encoded))
            return f"已更新 {path}，{len(encoded)} 字节。[hash:{content_hash(content)}]"

    def _create_directory(self, path: str, cancelled: threading.Event, change: StorageChange) -> str:
        if self.resource.allowed_files and _relative_path(path) != ".":
            raise ValueError("此工作区的文件直接存放于根目录。")
        target = self.target(path, directory=True, write=True)
        change.check_write()
        _check_cancel(cancelled)
        change.prepare()
        modes = public_modes(self.resource, target)
        _, directory_mode = modes or (PRIVATE_FILE_MODE, PRIVATE_DIRECTORY_MODE)
        target.mkdir(parents=True, exist_ok=True, mode=directory_mode)
        check_path(target, directory=True)
        if modes is not None:
            grant_public_read(target, directory=True, parents=self.resource.root)
        return f"已创建目录：{path}"

    def _delete(self, path: str, cancelled: threading.Event, change: StorageChange) -> dict[str, Any]:
        relative = _relative_path(path)
        if relative == ".":
            raise ValueError("请选择工作区内的文件或子目录。")
        target = self.resource.root / relative
        if self.resource.allowed_files and relative not in self.resource.allowed_files:
            raise ValueError("请选择此工作区内的知识文件。")
        pending = [target]
        entries = []
        while pending:
            _check_cancel(cancelled)
            node = pending.pop()
            try:
                info = node.lstat()
            except FileNotFoundError:
                if node == target:
                    self.target(relative, write=True)
                    return {"status": "not_found", "path": path, "deleted_entries": 0, "deleted_bytes": 0}
                raise
            self.target(str(node.relative_to(self.resource.root)), directory=stat.S_ISDIR(info.st_mode), write=True)
            entries.append((node, info))
            if stat.S_ISDIR(info.st_mode):
                pending.extend(node.iterdir())
        deleted = 0
        released = 0
        try:
            for node, expected in reversed(entries):
                _check_cancel(cancelled)
                self.target(str(node.relative_to(self.resource.root)), directory=stat.S_ISDIR(expected.st_mode), write=True)
                current = node.lstat()
                if (current.st_dev, current.st_ino, current.st_mode) != (expected.st_dev, expected.st_ino, expected.st_mode):
                    raise ValueError("文件节点已变化，请重新查看目录后重试。")
                change.prepare()
                if stat.S_ISDIR(current.st_mode):
                    node.rmdir()
                else:
                    node.unlink()
                    change.record(node.relative_to(self.resource.root).as_posix(), current.st_size, None)
                    released += current.st_size
                deleted += 1
        except (OSError, ValueError) as error:
            if not deleted:
                raise
            return {"status": "partial", "path": path, "deleted_entries": deleted,
                    "deleted_bytes": released, "error": str(error)}
        return {"status": "deleted", "path": path, "deleted_entries": deleted, "deleted_bytes": released}
