from __future__ import annotations

import asyncio
import secrets
import uuid
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import ClassVar

from fastapi import WebSocket, WebSocketDisconnect

from spring_harness.cloud import gateway_store
from spring_harness.cloud.auth import decode_user_id
from spring_harness.cloud.db import UserRow, get_sessionmaker
from spring_harness.cloud.gateway_store import GatewaySessionStore, find_active_session, list_sessions_for_user
from spring_harness.cloud.registry import CloudSessionHandle, get_registry
from spring_harness.core.config.model import setting as model_setting
from spring_harness.core.config.settings import config
from spring_harness.core.log import logger
from spring_harness.core.rpc.connection import JsonRpcConnection, JsonRpcError
from spring_harness.core.rpc.schema import (
    AttachParams, SessionListResult, SessionParams, SessionResult, SessionSummary,
    SetModelParams, TurnStartParams, WorkspaceParams,
)
from spring_harness.core.rpc.server import BUSY, PROTOCOL_VERSION, SESSION_NOT_FOUND, AppServer
from spring_harness.core.services.web_server import ModelsListResult, WebInitializeResult, _model_infos

SESSION_OCCUPIED = -32003
SESSION_REPLACED = -32004
WS_UNAUTHORIZED_CLOSE_CODE = 4401
WS_DISABLED_CLOSE_CODE = 4403
WS_REPLACED_CLOSE_CODE = 4409


class CloudSessionResult(SessionResult):
    busy: bool = False


def _user_close_code(user_id: int) -> int | None:
    with get_sessionmaker()() as s:
        user = s.get(UserRow, user_id)
        if user is None:
            return WS_UNAUTHORIZED_CLOSE_CODE
        if user.status != "active":
            return WS_DISABLED_CLOSE_CODE
        return None


