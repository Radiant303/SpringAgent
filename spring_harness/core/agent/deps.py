from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path

from pydantic_ai import ModelMessage, RequestUsage

from spring_harness.core.billing import BillingContext
from spring_harness.core.workspace import FileAccessContext, StorageGuard


@dataclass
class RequestUsageEntry:
    """一次模型请求的用量记录。"""

    model_id: str
    usage: RequestUsage


@dataclass
class CodingAgentDeps:
    workspace: Path
    model_id: str = "__default__"
    billing: BillingContext | None = None
    user_id: int | None = None
    file_access: FileAccessContext | None = None
    storage_guard: StorageGuard | None = None
    storage_on_change: Callable[[], Awaitable[None]] | None = None
    last_messages: list[ModelMessage] = field(default_factory=list)
    usage_log: list[RequestUsageEntry] = field(default_factory=list)

    @classmethod
    def create_default(cls, workspace: Path | None = None):
        return cls(workspace=(workspace or Path.cwd()).resolve())
