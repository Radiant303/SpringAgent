"""存储计量的持久变更标记。"""

import os
from pathlib import Path
from uuid import uuid4

from spring_agent.core.filesystem import check_path


def pending_root(data_root: Path, user_id: int) -> Path:
    root = data_root / "users" / str(user_id) / ".storage-pending"
    check_path(root, directory=True)
    return root


def pending_changes(data_root: Path, user_id: int) -> list[Path]:
    root = pending_root(data_root, user_id)
    try:
        entries = list(root.iterdir())
    except FileNotFoundError:
        return []
    for entry in entries:
        check_path(entry)
    return entries


def begin_change(data_root: Path, user_id: int) -> Path:
    root = pending_root(data_root, user_id)
    root.mkdir(parents=True, exist_ok=True)
    check_path(root, directory=True)
    marker = root / f"{uuid4()}.pending"
    with marker.open("xb") as file:
        file.write(b"pending\n")
        file.flush()
        os.fsync(file.fileno())
    return marker


def clear_changes(markers: list[Path]) -> None:
    for marker in markers:
        check_path(marker)
        marker.unlink(missing_ok=True)
