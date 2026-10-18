"""云端会话注册表。"""

from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import TYPE_CHECKING

from spring_harness.cloud.storage import CloudStorageGuard
from spring_harness.core.filesystem import check_path
from spring_harness.core.workspace import FileAccessContext
from spring_harness.core.agent.deps import CodingAgentDeps
from spring_harness.core.log import logger
from spring_harness.core.rpc.connection import JsonRpcError
from spring_harness.core.rpc.server import BUSY, SESSION_NOT_FOUND, AppServer
from spring_harness.core.session import HarnessSession
from spring_harness.core.stream.events import (
    ApprovalRequest,
    QuestionRequest,
    TurnFinished,
    TurnUsage,
)

if TYPE_CHECKING:
    import redis.asyncio as aioredis

    from spring_harness.core.store.base import SessionStore
    from spring_harness.core.stream.events import ServerEvent

STREAM_KEY_PREFIX = "stream:session:"
# 滑动过期：每条事件写入都续期，活跃会话的流不会中途消失
STREAM_TTL_SECONDS = 3600
# turn 结束后缩短保留期：续传窗口已过，历史有数据库兜底
STREAM_TTL_AFTER_FINISH = 600


def _usage_payload(usage: TurnUsage | None) -> list[dict]:
    """模型分组的生命周期用量。"""
    if usage is None:
        return []
    return [{
        "modelId": group.model_id,
        "requests": group.requests,
        "inputTokens": group.input_tokens,
        "cacheReadTokens": group.cache_read_tokens,
        "cacheWriteTokens": group.cache_write_tokens,
        "outputTokens": group.output_tokens,
    } for group in usage.usages]


