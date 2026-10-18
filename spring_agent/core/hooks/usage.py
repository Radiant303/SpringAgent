"""模型响应的本地用量补全。"""

from __future__ import annotations

import json
from collections.abc import Iterator
from copy import copy
from functools import cache

from pydantic_ai import (
    ModelMessage,
    ModelResponse,
    NativeToolCallPart,
    NativeToolReturnPart,
    RequestUsage,
    TextPart,
    ThinkingPart,
    ToolCallPart,
)
from pydantic_ai.models import ModelRequestParameters
from pydantic_ai_harness.compaction._shared import estimate_context_tokens

try:
    import tiktoken
except ImportError:
    tiktoken = None

_SEARCH_TOOL_NAMES = {"web_search", "google_search"}
_NON_SEARCH_ACTIONS = {"open_page", "find_in_page"}


@cache
def _encoding():
    if tiktoken is None:
        return None
    try:
        return tiktoken.get_encoding("cl100k_base")
    except Exception:
        return None


def _count_text(text: str) -> int:
    if not text:
        return 0
    encoding = _encoding()
    if encoding is not None:
        return len(encoding.encode(text, disallowed_special=()))
    return max(1, len(text) // 4)


def _output_text(response: ModelResponse) -> Iterator[str]:
    for part in response.parts:
        if isinstance(part, TextPart):
            yield part.content
        elif isinstance(part, ThinkingPart):
            yield part.content or ""
        elif isinstance(part, ToolCallPart | NativeToolCallPart) and part.args is not None:
            yield part.tool_name
            if isinstance(part.args, str):
                yield part.args
            else:
                yield json.dumps(part.args, ensure_ascii=False, separators=(",", ":"))


def fill_missing_usage(usage: RequestUsage, response: ModelResponse, messages: list[ModelMessage],
                       parameters: ModelRequestParameters) -> RequestUsage:
    """补齐上游尚未报告的输入与输出 tokens。"""
    estimated = copy(usage)
    if estimated.input_tokens == 0:
        input_tokens = estimate_context_tokens(
            messages, _count_text, model_request_parameters=parameters,
        )
        estimated.input_tokens = max(
            input_tokens, estimated.cache_read_tokens + estimated.cache_write_tokens,
        )
    if estimated.output_tokens == 0:
        estimated.output_tokens = sum(_count_text(text) for text in _output_text(response))
    return estimated


def count_native_web_searches(response: ModelResponse) -> int | None:
    """从已完成原生搜索部分恢复搜索次数。"""
    returned = {
        part.tool_call_id
        for part in response.parts
        if isinstance(part, NativeToolReturnPart) and part.tool_name in _SEARCH_TOOL_NAMES
    }
    if not returned:
        return None
    searches: set[str] = set()
    for part in response.parts:
        if not isinstance(part, NativeToolCallPart) or part.tool_name not in _SEARCH_TOOL_NAMES \
                or part.tool_call_id not in returned or part.tool_call_id in searches:
            continue
        args = part.args
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except ValueError:
                args = None
        if isinstance(args, dict) and args.get("type") in _NON_SEARCH_ACTIONS:
            continue
        searches.add(part.tool_call_id)
    return len(searches) or None
