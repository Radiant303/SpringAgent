"""存储事务提交后的通知队列。"""

from collections.abc import Awaitable, Callable
from contextvars import ContextVar

pending_notifications: ContextVar[list[Callable[[], Awaitable[None]]] | None] = ContextVar(
    "storage_notifications", default=None,
)
