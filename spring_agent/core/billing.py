"""模型请求计费上下文。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol


class BillingError(RuntimeError):
    status_code: int | None = None


class BillingGateway(Protocol):
    async def reserve(self, payload: dict[str, Any]) -> None: ...

    async def settle(self, payload: dict[str, Any]) -> None: ...

    async def record_search(self, payload: dict[str, Any]) -> None: ...


@dataclass(frozen=True)
class BillingContext:
    gateway: BillingGateway
    user_id: int
    session_id: str
    turn_id: str
