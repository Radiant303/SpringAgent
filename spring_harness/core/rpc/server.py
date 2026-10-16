import asyncio
import time
from collections.abc import Awaitable, Callable
from pathlib import Path
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
    AttachParams,
    HistoryQueryParams,
    HistoryResult,
    InitializeResult,
    PlanResult,
    QuestionRequestParams,
    QuestionResult,
    SessionEventParams,
    SessionListResult,
    SessionParams,
    SessionResult,
    SessionSummary,
    SetModelParams,
    TurnStartParams,
    WorkspaceParams,
)
from spring_harness.core.session import HarnessSession
from spring_harness.core.store.base import SessionStore
from spring_harness.core.store.jsonl import JsonlSessionStore
from spring_harness.core.stream.events import (
    ApprovalRequest,
    QuestionRequest,
    ServerEvent,
)

PROTOCOL_VERSION = 1

BUSY = -32001
SESSION_NOT_FOUND = -32002


class AppServer:
    def __init__(
        self,
        connection: JsonRpcConnection,
        *,
        session_factory: Any = None,  # 测试注入：Callable(workspace, store) -> HarnessSession
    ) -> None:
        self._connection = connection
        self._session_factory = session_factory or (
            lambda workspace, store=None: HarnessSession(workspace, store=store)
        )
        self._sessions: dict[str, HarnessSession] = {}
        self._pumps: dict[str, asyncio.Task] = {}
        self._tasks: set[asyncio.Task] = set()
        self._shutting_down = False
        # 在途审批/提问：request_id -> (session_id, 种类)。连接断开时用于默认拒绝
        self._inflight_requests: dict[str, tuple[str, str]] = {}
        # 方法名 → (参数模型, 处理函数)；None 表示无参数
        self._routes: dict[str, tuple[type[BaseModel] | None, Callable[[Any], Awaitable[Any]]]] = {
            "initialize": (None, self._initialize),
            "session/new": (WorkspaceParams, self._session_new),
            "session/resume_last": (WorkspaceParams, self._session_resume_last),
            "session/list": (WorkspaceParams, self._session_list),
            "session/attach": (AttachParams, self._session_attach),
            "session/status": (SessionParams, self._session_status),
            "session/history": (HistoryQueryParams, self._session_history),
            "session/set_model": (SetModelParams, self._session_set_model),
            "session/plan": (SessionParams, self._session_plan),
            "turn/start": (TurnStartParams, self._turn_start),
            "turn/cancel": (SessionParams, self._turn_cancel),
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

    async def _initialize(self, _: None) -> InitializeResult:
        return InitializeResult(server_name="spring-harness", protocol_version=PROTOCOL_VERSION)

    def _apply_model(self, session: HarnessSession, model: str | None, effort: str | None = None) -> None:
        if model is None and effort is None:
            return
        if session.busy:
            raise JsonRpcError(BUSY, "运行中不能切换模型或思考挡位")
        try:
            session.set_model(model or session.model_selection["model"], effort)
        except ValueError as e:
            raise JsonRpcError(INVALID_PARAMS, str(e)) from e

    async def _create_session(
        self, workspace: Path, store: SessionStore | None = None, model: str | None = None,
        effort: str | None = None,
    ) -> HarnessSession:
        if self._shutting_down:
            raise RuntimeError("服务器正在关闭")
        task = self._track(asyncio.create_task(self._build_session(workspace, store, model, effort)))
        try:
            return await asyncio.shield(task)
        except asyncio.CancelledError:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            raise

    async def _build_session(
        self, workspace: Path, store: SessionStore | None, model: str | None, effort: str | None,
    ) -> HarnessSession:
        factory = asyncio.create_task(asyncio.to_thread(self._session_factory, workspace, store))
        cancelled = False
        while not factory.done():
            try:
                await asyncio.shield(factory)
            except asyncio.CancelledError:
                cancelled = True
            except Exception:  # noqa: BLE001
                break
        if cancelled:
            if not factory.cancelled() and factory.exception() is None:
                await factory.result().close()
            raise asyncio.CancelledError
        session = factory.result()
        try:
            self._apply_model(session, model, effort)
            return self._register(session)
        except BaseException:
            await session.close()
            raise

    def _session_result(self, session: HarnessSession) -> SessionResult:
        return SessionResult(session_id=session.session_id, busy=session.busy,
                             pending_input=session.pending_input, **session.model_selection)

    async def _session_status(self, p: SessionParams) -> SessionResult:
        return self._session_result(self._require(p.session_id))

    async def _session_new(self, p: WorkspaceParams) -> SessionResult:
        session = await self._create_session(Path(p.workspace), model=p.model, effort=p.effort)
        return self._session_result(session)

    async def _session_resume_last(self, p: WorkspaceParams) -> SessionResult:
        workspace = Path(p.workspace).resolve()
        existing = next(
            (s for s in self._sessions.values() if s.workspace == workspace), None,
        )
        if existing is not None:
            if not existing.busy:
                self._apply_model(existing, p.model, p.effort)
            return self._session_result(existing)
        sessions = await asyncio.to_thread(JsonlSessionStore.list_sessions, workspace)
        if not sessions:
            return SessionResult(session_id=None)
        session = await self._create_session(workspace, sessions[-1][0], p.model, p.effort)
        return self._session_result(session)

    async def _session_list(self, p: WorkspaceParams) -> SessionListResult:
        workspace = Path(p.workspace).resolve()
        sessions = []
        for store, meta in await asyncio.to_thread(JsonlSessionStore.list_sessions, workspace):
            created_at = meta.get("created_at")
            assert isinstance(created_at, str)
            session = self._sessions.get(store.session_id)
            sessions.append(SessionSummary(
                session_id=store.session_id,
                title=meta.get("title"),
                created_at=created_at,
                updated_at=meta.get("updated_at"),
                busy=session.busy if session is not None else False,
            ))
        return SessionListResult(sessions=sessions)

    async def _session_attach(self, p: AttachParams) -> SessionResult:
        workspace = Path(p.workspace).resolve()
        existing = next(
            (s for s in self._sessions.values() if s.session_id == p.session_id), None,
        )
        if existing is not None:
            if not existing.busy:
                self._apply_model(existing, p.model, p.effort)
            return self._session_result(existing)
        for store, _meta in await asyncio.to_thread(JsonlSessionStore.list_sessions, workspace):
            if store.session_id == p.session_id:
                session = await self._create_session(workspace, store, p.model, p.effort)
                return self._session_result(session)
        raise JsonRpcError(SESSION_NOT_FOUND, f"未知会话: {p.session_id}")

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

    async def _session_set_model(self, p: SetModelParams) -> SessionResult:
        session = self._require(p.session_id)
        self._apply_model(session, p.model, p.effort)
        return self._session_result(session)

    async def _turn_start(self, p: TurnStartParams) -> None:
        session = self._require(p.session_id)
        if session.busy:
            raise JsonRpcError(BUSY, "上一轮还没结束")
        if self._shutting_down:
            raise RuntimeError("服务器正在关闭")
        self._track(asyncio.create_task(self._run_turn(session, p.input)))
        await asyncio.sleep(0)

    async def _turn_cancel(self, p: SessionParams) -> None:
        self._require(p.session_id).cancel()

    async def post_session_request(self, session: HarnessSession, event: ServerEvent) -> None:
        """把审批/提问事件经本连接发给客户端（云端会话注册表在挂载/补投时调用）。"""
        if isinstance(event, ApprovalRequest):
            await self._post_approval(session, event)
        elif isinstance(event, QuestionRequest):
            await self._post_question(session, event)

    # ---- 事件泵：一个会话一条，ServerEvent → 通知 / 服务器请求 ----

    def _track(self, task: asyncio.Task) -> asyncio.Task:
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    def _register(self, session: HarnessSession) -> HarnessSession:
        if self._shutting_down:
            raise RuntimeError("服务器正在关闭")
        pump = self._track(asyncio.create_task(self._pump(session)))
        self._sessions[session.session_id] = session
        self._pumps[session.session_id] = pump
        return session

    async def _pump(self, session: HarnessSession) -> None:
        try:
            async for event in session.events():
                if isinstance(event, ApprovalRequest):
                    await self._post_approval(session, event)
                elif isinstance(event, QuestionRequest):
                    await self._post_question(session, event)
                else:
                    await self._connection.send_notification(
                        "session/event",
                        SessionEventParams(session_id=session.session_id, event=event.model_dump()),
                    )
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 泵是长驻任务：任何意外只记日志，不让任务静默死掉
            logger.exception("会话 {} 的事件泵异常退出", session.session_id)

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

    # ---- 收尾 ----

    async def _run_turn(self, session: HarnessSession, text: str) -> None:
        try:
            await session.run_turn(text)
        except Exception:  # noqa: BLE001 兜底日志：run_turn 正常路径已自收口，到这属于意外
            # run_turn 内部已把异常落成 TurnFinished(error=...)，走到这属于意外
            logger.exception("会话 {} 的轮次异常", session.session_id)

    async def shutdown(self) -> None:
        self._shutting_down = True
        for session in self._sessions.values():
            session.cancel()
        for task in tuple(self._tasks):
            task.cancel()
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)
        results = await asyncio.gather(
            *(session.close() for session in self._sessions.values()), return_exceptions=True,
        )
        for result in results:
            if isinstance(result, BaseException):
                logger.error("会话关闭失败: {}", result)
        self._tasks.clear()

    def _require(self, session_id: str) -> HarnessSession:
        session = self._sessions.get(session_id)
        if session is None:
            raise JsonRpcError(SESSION_NOT_FOUND, f"未知会话: {session_id}")
        return session