class CloudSessionHandle:
    """一个云端会话的运行时：会话本体 + 事件扇出 + 审批路由。"""

    def __init__(
        self, session: HarnessSession, redis: aioredis.Redis | None, *, access: FileAccessContext,
    ) -> None:
        self.session = session
        self.file_access = access
        self.work_id = access.work_id
        self.user_id = access.user_id
        self._redis = redis
        self._approval_target: AppServer | None = None
        self._parked_requests: list[ApprovalRequest | QuestionRequest] = []
        self._tasks: set[asyncio.Task] = set()
        self._turn_pending = False
        self._cancel_pending = False
        self._pending_finishes = 0
        self._users = 0
        self._last_used = time.monotonic()
        self._closed = False
        self._fanout_active = False
        # 最近见到该会话的用户 ID：lifecycle 事件归属的兜底
        self._last_user_id: int | None = None
        self._fanout_task = self._track(asyncio.create_task(self._fanout_loop()))

    @property
    def session_id(self) -> str:
        return self.session.session_id

    @property
    def approval_target(self) -> AppServer | None:
        return self._approval_target

    @property
    def last_used(self) -> float:
        return self._last_used

    @property
    def idle(self) -> bool:
        return not (self._closed or self.busy or self._users or self._pending_finishes
                    or self._fanout_active or any(
                        task is not self._fanout_task and not task.done() for task in self._tasks
                    ))

    def touch(self) -> None:
        self._last_used = time.monotonic()

    @contextmanager
    def use(self) -> Iterator[None]:
        if self._closed:
            raise JsonRpcError(SESSION_NOT_FOUND, "会话已释放，请重新选择会话")
        self._users += 1
        self.touch()
        try:
            yield
        finally:
            self._users -= 1
            self.touch()

    # ---- 连接挂载/卸载 ----

    def set_approval_target(self, server: AppServer | None) -> None:
        self.touch()
        self._approval_target = server
        if server is not None and self._parked_requests:
            parked, self._parked_requests = self._parked_requests, []
            for event in parked:
                logger.info("补投断线期间暂存的审批/提问: session={} kind={}",
                            self.session_id, type(event).__name__)
                self._track(asyncio.create_task(self._route_request(event)))

    def remember_user(self, user_id: int | None) -> None:
        """校验会话所属用户。"""
        if type(user_id) is not int or user_id != self.user_id:
            raise JsonRpcError(SESSION_NOT_FOUND, "未知会话")
        self._last_user_id = self.user_id

    @property
    def busy(self) -> bool:
        return self.session.busy or self._turn_pending

    def start_turn(
        self, text: str, *, turn_id: str | None = None, user_id: int | None = None,
    ) -> None:
        if self._closed:
            raise JsonRpcError(SESSION_NOT_FOUND, "会话已释放，请重新选择会话")
        if self.busy:
            raise JsonRpcError(BUSY, "上一轮还没结束")
        self.remember_user(user_id)
        self.touch()
        self._turn_pending = True
        self._pending_finishes += 1
        self._cancel_pending = False
        self._track(asyncio.create_task(self._run(text, turn_id=turn_id, user_id=user_id)))

    def cancel_turn(self) -> None:
        self.touch()
        if self._turn_pending and not self.session.busy:
            self._cancel_pending = True
        self.session.cancel()

    async def _run(self, text: str, *, turn_id: str | None = None, user_id: int | None = None) -> None:
        try:
            if self._cancel_pending:
                event = TurnFinished(cancelled=True, turn_id=turn_id, user_id=user_id)
                try:
                    await self._write_stream(event)
                    await self._publish_lifecycle(event)
                finally:
                    self._pending_finishes = max(0, self._pending_finishes - 1)
                return
            await self.session.run_turn(text, turn_id=turn_id, user_id=user_id)
        except Exception:
            self._pending_finishes = max(0, self._pending_finishes - 1)
            logger.exception("会话 {} 的轮次异常", self.session_id)
        finally:
            self._turn_pending = False
            if self._fanout_task.done():
                self._pending_finishes = 0
            self.touch()

    # ---- 事件扇出 ----

    def _track(self, task: asyncio.Task) -> asyncio.Task:
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    async def _fanout_loop(self) -> None:
        try:
            async for event in self.session.events():
                self._fanout_active = True
                try:
                    if isinstance(event, (ApprovalRequest, QuestionRequest)):
                        await self._route_request(event)
                    else:
                        try:
                            await self._write_stream(event)
                        finally:
                            if isinstance(event, TurnFinished):
                                await self._publish_lifecycle(event)
                except Exception:
                    logger.exception("会话 {} 的事件处理失败", self.session_id)
                finally:
                    if isinstance(event, TurnFinished):
                        self._pending_finishes = max(0, self._pending_finishes - 1)
                        self._parked_requests.clear()
                    self._fanout_active = False
                    self.touch()
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("会话 {} 的事件扇出异常退出", self.session_id)
        finally:
            self._pending_finishes = 0
            self._fanout_active = False
            self._parked_requests.clear()
            self.touch()

    async def _route_request(self, event: ApprovalRequest | QuestionRequest) -> None:
        target = self._approval_target
        if target is None:
            self._parked_requests.append(event)
            logger.info("会话 {} 无在线连接，审批/提问暂存: {}", self.session_id, type(event).__name__)
            return
        try:
            await target.post_session_request(self.session, event)
        except Exception:
            self.session.respond(event.request_id, False if isinstance(event, ApprovalRequest) else None)
            logger.warning("会话审批连接已不可用: {}", self.session_id)

    async def _write_stream(self, event: ServerEvent) -> None:
        if self._redis is None:
            return
        key = STREAM_KEY_PREFIX + self.session_id
        try:
            await self._redis.xadd(key, {"event": json.dumps(event.model_dump(), ensure_ascii=False)})
            ttl = STREAM_TTL_AFTER_FINISH if isinstance(event, TurnFinished) else STREAM_TTL_SECONDS
            await self._redis.expire(key, ttl)
        except Exception:  # noqa: BLE001 流写入失败不阻断 turn；事件仍有数据库落库兜底
            logger.exception("事件写 Redis Stream 失败: session={}", self.session_id)

    async def _publish_lifecycle(self, event: TurnFinished) -> None:
        from spring_harness.cloud.mq import get_dispatcher  # 延迟导入避免循环依赖

        dispatcher = get_dispatcher()
        if dispatcher is None:
            return
        if event.cancelled:
            status = "cancelled"
        elif event.error:
            status = "error"
        else:
            status = "finished"
        try:
            await dispatcher.publish_lifecycle({
                "turnId": event.turn_id,
                "sessionId": self.session_id,
                "userId": event.user_id if event.user_id is not None else self._last_user_id,
                "status": status,
                "error": event.error,
                "usage": _usage_payload(event.usage),
            })
        except Exception:  # noqa: BLE001 生命周期回传失败不影响事件流
            logger.exception("turn 生命周期事件发布失败: session={}", self.session_id)

    async def close(self) -> None:
        self._closed = True
        self._approval_target = None
        self._parked_requests.clear()
        tasks = tuple(self._tasks)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._tasks.clear()
        await self.session.close()


