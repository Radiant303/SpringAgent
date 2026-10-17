"""云端文件工作区的存储准入。"""

from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from spring_harness.cloud.db import FileRow, SessionRow, SystemSettingRow, UserRow, WorkRow, get_sessionmaker
from spring_harness.core.filesystem import directory_size_bytes, resource_size_bytes
from spring_harness.core.workspace import FileAccessContext, FileScope, WorkspaceResource


@contextmanager
def _storage_transaction() -> Iterator[Session]:
    try:
        with get_sessionmaker().begin() as session:
            yield session
    except SQLAlchemyError as error:
        raise ValueError("存储协调服务暂不可用，请读取目标文件核对本次操作结果后再重试。") from error


@dataclass(frozen=True)
class CloudStorageGuard:
    """绑定用户资源的文件变更配额锁。

    @author hanbing
    @since 2026-10-17
    """

    access: FileAccessContext

    @contextmanager
    def change(self, resource: WorkspaceResource) -> Iterator[Callable[[int], None]]:
        if resource != self.access.resource(resource.scope):
            raise ValueError("文件资源与当前会话绑定不一致。")
        with _storage_transaction() as session:
            user = session.scalar(select(UserRow).where(UserRow.id == self.access.user_id).with_for_update())
            if user is None or user.status != "active":
                raise ValueError("文件操作需要状态正常的用户账号。")
            work = session.scalar(select(WorkRow).where(WorkRow.id == self.access.work_id).with_for_update())
            if work is None or work.user_id != user.id:
                raise ValueError("项目文件资源的归属已变化，请重新打开会话。")

            def check_growth(growth: int) -> None:
                if growth <= 0:
                    return
                works = session.scalars(select(WorkRow).where(WorkRow.user_id == user.id).with_for_update()).all()
                local = {row.id: directory_size_bytes(self.access.data_root / "works" / row.id) for row in works}
                sessions = session.execute(select(SessionRow.id, SessionRow.work_id).where(
                    SessionRow.work_id.in_([row.id for row in works]),
                ).with_for_update()).all()
                session_works = {session_id: work_id for session_id, work_id in sessions}
                remote = {row.id: 0 for row in works}
                records = session.execute(select(FileRow.session_id, FileRow.size).where(
                    FileRow.user_id == user.id, FileRow.storage_type != "local",
                    FileRow.session_id.in_(session_works),
                ).with_for_update()).all()
                for session_id, size in records:
                    remote[session_works[session_id]] += size
                work_bytes = {key: local[key] + remote[key] for key in local}
                knowledge = resource_size_bytes(self.access.resource(FileScope.KNOWLEDGE))
                total = max(user.used_bytes, sum(work_bytes.values())) + knowledge
                if total + growth > user.quota_bytes:
                    raise ValueError("用户存储空间不足，请清理文件或缩小内容。")
                if resource.scope == FileScope.WORKSPACE:
                    if user.work_quota_bytes is None:
                        configured = session.scalar(select(SystemSettingRow.setting_value).where(
                            SystemSettingRow.setting_key == "work_max_bytes",
                        ))
                        maximum = int(configured) if configured is not None else 20 * 1024 * 1024
                    else:
                        maximum = user.work_quota_bytes
                    if maximum > 0 and work_bytes[work.id] + growth > maximum:
                        raise ValueError("项目工作区存储空间不足，请清理文件或缩小内容。")

            yield check_growth