class CloudAppServer(AppServer):
    """按用户隔离的会话控制连接。"""

    _active_owners: ClassVar[dict[str, CloudAppServer]] = {}

    def __init__(
        self, connection: JsonRpcConnection, *, user_id: int, data_root: Path,
        connection_id: str | None = None,
        close_connection: Callable[[int], Awaitable[None]] | None = None,
    ) -> None:
        super().__init__(connection)
        self._user_id = user_id
        self._data_root = data_root
        self._connection_id = connection_id or uuid.uuid4().hex
        self._close_connection = close_connection
        self._owned_sessions: set[str] = set()
        self._current_session: str | None = None
        self._control_tokens: dict[str, str] = {}
        self._routes["models/list"] = (None, self._models_list)
        self._routes["session/status"] = (SessionParams, self._session_status)

    @classmethod
    def control_token(cls, session_id: str, user_id: int, connection_id: str) -> str | None:
        owner = cls._active_owners.get(session_id)
        if owner is None or owner._shutting_down:
            return None
        if owner._user_id != user_id or owner._connection_id != connection_id:
            return None
        return owner._control_tokens.get(session_id)

    @classmethod
    def controls(cls, session_id: str, user_id: int, token: str | None) -> bool:
        owner = cls._active_owners.get(session_id)
        return bool(owner is not None and not owner._shutting_down
                    and owner._user_id == user_id and token
                    and owner._control_tokens.get(session_id) == token)

    async def handle_request(self, method: str, params: dict):
        self._check_connection()
        return await super().handle_request(method, params)

    def _check_connection(self) -> None:
        if self._shutting_down:
            raise JsonRpcError(SESSION_REPLACED, "连接已失效，请重新连接")

    def _require(self, session_id: str):
        self._check_connection()
        if self._active_owners.get(session_id) is not self:
            raise JsonRpcError(SESSION_OCCUPIED, "当前连接未控制此会话")
        return super()._require(session_id)

    async def _models_list(self, _: None) -> ModelsListResult:
        return ModelsListResult(models=_model_infos(), default_model=model_setting.default_model())

    def _release(self, session_id: str) -> None:
        if self._active_owners.get(session_id) is self:
            del self._active_owners[session_id]
        self._owned_sessions.discard(session_id)
        self._control_tokens.pop(session_id, None)

    def _reject_requests(self) -> None:
        for request_id, (session_id, kind) in list(self._inflight_requests.items()):
            session = self._sessions.get(session_id)
            if session is not None:
                session.respond(request_id, False if kind == "approval" else None)
            self._connection.discard_response(request_id)
        self._inflight_requests.clear()
        for task in tuple(self._tasks):
            task.cancel()

    async def _close_replaced(self, session_id: str) -> None:
        try:
            await self._connection.send_notification("session/replaced", {"sessionId": session_id})
        except Exception:
            logger.debug("被接管连接无法接收通知: {}", session_id)
        finally:
            if self._close_connection is not None:
                try:
                    await self._close_connection(WS_REPLACED_CLOSE_CODE)
                except (WebSocketDisconnect, RuntimeError):
                    pass

    def _adopt(self, handle: CloudSessionHandle, model: str | None, effort: str | None = None,
               *, takeover: bool = False) -> None:
        self._check_connection()
        session_id = handle.session_id
        owner = self._active_owners.get(session_id)
        if owner is not None and owner is not self:
            if owner._user_id != self._user_id:
                raise JsonRpcError(SESSION_NOT_FOUND, "未知会话")
            if not takeover:
                raise JsonRpcError(SESSION_OCCUPIED, "会话正被其他连接使用")
        if not handle.busy:
            self._apply_model(handle.session, model, effort)
        if owner is not None and owner is not self:
            owner._shutting_down = True
            owner._reject_requests()
            for previous in tuple(owner._owned_sessions):
                owner._unmount(previous)
            asyncio.create_task(owner._close_replaced(session_id))
        if self._current_session is not None and self._current_session != session_id:
            self._unmount(self._current_session)
        if self._active_owners.get(session_id) is not self:
            self._control_tokens[session_id] = uuid.uuid4().hex
        self._active_owners[session_id] = self
        self._owned_sessions.add(session_id)
        handle.remember_user(self._user_id)
        handle.set_approval_target(self)
        self._sessions[session_id] = handle.session
        self._current_session = session_id

    def _unmount(self, session_id: str) -> None:
        registry = get_registry()
        handle = registry.get(session_id) if registry is not None else None
        if handle is not None and handle.approval_target is self:
            handle.set_approval_target(None)
        self._release(session_id)
        self._sessions.pop(session_id, None)
        if self._current_session == session_id:
            self._current_session = None

    async def _initialize(self, _: None) -> WebInitializeResult:
        work = await asyncio.to_thread(gateway_store.ensure_default_work, self._user_id)
        return WebInitializeResult(
            server_name="spring-harness", protocol_version=PROTOCOL_VERSION,
            workspace=work.workspace_path, models=_model_infos(), default_model=model_setting.default_model(),
        )

    async def _session_new(self, p: WorkspaceParams) -> CloudSessionResult:
        from spring_harness.core.config.thinking import resolve_effort
        from spring_harness.core.rpc.connection import INVALID_PARAMS

        model = p.model or model_setting.default_model()
        try:
            if model not in model_setting.list_models():
                raise ValueError(f"模型 '{model}' 不存在或未启用")
            resolve_effort(model_setting.get_model_config(model), p.effort)
        except ValueError as e:
            raise JsonRpcError(INVALID_PARAMS, str(e)) from e
        session_id = str(uuid.uuid4())
        if p.work_id:
            work = await asyncio.to_thread(gateway_store.get_work, p.work_id)
            if work is None or work.user_id != self._user_id:
                raise JsonRpcError(SESSION_NOT_FOUND, f"未知项目: {p.work_id}")
        else:
            work = await asyncio.to_thread(gateway_store.ensure_default_work, self._user_id)
        self._check_connection()
        store = await asyncio.to_thread(GatewaySessionStore.create, self._user_id, work.work_id, session_id)
        registry = get_registry()
        assert registry is not None
        handle = await registry.get_or_create(
            session_id, workspace=Path(work.workspace_path), store=store, work_id=work.work_id,
        )
        self._adopt(handle, p.model, p.effort)
        return self._cloud_result(handle)

    async def _session_resume_last(self, p: WorkspaceParams) -> SessionResult:
        rows = await asyncio.to_thread(list_sessions_for_user, self._user_id)
        if p.work_id:
            rows = [row for row in rows if row.work_id == p.work_id]
        if not rows:
            return CloudSessionResult(session_id=None)
        return await self._session_attach(AttachParams(
            workspace=p.workspace, session_id=rows[0].id, model=p.model, effort=p.effort,
        ))

    async def _session_list(self, p: WorkspaceParams) -> SessionListResult:
        rows = await asyncio.to_thread(list_sessions_for_user, self._user_id)
        if p.work_id:
            rows = [row for row in rows if row.work_id == p.work_id]
        return SessionListResult(sessions=[SessionSummary(
            session_id=row.id, title=row.title, created_at=row.created_at.isoformat() + "Z",
            updated_at=row.updated_at.isoformat() + "Z",
        ) for row in rows])

    async def _session_attach(self, p: AttachParams) -> CloudSessionResult:
        row = await asyncio.to_thread(find_active_session, p.session_id, self._user_id)
        if row is None:
            raise JsonRpcError(SESSION_NOT_FOUND, f"未知会话: {p.session_id}")
        self._check_connection()
        registry = get_registry()
        assert registry is not None
        handle = registry.get(p.session_id)
        if handle is None:
            store = await asyncio.to_thread(GatewaySessionStore.open, p.session_id)
            if store is None:
                raise JsonRpcError(SESSION_NOT_FOUND, f"未知会话: {p.session_id}")
            handle = await registry.get_or_create(
                p.session_id, workspace=Path(row.workspace_path), store=store, work_id=row.work_id,
            )
        self._adopt(handle, p.model, p.effort, takeover=p.takeover)
        return self._cloud_result(handle)

    def _cloud_result(self, handle: CloudSessionHandle) -> CloudSessionResult:
        return CloudSessionResult(session_id=handle.session_id, busy=handle.busy,
                                  **handle.session.model_selection)

    async def _session_set_model(self, p: SetModelParams) -> CloudSessionResult:
        self._require(p.session_id)
        registry = get_registry()
        assert registry is not None
        handle = registry.get(p.session_id)
        assert handle is not None
        if handle.busy:
            raise JsonRpcError(BUSY, "运行中不能切换模型或思考挡位")
        self._apply_model(handle.session, p.model, p.effort)
        return self._cloud_result(handle)

    async def _turn_start(self, p: TurnStartParams) -> None:
        self._require(p.session_id)
        registry = get_registry()
        assert registry is not None
        handle = registry.get(p.session_id)
        assert handle is not None
        handle.start_turn(p.input, turn_id=f"ws-{uuid.uuid4().hex[:24]}", user_id=self._user_id)

    async def _session_status(self, p: SessionParams) -> CloudSessionResult:
        self._require(p.session_id)
        registry = get_registry()
        assert registry is not None
        handle = registry.get(p.session_id)
        assert handle is not None
        return self._cloud_result(handle)

    async def _turn_cancel(self, p: SessionParams) -> None:
        self._require(p.session_id)
        registry = get_registry()
        assert registry is not None
        handle = registry.get(p.session_id)
        assert handle is not None
        handle.cancel_turn()

    async def shutdown(self) -> None:
        self._shutting_down = True
        self._reject_requests()
        for session_id in tuple(self._owned_sessions):
            self._unmount(session_id)
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)
        self._tasks.clear()