class CloudSessionRegistry:
    """进程级会话运行时缓存。

    @author hanbing
    @since 2026-10-18
    """

    def __init__(
        self, redis: aioredis.Redis | None, *, idle_ttl: float | None = None,
        max_idle_handles: int | None = None, sweep_interval: float | None = None,
    ) -> None:
        from spring_harness.core.config.settings import config

        idle_ttl = config.cloud.session_idle_ttl_seconds if idle_ttl is None else idle_ttl
        max_idle_handles = (config.cloud.session_max_idle_handles
                            if max_idle_handles is None else max_idle_handles)
        sweep_interval = (config.cloud.session_sweep_interval_seconds
                          if sweep_interval is None else sweep_interval)
        if not 0 < idle_ttl < float("inf") or max_idle_handles < 1 \
                or not 0 < sweep_interval < float("inf"):
            raise ValueError("会话空闲超时和回收间隔必须为有限正数，缓存容量必须为正数")
        self._redis = redis
        self._idle_ttl = idle_ttl
        self._max_idle_handles = max_idle_handles
        self._sweep_interval = sweep_interval
        self._handles: dict[str, CloudSessionHandle] = {}
        self._deletions: dict[str, tuple[int, str, AppServer | None]] = {}
        self._create_lock = asyncio.Lock()
        self._sweep_task: asyncio.Task | None = None
        self._cleanup_tasks: set[asyncio.Task] = set()
        self._closed = False

    def start(self) -> None:
        if self._closed:
            raise RuntimeError("会话注册表已关闭")
        if self._sweep_task is None:
            self._sweep_task = asyncio.create_task(self._sweep_loop())

    async def _sweep_loop(self) -> None:
        while True:
            await asyncio.sleep(self._sweep_interval)
            try:
                await self.evict_idle()
            except Exception:
                logger.exception("空闲会话回收失败")

    async def evict_idle(self) -> int:
        async with self._create_lock:
            retired = self._retire_idle_locked()
            cleanup = self._start_cleanup(retired)
        if cleanup is not None:
            await asyncio.shield(cleanup)
        return len(retired)

    def _retire_idle_locked(
        self, keep: CloudSessionHandle | None = None,
    ) -> list[tuple[CloudSessionHandle, AppServer | None]]:
        from spring_harness.cloud.ws import CloudAppServer

        now = time.monotonic()
        idle = sorted((handle for handle in self._handles.values()
                       if handle.idle and not (
                           isinstance(handle.approval_target, CloudAppServer)
                           and handle.approval_target.selected_session_id == handle.session_id
                       )), key=lambda handle: handle.last_used)
        expired = {handle for handle in idle
                   if handle is not keep and now - handle.last_used >= self._idle_ttl}
        excess = max(0, len(idle) - len(expired) - self._max_idle_handles)
        for handle in idle:
            if not excess:
                break
            if handle is not keep and handle not in expired:
                expired.add(handle)
                excess -= 1
        retired = []
        for handle in idle:
            if handle not in expired:
                continue
            owner = CloudAppServer.unmount_session(handle.session_id)
            self._handles.pop(handle.session_id)
            retired.append((handle, owner))
        return retired

    def _start_cleanup(
        self, retired: list[tuple[CloudSessionHandle, AppServer | None]],
    ) -> asyncio.Task | None:
        if not retired:
            return None
        task = asyncio.create_task(self._release_retired(retired))
        self._cleanup_tasks.add(task)
        task.add_done_callback(self._cleanup_tasks.discard)
        return task

    async def _release_retired(
        self, retired: list[tuple[CloudSessionHandle, AppServer | None]],
    ) -> None:
        async def release(handle: CloudSessionHandle, owner: AppServer | None) -> None:
            try:
                await handle.close()
            except Exception:
                logger.exception("空闲会话关闭失败: {}", handle.session_id)
            if owner is not None and handle.session_id not in owner._sessions:
                try:
                    await asyncio.wait_for(owner._connection.send_notification(
                        "session/invalidated", {"sessionId": handle.session_id, "deleted": False},
                    ), timeout=5)
                except Exception:
                    logger.debug("空闲会话释放通知发送失败: {}", handle.session_id)

        await asyncio.gather(*(release(handle, owner) for handle, owner in retired))
        logger.info("已回收空闲会话: {}", len(retired))

    def get(self, session_id: str) -> CloudSessionHandle | None:
        return self._handles.get(session_id)

    async def get_or_create(
        self, session_id: str, *, store: SessionStore, access: FileAccessContext,
        page_id: int | None = None,
    ) -> CloudSessionHandle:
        if session_id != store.session_id:
            raise JsonRpcError(SESSION_NOT_FOUND, "未知会话")
        async with self._create_lock:
            if self._closed:
                raise JsonRpcError(SESSION_NOT_FOUND, "会话服务已关闭")
            if session_id in self._deletions:
                raise JsonRpcError(BUSY, "会话正在删除，请稍后重试")
            from spring_harness.cloud.db import SessionRow, get_sessionmaker

            def exists() -> bool:
                with get_sessionmaker()() as database:
                    row = database.get(SessionRow, session_id)
                    return bool(row is not None and row.user_id == access.user_id
                                and row.work_id == access.work_id and row.status == "active")

            if not await asyncio.to_thread(exists):
                raise JsonRpcError(SESSION_NOT_FOUND, "未知会话")
            handle = self._handles.get(session_id)
            if handle is not None:
                if handle.file_access != access:
                    raise JsonRpcError(SESSION_NOT_FOUND, "未知会话")
                handle.touch()
                return handle
            check_path(access.workspace_root, directory=True)
            check_path(access.knowledge_root, directory=True)
            access.workspace_root.mkdir(parents=True, exist_ok=True)
            from spring_harness.cloud.billing import get_billing_client

            billing = get_billing_client()
            if billing is None:
                raise RuntimeError("云端计费服务未初始化")
            deps = CodingAgentDeps(
                workspace=access.workspace_root, user_id=access.user_id,
                file_access=access, storage_guard=CloudStorageGuard(access),
            )
            session = await asyncio.to_thread(
                HarnessSession, access.workspace_root, store=store, deps=deps,
                billing_service=billing, page_id=page_id,
            )
            handle = CloudSessionHandle(session, self._redis, access=access)
            self._handles[session_id] = handle
            self._start_cleanup(self._retire_idle_locked(keep=handle))
            logger.info("云端会话已注册: {}", session_id)
            return handle

    async def prepare_deletion(self, session_id: str, user_id: int, token: str) -> None:
        from spring_harness.capabilities.planning import delete_plan
        from spring_harness.cloud.ws import CloudAppServer

        async with self._create_lock:
            existing = self._deletions.get(session_id)
            if existing is not None and existing[0] != user_id:
                raise JsonRpcError(SESSION_NOT_FOUND, "未知会话")
            handle = self._handles.get(session_id)
            if handle is not None:
                if handle.user_id != user_id:
                    raise JsonRpcError(SESSION_NOT_FOUND, "未知会话")
                if handle.busy:
                    raise JsonRpcError(BUSY, "会话正在运行，请结束当前任务后删除")
                if not handle.idle:
                    raise JsonRpcError(BUSY, "会话操作尚未完成，请稍后重试删除")
                workspace = handle.file_access.workspace_root
            else:
                workspace = await asyncio.to_thread(self._workspace_for, session_id, user_id)
            owner = existing[2] if existing is not None else CloudAppServer.unmount_session(session_id)
            self._deletions[session_id] = (user_id, token, owner)
            self._handles.pop(session_id, None)
            if handle is not None:
                await handle.close()
            await delete_plan(session_id, root=workspace)
            if self._redis is not None:
                await self._redis.delete(STREAM_KEY_PREFIX + session_id)

    @staticmethod
    def _workspace_for(session_id: str, user_id: int) -> Path:
        from spring_harness.cloud.db import SessionRow, get_sessionmaker
        from spring_harness.core.config.settings import config

        with get_sessionmaker()() as database:
            row = database.get(SessionRow, session_id)
        if row is None or row.user_id != user_id:
            raise JsonRpcError(SESSION_NOT_FOUND, "未知会话")
        return Path(config.cloud.data_root).expanduser() / "works" / row.work_id

    async def finish_deletion(self, session_id: str, user_id: int, token: str, committed: bool) -> None:
        async with self._create_lock:
            existing = self._deletions.get(session_id)
            if existing is None or existing[:2] != (user_id, token):
                return
            self._deletions.pop(session_id)
            owner = existing[2]
        if owner is not None:
            try:
                await owner._connection.send_notification("session/invalidated", {
                    "sessionId": session_id, "deleted": committed,
                })
            except Exception:
                logger.debug("会话释放通知发送失败: {}", session_id)

    def active_count_in_work(self, work_id: str) -> int:
        """统计项目内已挂载或仍在使用的会话数。"""
        return sum(
            1 for handle in self._handles.values()
            if handle.work_id == work_id
            and (handle.approval_target is not None or not handle.idle)
        )

    async def close_all(self) -> None:
        from spring_harness.cloud.ws import CloudAppServer

        self._closed = True
        if self._sweep_task is not None:
            self._sweep_task.cancel()
            await asyncio.gather(self._sweep_task, return_exceptions=True)
            self._sweep_task = None
        async with self._create_lock:
            handles = list(self._handles.values())
            for handle in handles:
                CloudAppServer.unmount_session(handle.session_id)
            self._handles.clear()
            self._start_cleanup([(handle, None) for handle in handles])
            pending = tuple(self._cleanup_tasks)
        if pending:
            await asyncio.shield(asyncio.gather(*pending, return_exceptions=True))


_registry: CloudSessionRegistry | None = None


def set_registry(registry: CloudSessionRegistry | None) -> None:
    global _registry
    _registry = registry


def get_registry() -> CloudSessionRegistry | None:
    return _registry
