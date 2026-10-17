"""工作区文件和目录删除工具。"""

import os
import stat
from dataclasses import dataclass
from pathlib import Path, PureWindowsPath

from pydantic_ai import FunctionToolset


@dataclass(frozen=True)
class _Entry:
    """待删除节点。

    @author hanbing
    @since 2026-10-17
    """

    path: Path
    device: int
    inode: int
    mode: int


def _checked_stat(root: Path, target: Path) -> os.stat_result:
    resolved = target.resolve(strict=True)
    if resolved == root or not resolved.is_relative_to(root):
        raise ValueError("拒绝删除：目标解析后位于工作区之外或是工作区根目录。")
    relative = target.relative_to(root)
    current = root
    for part in ("", *relative.parts):
        current = current / part
        info = current.lstat()
        if stat.S_ISLNK(info.st_mode) or (
            getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT
        ):
            raise ValueError("拒绝删除：路径中包含符号链接、junction 或其他重解析点。")
        if current != target and not stat.S_ISDIR(info.st_mode):
            raise ValueError("拒绝删除：路径的父节点不是目录。")
    if not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
        raise ValueError("拒绝删除：仅支持普通文件和目录。")
    return info


def _relative_target(root: Path, path: str) -> Path:
    if not path or not path.strip():
        raise ValueError("拒绝删除：路径不能为空，请提供相对于工作区的路径。")
    windows = PureWindowsPath(path)
    if Path(path).is_absolute() or windows.drive or windows.root:
        raise ValueError("拒绝删除：只接受工作区相对路径，不接受绝对路径、盘符或 UNC 路径。")
    parts = path.replace("\\", "/").split("/")
    for part in parts:
        if part in ("", "."):
            continue
        if part == "..":
            raise ValueError("拒绝删除：路径不能包含 ..，请使用工作区内的直接相对路径。")
        if part.endswith((".", " ")) or any(
            ord(char) < 32 or char in '<>:"|?*' for char in part
        ):
            raise ValueError("拒绝删除：路径包含非法字符、通配符、数据流语法或结尾空格/点。")
        stem = part.split(".", 1)[0].upper()
        if stem in {"CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"} or (
            len(stem) == 4 and stem[:3] in {"COM", "LPT"} and stem[3] in "123456789¹²³"
        ):
            raise ValueError("拒绝删除：路径包含保留设备名称。")
    target = root.joinpath(*parts)
    if target == root:
        raise ValueError("拒绝删除：不能删除工作区根目录本身。")
    return target


def _plan(root: Path, target: Path) -> list[_Entry]:
    pending = [target]
    entries = []
    while pending:
        node = pending.pop()
        info = _checked_stat(root, node)
        entries.append(_Entry(node, info.st_dev, info.st_ino, info.st_mode))
        if stat.S_ISDIR(info.st_mode):
            with os.scandir(node) as children:
                pending.extend(Path(child.path) for child in children)
    return list(reversed(entries))


def workspace_delete_toolset(root_dir: str) -> FunctionToolset:
    """构造工作区删除工具集。"""
    root = Path(root_dir).expanduser().resolve()

    def delete_path(path: str) -> dict[str, str | int]:
        """永久删除当前项目工作区中的普通文件或目录，目录会递归删除。

        使用工作区相对路径，例如 page/old.html 或 build。
        目标及其子项须为工作区内的普通文件或目录。

        Args:
            path: 待删除文件或目录的工作区相对路径。
        """
        try:
            target = _relative_target(root, path)
            entries = _plan(root, target)
        except FileNotFoundError:
            return {"status": "not_found", "message": "目标或路径中的节点不存在，未执行删除。请重新查看目录。"}
        except (ValueError, RuntimeError) as error:
            return {"status": "refused", "message": str(error)}
        except OSError:
            return {"status": "refused", "message": "无法完整校验目标，未执行删除。请检查路径和权限。"}

        deleted = 0
        try:
            for entry in entries:
                info = _checked_stat(root, entry.path)
                if (info.st_dev, info.st_ino, info.st_mode) != (entry.device, entry.inode, entry.mode):
                    raise ValueError("目标在校验后发生变化。")
                if stat.S_ISDIR(info.st_mode):
                    entry.path.rmdir()
                else:
                    entry.path.unlink()
                deleted += 1
        except (OSError, ValueError, RuntimeError):
            return {
                "status": "error", "deleted_entries": deleted,
                "message": "删除已停止：路径发生变化或权限/文件占用阻止操作。可能已删除部分内容，请重新查看目录后重试。",
            }
        return {
            "status": "deleted", "path": path, "deleted_entries": deleted,
            "message": "删除成功",
        }

    toolset = FunctionToolset()
    toolset.add_function(delete_path, sequential=True)
    return toolset
