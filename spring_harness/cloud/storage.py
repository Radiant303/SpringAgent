"""云端文件工作区的增量计量。"""

from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
import shutil
import tempfile

from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from spring_harness.cloud.db import FileRow, SessionRow, SystemSettingRow, UserRow, WorkRow, get_sessionmaker
from spring_harness.core.config.settings import STATE_DIR
from spring_harness.core.filesystem import check_path, directory_size_bytes, resource_size_bytes, workspace_size_bytes
from spring_harness.core.storage_journal import begin_change, clear_changes, pending_changes
from spring_harness.core.workspace import FileAccessContext, FileScope, StorageChange, WorkspaceResource


@contextmanager
def _storage_transaction() -> Iterator[Session]:
    try:
        with get_sessionmaker().begin() as session:
            yield session
    except SQLAlchemyError as error:
        raise ValueError("存储协调服务暂不可用，请读取目标文件核对本次操作结果后再重试。") from error


def _path_bytes(path: Path) -> int:
    if path.is_dir():
        return directory_size_bytes(path)
    check_path(path)
    try:
        return path.stat().st_size
    except FileNotFoundError:
        return 0


def _ensure_current(session: Session, access: FileAccessContext, user: UserRow) -> list[Path]:
    pending = pending_changes(access.data_root, user.id)
    if user.storage_initialized and not pending:
        return []
    works = session.scalars(select(WorkRow).where(WorkRow.user_id == user.id).with_for_update()).all()
    session_works = dict(session.execute(select(SessionRow.id, SessionRow.work_id).where(
        SessionRow.user_id == user.id,
    )).tuples().all())
    remote = {work.id: 0 for work in works}
    for record in session.scalars(select(FileRow).where(FileRow.user_id == user.id)).all():
        if record.storage_type == "local":
            relative = Path(record.path)
            if relative.is_absolute() or ".." in relative.parts or relative.parts[:1] != ("works",):
                raise ValueError("上传文件需要有效的工作区相对路径。")
            path = access.data_root / relative
            check_path(path)
            try:
                record.size = path.stat().st_size
            except FileNotFoundError:
                session.delete(record)
        elif (work_id := session_works.get(record.session_id)) in remote:
            remote[work_id] += record.size
    total = 0
    for work in works:
        root = access.data_root / "works" / work.id
        size, internal = workspace_size_bytes(root, STATE_DIR)
        work.size_bytes = size + remote[work.id]
        work.internal_bytes = internal
        total += work.size_bytes
    user.knowledge_bytes = resource_size_bytes(access.resource(FileScope.KNOWLEDGE))
    user.used_bytes = total + user.knowledge_bytes
    user.storage_initialized = True
    session.flush()
    return pending


@dataclass
class _Change:
    """文件变更的配额与差值记录。

    @author hanbing
    @since 2026-10-17
    """

    session: Session
    access: FileAccessContext
    resource: WorkspaceResource
    user: UserRow
    work: WorkRow
    maximum: int
    marker: Path | None = None

    def check_growth(self, growth: int) -> None:
        if growth <= 0:
            return
        if self.user.used_bytes + growth > self.user.quota_bytes:
            raise ValueError("用户存储空间不足，请清理文件或缩小内容。")
        if self.resource.scope == FileScope.WORKSPACE and self.maximum > 0 \
                and self.work.size_bytes + growth > self.maximum:
            raise ValueError("项目工作区存储空间不足，请清理文件或缩小内容。")

    def prepare(self) -> None:
        if self.marker is None:
            self.marker = begin_change(self.access.data_root, self.user.id)

    def record(self, path: str, old_size: int, new_size: int | None) -> None:
        delta = (new_size or 0) - old_size
        self.user.used_bytes += delta
        if self.resource.scope == FileScope.KNOWLEDGE:
            self.user.knowledge_bytes += delta
            minimum = self.user.knowledge_bytes
        else:
            self.work.size_bytes += delta
            relative = Path(path)
            if relative.parts and Path(relative.parts[0]) == Path(STATE_DIR):
                self.work.internal_bytes += delta
            minimum = min(self.work.size_bytes, self.work.internal_bytes)
            key = (self.resource.root / relative).relative_to(self.access.data_root).as_posix()
            record = self.session.scalar(select(FileRow).where(
                FileRow.user_id == self.user.id, FileRow.storage_type == "local", FileRow.path == key,
            ))
            if record is not None:
                if new_size is None:
                    self.session.delete(record)
                else:
                    record.size = new_size
        if min(self.user.used_bytes, minimum) < 0:
            raise RuntimeError("存储计数需要重新核算，请重试本次操作。")


@dataclass(frozen=True)
class CloudStorageGuard:
    """绑定用户资源的存储变更协调器。

    @author hanbing
    @since 2026-10-17
    """

    access: FileAccessContext

    @contextmanager
    def change(self, resource: WorkspaceResource) -> Iterator[StorageChange]:
        if resource != self.access.resource(resource.scope):
            raise ValueError("文件资源与当前会话绑定不一致。")
        with _storage_transaction() as session:
            user, work = self._locked(session)
            pending = _ensure_current(session, self.access, user)
            if user.work_quota_bytes is None:
                configured = session.scalar(select(SystemSettingRow.setting_value).where(
                    SystemSettingRow.setting_key == "work_max_bytes",
                ))
                maximum = int(configured) if configured is not None else 20 * 1024 * 1024
            else:
                maximum = user.work_quota_bytes
            change = _Change(session, self.access, resource, user, work, maximum)
            yield change
            session.flush()
        clear_changes(pending + ([change.marker] if change.marker is not None else []))

    @contextmanager
    def internal_change(self, paths: tuple[str, ...]) -> Iterator[None]:
        if not paths or any(name not in {"teaching.db", "teaching", ".agent-scratch"} for name in paths):
            raise ValueError("请选择受管的内部文件资源。")
        root = self.access.workspace_root / STATE_DIR
        with self.change(self.access.resource(FileScope.WORKSPACE)) as change:
            with tempfile.TemporaryDirectory(prefix="spring-storage-") as temporary:
                backup = Path(temporary)
                old_size = 0
                for name in paths:
                    target = root / name
                    old_size += _path_bytes(target)
                    if target.is_dir():
                        shutil.copytree(target, backup / name)
                    elif target.exists():
                        shutil.copy2(target, backup / name)
                change.prepare()
                try:
                    yield
                    new_size = sum(_path_bytes(root / name) for name in paths)
                    change.check_growth(new_size - old_size)
                except BaseException:
                    for name in paths:
                        target = root / name
                        check_path(target, directory=target.is_dir())
                        if target.is_dir():
                            shutil.rmtree(target)
                        else:
                            target.unlink(missing_ok=True)
                        saved = backup / name
                        if saved.is_dir():
                            shutil.copytree(saved, target)
                        elif saved.exists():
                            shutil.copy2(saved, target)
                    raise
                change.record(f"{STATE_DIR}/internal", old_size, new_size)

    def _locked(self, session: Session) -> tuple[UserRow, WorkRow]:
        user = session.scalar(select(UserRow).where(UserRow.id == self.access.user_id).with_for_update())
        if user is None or user.status != "active":
            raise ValueError("文件操作需要状态正常的用户账号。")
        work = session.scalar(select(WorkRow).where(WorkRow.id == self.access.work_id).with_for_update())
        if work is None or work.user_id != user.id:
            raise ValueError("项目文件资源的归属已变化，请重新打开会话。")
        return user, work
