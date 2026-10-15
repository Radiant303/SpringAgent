from dataclasses import dataclass, field
from pathlib import Path

from pydantic_ai import ModelMessage, RequestUsage

from spring_harness.core.billing import BillingContext
from spring_harness.core.background import BackgroundTaskManager


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
    last_messages: list[ModelMessage] = field(default_factory=list)
    usage_log: list[RequestUsageEntry] = field(default_factory=list)
    background: BackgroundTaskManager = field(default_factory=BackgroundTaskManager)

    @classmethod
    def create_default(cls, workspace: Path | None = None):
        return cls(workspace=(workspace or Path.cwd()).resolve())
