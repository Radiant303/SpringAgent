"""云端请求计费客户端与结算重试。"""

from __future__ import annotations

import asyncio
import json
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import httpx

from spring_harness.core.billing import BillingError
from spring_harness.core.log import logger


class BillingClient:
    def __init__(self, base_url: str, internal_token: str, outbox_path: Path,
                 *, transport: httpx.AsyncBaseTransport | None = None) -> None:
        outbox_path.parent.mkdir(parents=True, exist_ok=True)
        self._path = outbox_path
        self._client = httpx.AsyncClient(base_url=base_url,
                                        headers={"X-Internal-Token": internal_token},
                                        timeout=10, transport=transport)
        self._worker: asyncio.Task | None = None
        self._delivery_lock = asyncio.Lock()
        with self._connection() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("CREATE TABLE IF NOT EXISTS billing_outbox ("
                       "request_id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, "
                       "payload TEXT NOT NULL, ready INTEGER NOT NULL)")

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
                "inputTokens": 0, "cacheReadTokens": 0, "cacheWriteTokens": 0, "outputTokens": 0}

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

    async def _deliver(self, payload: dict) -> None:
        try:
            await self._post("settle", payload)
        except BillingError as error:
            if getattr(error, "status_code", None) == 404 and payload["usageKnown"] \
                    and not (payload["inputTokens"] or payload["outputTokens"]):
                await asyncio.to_thread(self._delete, payload["requestId"])
                return
            raise
        await asyncio.to_thread(self._delete, payload["requestId"])

    async def start(self) -> None:
        def recover():
            with self._connection() as db:
                db.execute("UPDATE billing_outbox SET ready = 1 WHERE ready = 0")
        await asyncio.to_thread(recover)
        await self.retry_pending()
        self._worker = asyncio.create_task(self._retry_loop())

    async def retry_pending(self) -> None:
        async with self._delivery_lock:
            for payload in await asyncio.to_thread(self._pending):
                try:
                    await self._deliver(payload)
                except BillingError as error:
                    logger.error("模型请求结算待重试: request={} error={}", payload["requestId"], error)

    async def _retry_loop(self) -> None:
        while True:
            await asyncio.sleep(5)
            try:
                await self.retry_pending()
            except Exception:
                logger.exception("模型请求结算重试失败")

    async def close(self) -> None:
        if self._worker is not None:
            self._worker.cancel()
            await asyncio.gather(self._worker, return_exceptions=True)
        await self._client.aclose()


_client: BillingClient | None = None


def set_billing_client(client: BillingClient | None) -> None:
    global _client
    _client = client


def get_billing_client() -> BillingClient | None:
    return _client
