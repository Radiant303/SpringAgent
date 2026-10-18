import asyncio
import json
from collections.abc import Awaitable, Callable
from typing import Any, Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, TypeAdapter, ValidationError

PARSE_ERROR = -32700
CHUNK_METHOD = "__ws_chunk"
CHUNK_MAX_CHARS = 8 * 1024
INVALID_REQUEST = -32600
INVALID_PARAMS = -32602
METHOD_NOT_FOUND = -32601
INTERNAL_ERROR = -32603


class JsonRpcError(Exception):
    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code


class JsonRpcErrorBody(BaseModel):
    code: int
    message: str


class JsonRpcRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    jsonrpc: Literal["2.0"] = "2.0"
    id: str | int
    method: str
    params: dict[str, Any] | None = None


class JsonRpcNotification(BaseModel):
    model_config = ConfigDict(extra="forbid")

    jsonrpc: Literal["2.0"] = "2.0"
    method: str
    params: dict[str, Any] | None = None


class JsonRpcResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    jsonrpc: Literal["2.0"] = "2.0"
    id: str | int
    result: Any


class JsonRpcErrorResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    # id 允许为 None：无法确定请求 id 时（如解析失败）按规范回 null
    jsonrpc: Literal["2.0"] = "2.0"
    id: str | int | None
    error: JsonRpcErrorBody


# 入站消息四选一：必填键互斥（请求必有 id+method，通知无 id，响应无 method
# 且 result/error 二选一）+ extra=forbid，联合校验即完成结构判别
_INBOUND_ADAPTER = TypeAdapter(
    JsonRpcRequest | JsonRpcNotification | JsonRpcResponse | JsonRpcErrorResponse
)


def _params_dict(params: dict | BaseModel) -> dict:
    if isinstance(params, BaseModel):
        return params.model_dump(by_alias=True)
    return params


class JsonRpcConnection:
    def __init__(
        self,
        read_line: Callable[[], Awaitable[str | None]],   # None = 对端关闭
        write_line: Callable[[str], Awaitable[None]],
        *,
        chunk_large: bool = False,
    ) -> None:
        self._read_line = read_line
        self._write_line = write_line
        self._chunk_large = chunk_large
        self._send_lock = asyncio.Lock()
        self._pending: dict[str | int, asyncio.Future] = {}

    # ---- 发送 ----

    async def post_request(self, method: str, params: dict | BaseModel, *, rid: str | int) -> None:
        future = asyncio.get_running_loop().create_future()
        self._pending[rid] = future
        try:
            await self._send(JsonRpcRequest(id=rid, method=method, params=_params_dict(params)))
        except BaseException:
            self.discard_response(rid)
            raise

    def discard_response(self, rid: str | int) -> None:
        future = self._pending.pop(rid, None)
        if future is not None:
            if future.done() and not future.cancelled():
                future.exception()
            else:
                future.cancel()

    async def wait_response(self, rid: str | int) -> Any:
        future = self._pending[rid]
        try:
            return await future
        finally:
            self._pending.pop(rid, None)

    async def send_notification(self, method: str, params: dict | BaseModel) -> None:
        await self._send(JsonRpcNotification(method=method, params=_params_dict(params)))

    async def _send(self, message: BaseModel) -> None:
        line = message.model_dump_json()
        async with self._send_lock:
            if not self._chunk_large or len(line) <= CHUNK_MAX_CHARS:
                await self._write_line(line)
                return
            chunk_id = uuid4().hex
            total = (len(line) + CHUNK_MAX_CHARS - 1) // CHUNK_MAX_CHARS
            for index, start in enumerate(range(0, len(line), CHUNK_MAX_CHARS)):
                await self._write_line(json.dumps({
                    "jsonrpc": "2.0",
                    "method": CHUNK_METHOD,
                    "params": {
                        "id": chunk_id,
                        "index": index,
                        "total": total,
                        "data": line[start:start + CHUNK_MAX_CHARS],
                    },
                }, ensure_ascii=False))

    # ---- 接收循环 ----

    async def run(
        self,
        on_request: Callable[[str, dict], Awaitable[Any]],
        on_notification: Callable[[str, dict], Awaitable[None]],
    ) -> None:
        try:
            while (line := await self._read_line()) is not None:
                try:
                    raw = json.loads(line)
                except json.JSONDecodeError:
                    await self._send(JsonRpcErrorResponse(
                        id=None, error=JsonRpcErrorBody(code=PARSE_ERROR, message="Parse error")))
                    continue
                try:
                    message = _INBOUND_ADAPTER.validate_python(raw)
                except ValidationError:
                    # 形似请求的按规范回 Invalid Request；形似响应/通知的无法应答，丢弃
                    if isinstance(raw, dict) and "method" in raw:
                        await self._send(JsonRpcErrorResponse(
                            id=None, error=JsonRpcErrorBody(code=INVALID_REQUEST, message="Invalid Request")))
                    continue
                if isinstance(message, JsonRpcRequest):
                    await self._dispatch_request(on_request, message)
                elif isinstance(message, JsonRpcNotification):
                    await on_notification(message.method, message.params or {})
                else:
                    self._resolve_pending(message)
        finally:
            for future in self._pending.values():
                if not future.done():
                    future.set_exception(JsonRpcError(-32000, "连接已关闭"))

    def _resolve_pending(self, message: JsonRpcResponse | JsonRpcErrorResponse) -> None:
        if message.id is None:
            return  # id 为 null 的响应（如对畸形请求的报错）无法与任何挂起请求配对
        future = self._pending.get(message.id)
        if future is not None and not future.done():
            if isinstance(message, JsonRpcErrorResponse):
                future.set_exception(JsonRpcError(message.error.code, message.error.message))
            else:
                future.set_result(message.result)

    async def _dispatch_request(self, on_request, request: JsonRpcRequest) -> None:
        try:
            result = await on_request(request.method, request.params or {})
            await self._send(JsonRpcResponse(id=request.id, result=result if result is not None else {}))
        except JsonRpcError as e:
            await self._send(JsonRpcErrorResponse(
                id=request.id, error=JsonRpcErrorBody(code=e.code, message=str(e))))
        except Exception as e:  # noqa: BLE001 协议边界：处理函数的任何异常都要映射成 -32603 错误响应
            await self._send(JsonRpcErrorResponse(
                id=request.id, error=JsonRpcErrorBody(code=INTERNAL_ERROR, message=f"{type(e).__name__}: {e}")))
