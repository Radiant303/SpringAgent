from typing import Literal

from pydantic import BaseModel, Field


class TextDelta(BaseModel):
    kind: Literal["text_delta"] = "text_delta"
    text: str

class ThinkingDelta(BaseModel):
    kind: Literal["thinking_delta"] = "thinking_delta"
    text: str

class ToolCallStarted(BaseModel):
    kind: Literal["tool_started"] = "tool_started"
    tool_call_id: str
    tool_name: str

class ToolArgsDelta(BaseModel):
    kind: Literal["tool_args_delta"] = "tool_args_delta"
    tool_call_id: str
    args_chunk: str

class ToolDiff(BaseModel):
    kind: Literal["tool_diff"] = "tool_diff"
    tool_call_id: str
    diff: str

class ToolFinished(BaseModel):
    kind: Literal["tool_finished"] = "tool_finished"
    tool_call_id: str
    result: str
    is_error: bool = False

class ToolPending(BaseModel):
    kind: Literal["tool_pending"] = "tool_pending"
    tool_call_id: str
    label: str = "等待批准"

class UsageUpdated(BaseModel):
    kind: Literal["usage"] = "usage"
    context_tokens: int

class ModelUsage(BaseModel):
    model_id: str
    requests: int = 0
    input_tokens: int = 0
    cache_read_tokens: int = 0
    cache_write_tokens: int = 0
    output_tokens: int = 0


class TurnUsage(BaseModel):
    """一轮对话的模型分组用量。"""

    requests: int = 0
    input_tokens: int = 0
    cache_read_tokens: int = 0
    cache_write_tokens: int = 0
    output_tokens: int = 0
    usages: list[ModelUsage] = Field(default_factory=list)

class PlanUpdated(BaseModel):
    kind: Literal["plan_updated"] = "plan_updated"
    items: list[dict]

class TeachingUpdated(BaseModel):
    kind: Literal["teaching_updated"] = "teaching_updated"
    unit: dict

class StorageUpdated(BaseModel):
    kind: Literal["storage_updated"] = "storage_updated"


class CompactionStarted(BaseModel):
    kind: Literal["compaction_started"] = "compaction_started"
    before: int

class CompactionNotice(BaseModel):
    kind: Literal["compaction"] = "compaction"
    dropped: int; before: int; after: int

class TurnFinished(BaseModel):
    kind: Literal["turn_finished"] = "turn_finished"
    cancelled: bool = False
    error: str | None = None
    turn_id: str | None = None  # 轮次 ID
    user_id: int | None = None  # 计费归属的用户 ID
    usage: TurnUsage | None = None  # 本轮 token 用量合计

class ApprovalRequest(BaseModel):
    kind: Literal["approval_request"] = "approval_request"
    request_id: str
    tool_call_id: str
    tool_name: str
    args: dict
    diff: str | None

class QuestionRequest(BaseModel):
    kind: Literal["question_request"] = "question_request"
    request_id: str
    question: str
    options: list | None
    allow_custom: bool = True


ServerEvent = TextDelta | ThinkingDelta | ToolCallStarted | ToolArgsDelta | ToolDiff | ToolPending | ToolFinished | UsageUpdated | PlanUpdated | TeachingUpdated | StorageUpdated | CompactionStarted | CompactionNotice | TurnFinished | ApprovalRequest | QuestionRequest
