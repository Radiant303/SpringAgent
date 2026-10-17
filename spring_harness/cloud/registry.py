"""云端会话注册表。"""

from __future__ import annotations

import asyncio
import json
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
    from pathlib import Path

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
        # 最近见到该会话的用户 ID：lifecycle 事件归属的兜底
        self._last_user_id: int | None = None
        self._fanout_task = self._track(asyncio.create_task(self._fanout_loop()))

    @property
    def session_id(self) -> str:
        return self.session.session_id

    @property
    def approval_target(self) -> AppServer | None:
        return self._approval_target

    # ---- 连接挂载/卸载 ----

    def set_approval_target(self, server: AppServer | None) -> None:
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
        if self.busy:
            raise JsonRpcError(BUSY, "上一轮还没结束")
        self.remember_user(user_id)
        self._turn_pending = True
        self._cancel_pending = False
        self._track(asyncio.create_task(self._run(text, turn_id=turn_id, user_id=user_id)))

    def cancel_turn(self) -> None:
        if self._turn_pending and not self.session.busy:
            self._cancel_pending = True
        self.session.cancel()

    async def _run(self, text: str, *, turn_id: str | None = None, user_id: int | None = None) -> None:
        try:
            if self._cancel_pending:
                event = TurnFinished(cancelled=True, turn_id=turn_id, user_id=user_id)
                await self._write_stream(event)
                await self._publish_lifecycle(event)
                return
            await self.session.run_turn(text, turn_id=turn_id, user_id=user_id)
        except Exception:
            logger.exception("会话 {} 的轮次异常", self.session_id)
        finally:
            self._turn_pending = False

    # ---- 事件扇出 ----

    def _track(self, task: asyncio.Task) -> asyncio.Task:
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    async def _fanout_loop(self) -> None:
        try:
            async for event in self.session.events():
                if isinstance(event, (ApprovalRequest, QuestionRequest)):
                    await self._route_request(event)
                else:
                    await self._write_stream(event)
                    if isinstance(event, TurnFinished):
                        await self._publish_lifecycle(event)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 扇出是长驻任务：任何意外只记日志，不让任务静默死掉
            logger.exception("会话 {} 的事件扇出异常退出", self.session_id)

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

    # ---- 关闭（进程退出时调用） ----

    async def close(self) -> None:
        self._fanout_task.cancel()
        for task in tuple(self._tasks):
            task.cancel()
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)
        await self.session.close()


class CloudSessionRegistry:
    """进程级会话表：session_id → CloudSessionHandle。"""

    def __init__(self, redis: aioredis.Redis | None) -> None:
        self._redis = redis
        self._handles: dict[str, CloudSessionHandle] = {}
        self._create_lock = asyncio.Lock()

    def get(self, session_id: str) -> CloudSessionHandle | None:
        return self._handles.get(session_id)

    async def get_or_create(
        self, session_id: str, *, store: SessionStore, access: FileAccessContext,
        page_id: int | None = None,
    ) -> CloudSessionHandle:
        if session_id != store.session_id:
            raise JsonRpcError(SESSION_NOT_FOUND, "未知会话")
        async with self._create_lock:
            handle = self._handles.get(session_id)
            if handle is not None:
                if handle.file_access != access:
                    raise JsonRpcError(SESSION_NOT_FOUND, "未知会话")
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
            logger.info("云端会话已注册: {}", session_id)
            return handle

    def active_count_in_work(self, work_id: str) -> int:
        """进程内该 work 下"活跃"的会话数：有连接挂载或 turn 在跑即活跃，
        仅注册可续传的会话不算（否则删除入口会被永远堵死）。"""
        return sum(
            1 for handle in self._handles.values()
            if handle.work_id == work_id
            and (handle.approval_target is not None or handle.busy)
        )

    async def close_all(self) -> None:
        handles, self._handles = list(self._handles.values()), {}
        await asyncio.gather(*(h.close() for h in handles), return_exceptions=True)


_registry: CloudSessionRegistry | None = None


def set_registry(registry: CloudSessionRegistry | None) -> None:
    global _registry
    _registry = registry


def get_registry() -> CloudSessionRegistry | None:
    return _registry
