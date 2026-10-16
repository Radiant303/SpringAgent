import asyncio
import time
from collections.abc import Awaitable, Callable
from typing import Any

from pydantic import BaseModel, ValidationError
from pydantic_ai.messages import ModelMessagesTypeAdapter

from spring_harness.capabilities.planning import load_plan_items
from spring_harness.core.log import logger
from spring_harness.core.rpc.connection import (
    INVALID_PARAMS,
    METHOD_NOT_FOUND,
    JsonRpcConnection,
    JsonRpcError,
)
from spring_harness.core.rpc.schema import (
    ApprovalRequestParams,
    ApprovalResult,
    HistoryQueryParams,
    HistoryResult,
    PlanResult,
    QuestionRequestParams,
    QuestionResult,
    SessionParams,
)
from spring_harness.core.session import HarnessSession
from spring_harness.core.stream.events import (
    ApprovalRequest,
    QuestionRequest,
    ServerEvent,
)

PROTOCOL_VERSION = 1

BUSY = -32001
SESSION_NOT_FOUND = -32002


class AppServer:
    def __init__(self, connection: JsonRpcConnection) -> None:
        self._connection = connection
        self._sessions: dict[str, HarnessSession] = {}
        self._tasks: set[asyncio.Task] = set()
        self._shutting_down = False
        # 在途审批/提问：request_id -> (session_id, 种类)。连接断开时用于默认拒绝
        self._inflight_requests: dict[str, tuple[str, str]] = {}
        # 方法名 → (参数模型, 处理函数)；None 表示无参数
        self._routes: dict[str, tuple[type[BaseModel] | None, Callable[[Any], Awaitable[Any]]]] = {
            "session/history": (HistoryQueryParams, self._session_history),
            "session/plan": (SessionParams, self._session_plan),
        }

    # ---- 客户端请求 ----

    async def handle_request(self, method: str, params: dict) -> Any:
        route = self._routes.get(method)
        if route is None:
            raise JsonRpcError(METHOD_NOT_FOUND, f"未知方法: {method}")
        params_type, handler = route
        try:
            parsed = params_type.model_validate(params) if params_type is not None else None
        except ValidationError as e:
            raise JsonRpcError(INVALID_PARAMS, f"参数不合法: {method}") from e
        result = await handler(parsed)
        if isinstance(result, BaseModel):
            return result.model_dump(by_alias=True)
        return {}

    async def handle_notification(self, method: str, params: dict) -> None:
        logger.debug("忽略未知通知: {} {}", method, params)

    # ---- 方法实现 ----

    def _apply_model(self, session: HarnessSession, model: str | None, effort: str | None = None) -> None:
        if model is None and effort is None:
            return
        if session.busy:
            raise JsonRpcError(BUSY, "运行中不能切换模型或思考挡位")
        try:
            session.set_model(model or session.model_selection["model"], effort)
        except ValueError as e:
            raise JsonRpcError(INVALID_PARAMS, str(e)) from e

    async def _session_history(self, p: HistoryQueryParams) -> HistoryResult:
        started = time.perf_counter()
        try:
            page = await self._require(p.session_id).query_history(p.cursor, p.limit, p.direction)
        except ValueError as e:
            raise JsonRpcError(INVALID_PARAMS, str(e)) from e
        logger.debug("session history query completed in {:.3f}s", time.perf_counter() - started)
        return HistoryResult(
            segments=[ModelMessagesTypeAdapter.dump_python(segment, mode="json") for segment in page.segments],
            first_segment_index=page.first_segment_index,
            next_cursor=page.next_cursor,
            previous_cursor=page.previous_cursor,
            has_more=page.has_more,
        )

    async def _session_plan(self, p: SessionParams) -> PlanResult:
        # 只读已挂载的会话：归属校验由 attach 负责，这里不能绕过
        session = self._require(p.session_id)
        items = await load_plan_items(session.session_id)
        return PlanResult(items=[i.model_dump() for i in items])

    async def post_session_request(self, session: HarnessSession, event: ServerEvent) -> None:
        """转发会话的审批与提问。"""
        if isinstance(event, ApprovalRequest):
            await self._post_approval(session, event)
        elif isinstance(event, QuestionRequest):
            await self._post_question(session, event)

    def _track(self, task: asyncio.Task) -> asyncio.Task:
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    async def _post_approval(self, session: HarnessSession, event) -> None:
        if self._shutting_down or self._sessions.get(session.session_id) is not session:
            session.respond(event.request_id, False)
            return
        await self._connection.post_request(
            "approval/request",
            ApprovalRequestParams(
                session_id=session.session_id,
                tool_call_id=event.tool_call_id,
                tool_name=event.tool_name,
                args=event.args,
                diff=event.diff,
            ),
            rid=event.request_id,
        )
        if self._shutting_down or self._sessions.get(session.session_id) is not session:
            self._connection.discard_response(event.request_id)
            session.respond(event.request_id, False)
            return
        self._inflight_requests[event.request_id] = (session.session_id, "approval")
        self._track(asyncio.create_task(self._await_approval(session, event.request_id)))

    async def _await_approval(self, session: HarnessSession, request_id: str) -> None:
        if request_id not in self._inflight_requests:
            return
        try:
            result = await self._connection.wait_response(request_id)
            approved = ApprovalResult.model_validate(result or {}).approved
        except (JsonRpcError, ValidationError):
            approved = False
        finally:
            self._inflight_requests.pop(request_id, None)
        if not self._shutting_down:
            session.respond(request_id, approved)

    async def _post_question(self, session: HarnessSession, event) -> None:
        if self._shutting_down or self._sessions.get(session.session_id) is not session:
            session.respond(event.request_id, None)
            return
        await self._connection.post_request(
            "question/request",
            QuestionRequestParams(
                session_id=session.session_id,
                question=event.question,
                options=event.options,
                allow_custom=event.allow_custom,
            ),
            rid=event.request_id,
        )
        if self._shutting_down or self._sessions.get(session.session_id) is not session:
            self._connection.discard_response(event.request_id)
            session.respond(event.request_id, None)
            return
        self._inflight_requests[event.request_id] = (session.session_id, "question")
        self._track(asyncio.create_task(self._await_question(session, event.request_id)))

    async def _await_question(self, session: HarnessSession, request_id: str) -> None:
        if request_id not in self._inflight_requests:
            return
        try:
            result = await self._connection.wait_response(request_id)
            answer = QuestionResult.model_validate(result or {}).answer
        except (JsonRpcError, ValidationError):
            answer = None
        finally:
            self._inflight_requests.pop(request_id, None)
        if not self._shutting_down:
            session.respond(request_id, answer)

    def _require(self, session_id: str) -> HarnessSession:
        session = self._sessions.get(session_id)
        if session is None:
            raise JsonRpcError(SESSION_NOT_FOUND, f"未知会话: {session_id}")
        return session
