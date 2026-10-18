from collections.abc import Awaitable, Callable
from dataclasses import dataclass, replace

from pydantic_ai import ModelMessage, RunContext
from pydantic_ai_harness import SummarizingCompaction
from pydantic_ai_harness.compaction._shared import (
    estimate_token_count,
    find_safe_cutoff,
    find_token_cutoff,
)

OnCompaction = Callable[[int, int, int], Awaitable[None]]
"""压缩完成回调：(被折进摘要的消息条数, 压缩前估算 tokens, 压缩后估算 tokens)。"""

OnCompactionStart = Callable[[int], Awaitable[None]]
"""压缩开始回调：(压缩前估算 tokens)。"""


@dataclass
class NotifyingCompaction(SummarizingCompaction):
    on_compaction: OnCompaction | None = None
    on_compaction_start: OnCompactionStart | None = None

    async def compact(self, messages: list[ModelMessage], ctx: RunContext) -> list[ModelMessage]:
        from spring_harness.core.hooks.billing import RequestBillingModel

        if isinstance(ctx.model, RequestBillingModel):
            ctx = replace(ctx, model=ctx.model.for_agent("compaction"))
        before = estimate_token_count(messages, self.tokenizer)
        # 触发后仍可能空转（保留尾把 cutoff 顶回 0），真有内容可折才通知开始
        cutoff = self._cutoff(messages)
        if cutoff > 0 and self.on_compaction_start is not None:
            await self.on_compaction_start(before)
        result = await super().compact(messages, ctx)
        # 历史真变了才通知完成
        if self.on_compaction is not None and result != messages:
            # 条数取"被总结条数"（cutoff 之前的消息数），与 harness 回执的语义一致；
            # 不是 len 差值——摘要/首条保留会抵消掉几条，净减少对人不直观
            after = estimate_token_count(result, self.tokenizer)
            await self.on_compaction(cutoff, before, after)
        return result

    def _cutoff(self, messages: list[ModelMessage]) -> int:
        if self.keep_tokens is not None:
            return find_token_cutoff(messages, self.keep_tokens, self.tokenizer)
        return find_safe_cutoff(messages, self.keep_messages)
