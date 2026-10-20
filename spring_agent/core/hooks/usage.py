"""模型响应的本地用量补全。"""

from __future__ import annotations

import json
from collections.abc import Iterator
from copy import copy
from functools import cache

from pydantic_ai import (
    ModelMessage,
    ModelRequest,
    ModelResponse,
    NativeToolCallPart,
    NativeToolReturnPart,
    RequestUsage,
    RetryPromptPart,
    SystemPromptPart,
    TextPart,
    ThinkingPart,
    ToolCallPart,
    ToolReturnPart,
    UserPromptPart,
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


def count_text_tokens(text: str) -> int:
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
            messages, count_text_tokens, model_request_parameters=parameters,
        )
        estimated.input_tokens = max(
            input_tokens, estimated.cache_read_tokens + estimated.cache_write_tokens,
        )
    if estimated.output_tokens == 0:
        estimated.output_tokens = sum(count_text_tokens(text) for text in _output_text(response))
    return estimated


def estimate_interrupted_usage(messages: list[ModelMessage],
                               parameters: ModelRequestParameters) -> RequestUsage:
    """无响应请求的用量估算：新输入按全价，系统提示词与上下文按缓存读取价。"""
    split = 0
    for index in range(len(messages) - 1, -1, -1):
        if isinstance(messages[index], ModelResponse):
            split = index + 1
            break
    cached = estimate_context_tokens(messages[:split], count_text_tokens,
                                     model_request_parameters=parameters) if split else 0
    fresh = 0
    for message in messages[split:]:
        if not isinstance(message, ModelRequest):
            continue
        if message.instructions:
            cached += count_text_tokens(message.instructions)
        for part in message.parts:
            if isinstance(part, SystemPromptPart):
                cached += count_text_tokens(part.content)
            elif isinstance(part, UserPromptPart):
                if isinstance(part.content, str):
                    fresh += count_text_tokens(part.content)
                else:
                    fresh += sum(count_text_tokens(item) for item in part.content if isinstance(item, str))
            elif isinstance(part, ToolReturnPart | RetryPromptPart):
                fresh += count_text_tokens(str(part.content))
    return RequestUsage(input_tokens=cached + fresh, cache_read_tokens=cached)


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
