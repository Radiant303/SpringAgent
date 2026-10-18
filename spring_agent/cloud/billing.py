"""云端请求计费客户端与结算重试。"""

from __future__ import annotations

import asyncio
import json
import re
import sqlite3
from collections.abc import Awaitable, Callable
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx

from spring_agent.core.billing import BillingError
from spring_agent.core.log import logger

# 单条搜索事件发布等待上限（秒）。
_SEARCH_DELIVERY_TIMEOUT = 10
# 审计补报并发上限。
_AUDIT_CONCURRENCY = 4


class BillingClient:
    def __init__(self, base_url: str, internal_token: str, outbox_path: Path,
                 *, transport: httpx.AsyncBaseTransport | None = None,
                 search_publisher: Callable[[dict[str, Any]], Awaitable[None]] | None = None) -> None:
        outbox_path.parent.mkdir(parents=True, exist_ok=True)
        self._path = outbox_path
        self._client = httpx.AsyncClient(base_url=base_url,
                                        headers={"X-Internal-Token": internal_token},
                                        timeout=10, transport=transport)
        self._worker: asyncio.Task | None = None
        self._search_worker: asyncio.Task | None = None
        self._audit_worker: asyncio.Task | None = None
        self._delivery_lock = asyncio.Lock()
        self._search_lock = asyncio.Lock()
        self._audit_lock = asyncio.Lock()
        self._search_publisher = search_publisher or self._publish_search
        with self._connection() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("CREATE TABLE IF NOT EXISTS billing_outbox ("
                       "request_id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, "
                       "payload TEXT NOT NULL, ready INTEGER NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS search_outbox ("
                       "event_id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS billing_audit_outbox ("
                       "request_id TEXT PRIMARY KEY, payload TEXT NOT NULL, "
                       "dirty INTEGER NOT NULL, version INTEGER NOT NULL)")

    @contextmanager
    def _connection(self):
        db = sqlite3.connect(self._path, timeout=10)
        try:
            with db:
                yield db
        finally:
            db.close()

    def _save(self, payload: dict, *, ready: bool) -> None:
        with self._connection() as db:
            db.execute("INSERT INTO billing_outbox VALUES (?, ?, ?, ?) "
                       "ON CONFLICT(request_id) DO UPDATE SET payload=excluded.payload, ready=excluded.ready",
                       (payload["requestId"], payload["userId"], json.dumps(payload), int(ready)))

    def _delete(self, request_id: str) -> None:
        with self._connection() as db:
            db.execute("DELETE FROM billing_outbox WHERE request_id = ?", (request_id,))

    def _pending(self, user_id: int | None = None) -> list[dict]:
        with self._connection() as db:
            if user_id is None:
                rows = db.execute("SELECT payload FROM billing_outbox WHERE ready = 1 LIMIT 100").fetchall()
            else:
                rows = db.execute("SELECT payload FROM billing_outbox WHERE ready = 1 AND user_id = ? LIMIT 1",
                                  (user_id,)).fetchall()
        return [json.loads(row[0]) for row in rows]

    def _pending_request(self, request_id: str) -> dict | None:
        with self._connection() as db:
            row = db.execute("SELECT payload FROM billing_outbox WHERE request_id = ? AND ready = 1",
                             (request_id,)).fetchone()
        return json.loads(row[0]) if row else None

    def _error_details(self, error: Exception) -> tuple[str, str]:
        message = str(error)
        token = self._client.headers.get("X-Internal-Token")
        if token:
            message = message.replace(token, "[REDACTED]")
        message = re.sub(r"(?im)(?:[\"']?)(?:x-internal-token|authorization|cookie|set-cookie)"
                         r"[\"']?\s*[:=][^\r\n]*", "[REDACTED]", message)
        status = getattr(error, "status_code", None)
        if status is not None:
            message = f"HTTP {status}: {message}"
        return type(error).__name__, message

    def _save_delivery(self, payload: dict, delivery_status: str,
                       error: Exception | None = None, *, unknown: bool = False) -> None:
        request_id = payload["requestId"]
        with self._connection() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT payload, version FROM billing_audit_outbox WHERE request_id = ?",
                             (request_id,)).fetchone()
            if row or error is not None or unknown:
                now = datetime.now(UTC).isoformat()
                report = json.loads(row[0]) if row else {
                    "failureCount": 0, "firstFailedAt": now, "lastFailedAt": now,
                    "errorType": "UnknownUsage", "errorMessage": "实际用量未知，等待核对",
                }
                if error is not None:
                    report["failureCount"] += 1
                    report["lastFailedAt"] = now
                    report["errorType"], report["errorMessage"] = self._error_details(error)
                report.update(requestId=request_id, userId=payload["userId"],
                              settlement=payload, deliveryStatus=delivery_status)
                db.execute("INSERT INTO billing_audit_outbox (request_id, payload, dirty, version) "
                           "VALUES (?, ?, 1, ?) ON CONFLICT(request_id) DO UPDATE SET "
                           "payload=excluded.payload, dirty=1, version=excluded.version",
                           (request_id, json.dumps(report), row[1] + 1 if row else 1))
            if delivery_status != "RETRYING":
                db.execute("DELETE FROM billing_outbox WHERE request_id = ?", (request_id,))

    def _pending_audits(self) -> list[tuple[str, str, int]]:
        with self._connection() as db:
            return db.execute("SELECT request_id, payload, version FROM billing_audit_outbox "
                              "WHERE dirty = 1 ORDER BY rowid").fetchall()

    def _ack_audit(self, request_id: str, version: int, *, terminal: bool) -> None:
        with self._connection() as db:
            if terminal:
                db.execute("DELETE FROM billing_audit_outbox WHERE request_id = ? AND version = ?",
                           (request_id, version))
            else:
                db.execute("UPDATE billing_audit_outbox SET dirty = 0 WHERE request_id = ? AND version = ?",
                           (request_id, version))

    async def _retry_audit_pending(self) -> None:
        async with self._audit_lock:
            pending = await asyncio.to_thread(self._pending_audits)
            semaphore = asyncio.Semaphore(_AUDIT_CONCURRENCY)

            async def deliver(request_id: str, encoded: str, version: int) -> None:
                async with semaphore:
                    try:
                        report = json.loads(encoded)
                        response = await self._post("failure", report)
                        if not response.get("status"):
                            raise BillingError("结算审计回执缺少状态")
                        await asyncio.to_thread(self._ack_audit, request_id, version,
                                                terminal=report["deliveryStatus"] != "RETRYING")
                    except Exception:
                        logger.error("模型请求结算审计待补报: request={}", request_id)

            await asyncio.gather(*(deliver(*row) for row in pending))

    def _save_search(self, event_id: str, encoded: str) -> None:
        with self._connection() as db:
            db.execute("INSERT INTO search_outbox (event_id, payload) VALUES (?, ?) "
                       "ON CONFLICT(event_id) DO NOTHING", (event_id, encoded))
            row = db.execute("SELECT payload FROM search_outbox WHERE event_id = ?", (event_id,)).fetchone()
            if row is None or row[0] != encoded:
                raise ValueError("搜索事件 ID 已存在且内容不同")

    def _delete_search(self, event_id: str) -> None:
        with self._connection() as db:
            db.execute("DELETE FROM search_outbox WHERE event_id = ?", (event_id,))

    def _pending_search(self) -> list[tuple[str, str]]:
        with self._connection() as db:
            return db.execute("SELECT event_id, payload FROM search_outbox ORDER BY rowid").fetchall()

    @staticmethod
    async def _publish_search(payload: dict[str, Any]) -> None:
        from spring_agent.cloud.mq import get_dispatcher

        dispatcher = get_dispatcher()
        if dispatcher is None:
            raise ConnectionError("搜索用量 MQ 尚未连接")
        await dispatcher.publish_search_usage(payload)

    async def record_search(self, payload: dict[str, Any]) -> None:
        fields = {"eventId", "source", "provider", "searches", "userId", "sessionId",
                  "turnId", "requestId", "agentName"}
        if set(payload) != fields or payload["source"] != "local" \
                or type(payload["searches"]) is not int or payload["searches"] != 1 \
                or type(payload["userId"]) is not int or payload["userId"] <= 0 \
                or payload["requestId"] is not None:
            raise ValueError("搜索次数事件格式不正确")
        provider = payload["provider"]
        agent_name = payload["agentName"]
        if not isinstance(provider, str) or re.fullmatch(r"[a-z][a-z0-9_]{0,31}", provider) is None:
            raise ValueError("搜索供应商标识格式不正确")
        if agent_name is not None and (not isinstance(agent_name, str) or len(agent_name) > 64):
            raise ValueError("搜索代理名称格式不正确")
        for name, limit in (("eventId", 32), ("sessionId", 36), ("turnId", 64)):
            if not isinstance(payload[name], str) or not 0 < len(payload[name]) <= limit:
                raise ValueError("搜索次数事件归属不正确")
        encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        saved = json.loads(encoded)
        try:
            await asyncio.to_thread(self._save_search, saved["eventId"], encoded)
        except (sqlite3.Error, OSError) as error:
            raise BillingError("搜索次数事件写入失败") from error
        if not self._search_lock.locked():
            async with self._search_lock:
                await self._deliver_search(saved)

    async def _deliver_search(self, payload: dict[str, Any]) -> None:
        try:
            await asyncio.wait_for(self._search_publisher(payload), timeout=_SEARCH_DELIVERY_TIMEOUT)
            await asyncio.to_thread(self._delete_search, payload["eventId"])
        except Exception as error:
            logger.error("搜索次数事件待重试: event={} error={}", payload["eventId"], error)

    async def _retry_search_pending(self) -> None:
        async with self._search_lock:
            for event_id, encoded in await asyncio.to_thread(self._pending_search):
                try:
                    payload = json.loads(encoded)
                    if payload["eventId"] != event_id:
                        raise ValueError("搜索事件 ID 不一致")
                    await self._deliver_search(payload)
                except Exception as error:
                    logger.error("搜索次数事件读取失败: event={} error={}", event_id, error)

    async def _post(self, endpoint: str, payload: dict) -> dict:
        for attempt in range(3):
            try:
                response = await self._client.post("/internal/billing/requests/" + endpoint, json=payload)
                if response.status_code < 500:
                    if not response.is_success:
                        try:
                            message = response.json().get("message", "计费请求被拒绝")
                        except ValueError:
                            message = "计费请求被拒绝"
                        error = BillingError(message)
                        error.status_code = response.status_code
                        raise error
                    return response.json()
                response.raise_for_status()
            except (httpx.TransportError, httpx.HTTPStatusError) as error:
                if attempt == 2:
                    raise BillingError("计费服务暂不可用，已停止模型请求") from error
            await asyncio.sleep(0.2 * (attempt + 1))
        raise AssertionError("unreachable")

    @staticmethod
    def _recovery(request_id: str, user_id: int, *, issued: bool) -> dict:
        return {"requestId": request_id, "userId": user_id, "status": "error", "usageKnown": not issued,
                "inputTokens": 0, "cacheReadTokens": 0, "cacheWriteTokens": 0, "outputTokens": 0,
                "webSearches": None}

    async def reserve(self, payload: dict[str, Any]) -> None:
        async with self._delivery_lock:
            if await asyncio.to_thread(self._pending, payload["userId"]):
                raise BillingError("有模型请求尚未结算，请稍后重试")
        recovery = self._recovery(payload["requestId"], payload["userId"], issued=False)
        await asyncio.to_thread(self._save, recovery, ready=False)
        try:
            await self._post("reserve", payload)
        except BillingError as error:
            if getattr(error, "status_code", None) is not None:
                await asyncio.to_thread(self._delete, payload["requestId"])
            else:
                await asyncio.to_thread(self._save, recovery, ready=True)
            raise

    async def issued(self, request_id: str) -> None:
        def update():
            with self._connection() as db:
                row = db.execute("SELECT user_id FROM billing_outbox WHERE request_id = ?", (request_id,)).fetchone()
                if row is None:
                    raise BillingError("请求冻结记录丢失")
                payload = self._recovery(request_id, row[0], issued=True)
                db.execute("UPDATE billing_outbox SET payload = ? WHERE request_id = ?",
                           (json.dumps(payload), request_id))
        await asyncio.to_thread(update)

    async def settle(self, payload: dict[str, Any]) -> None:
        async with self._delivery_lock:
            await asyncio.to_thread(self._save, payload, ready=True)
            await self._deliver(payload)

    async def _persist_delivery(self, payload: dict, delivery_status: str,
                                error: Exception | None = None, *, unknown: bool = False) -> None:
        task = asyncio.create_task(asyncio.to_thread(self._save_delivery, payload, delivery_status,
                                                     error, unknown=unknown))
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            await task
            raise

    async def _deliver(self, payload: dict) -> None:
        try:
            response = await self._post("settle", payload)
        except Exception as error:
            discarded = getattr(error, "status_code", None) == 404 and payload["usageKnown"] \
                and not (payload["inputTokens"] or payload["outputTokens"] \
                         or payload["cacheReadTokens"] or payload["cacheWriteTokens"]) \
                and (payload.get("webSearches") or 0) <= 0
            if discarded:
                await self._persist_delivery(payload, "DISCARDED", error)
                return
            try:
                await self._persist_delivery(payload, "RETRYING", error)
            except Exception:
                logger.error("模型请求结算审计写入失败: request={}", payload["requestId"])
            raise
        await self._persist_delivery(payload, "DELIVERED",
                                     unknown=response.get("status") == "UNKNOWN" or not payload["usageKnown"])

    async def start(self) -> None:
        def recover():
            with self._connection() as db:
                db.execute("UPDATE billing_outbox SET ready = 1 WHERE ready = 0")
        await asyncio.to_thread(recover)
        self._search_worker = asyncio.create_task(self._retry_loop(search=True))
        self._audit_worker = asyncio.create_task(self._retry_loop(audit=True))
        await self._retry_http_pending()
        self._worker = asyncio.create_task(self._retry_loop())

    async def retry_pending(self) -> None:
        await asyncio.gather(self._retry_http_pending(), self._retry_search_pending(),
                             self._retry_audit_pending())

    async def _retry_http_pending(self) -> None:
        for saved in await asyncio.to_thread(self._pending):
            async with self._delivery_lock:
                try:
                    payload = await asyncio.to_thread(self._pending_request, saved["requestId"])
                    if payload is None:
                        continue
                    payload.setdefault("webSearches", None)
                    await self._deliver(payload)
                except Exception as error:
                    _, message = self._error_details(error)
                    logger.error("模型请求结算待重试: request={} error={}", saved["requestId"], message)

    async def _retry_loop(self, *, search: bool = False, audit: bool = False) -> None:
        while True:
            try:
                if search:
                    await self._retry_search_pending()
                elif audit:
                    await self._retry_audit_pending()
                else:
                    await self._retry_http_pending()
            except Exception:
                if audit:
                    logger.error("模型请求结算审计补报失败")
                else:
                    logger.exception("搜索次数事件重试失败" if search else "模型请求结算重试失败")
            await asyncio.sleep(5)

    async def close(self) -> None:
        workers = [worker for worker in (self._worker, self._search_worker, self._audit_worker)
                   if worker is not None]
        for worker in workers:
            worker.cancel()
        await asyncio.gather(*workers, return_exceptions=True)
        self._worker = self._search_worker = self._audit_worker = None
        await self._client.aclose()


_client: BillingClient | None = None


def set_billing_client(client: BillingClient | None) -> None:
    global _client
    _client = client


def get_billing_client() -> BillingClient | None:
    return _client
