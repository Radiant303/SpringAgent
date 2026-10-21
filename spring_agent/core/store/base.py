from typing import Protocol

from pydantic_ai import ModelMessage


class SessionStore(Protocol):
    """会话消息存储。"""

    @property
    def session_id(self) -> str: ...

    def append(self, messages: list[ModelMessage]) -> None:
        """每轮结束（含异常终止）后调用，追加增量。"""
        ...

    def append_rewritten(self, messages: list[ModelMessage]) -> None:
        """历史被压缩/改写时调用：开新段写入新基线，旧段保留。"""
        ...

    def load_messages(self) -> list[ModelMessage]:
        """发给模型的当前历史：最新一段（压缩后的新基线）。"""
        ...