async def ws_endpoint(websocket: WebSocket) -> None:
    await websocket.accept()
    token = websocket.query_params.get("token")
    user_id = decode_user_id(token) if token else None
    close_code = WS_UNAUTHORIZED_CLOSE_CODE if user_id is None else await asyncio.to_thread(_user_close_code, user_id)
    if close_code is not None:
        await websocket.close(code=close_code)
        return

    async def read_line() -> str | None:
        try:
            return await websocket.receive_text()
        except (WebSocketDisconnect, RuntimeError):
            return None

    async def write_line(line: str) -> None:
        try:
            await websocket.send_text(line)
        except (WebSocketDisconnect, RuntimeError) as e:
            raise ConnectionError("WebSocket 已关闭") from e

    async def close_connection(code: int) -> None:
        await websocket.close(code=code)

    trusted = secrets.compare_digest(websocket.headers.get("x-internal-token", ""), config.cloud.internal_token)
    connection_id = websocket.headers.get("x-connection-id") if trusted else None
    connection = JsonRpcConnection(read_line, write_line)
    server = CloudAppServer(
        connection, user_id=user_id, data_root=Path(config.cloud.data_root).expanduser(),
        connection_id=connection_id, close_connection=close_connection,
    )
    logger.info("云端客户端已连接: user_id={} client={}", user_id, websocket.client)
    try:
        await connection.run(server.handle_request, server.handle_notification)
    finally:
        await server.shutdown()
        logger.info("云端客户端已断开: user_id={} client={}", user_id, websocket.client)
