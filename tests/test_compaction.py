"""上下文压缩历史写回归测试。"""

import asyncio

from pydantic_ai import Agent, ModelMessage, ModelRequest, ModelResponse, RequestUsage, TextPart, UserPromptPart
from pydantic_ai.models.test import TestModel

from spring_agent.capabilities.compaction import NotifyingCompaction


def _has_summary(messages: list[ModelMessage]) -> bool:
    return any(
        "Summary of previous conversation:" in str(getattr(part, "content", ""))
        for message in messages if isinstance(message, ModelRequest)
        for part in message.parts
    )


class CaptureModel(TestModel):
    def __init__(self) -> None:
        super().__init__(call_tools=[])
        self.requests: list[list[ModelMessage]] = []

    async def request(self, messages, model_settings, model_request_parameters):
        self.requests.append(list(messages))
        response = await super().request(messages, model_settings, model_request_parameters)
        response.usage = RequestUsage(input_tokens=20 if _has_summary(messages) else 500_000, output_tokens=5)
        return response


def test_compacted_history_is_reused_without_repeated_compaction() -> None:
    async def run() -> None:
        model = CaptureModel()
        started: list[int] = []

        async def on_start(before: int) -> None:
            started.append(before)

        agent = Agent(
            model=model,
            capabilities=[NotifyingCompaction(max_tokens=1000, keep_tokens=1, on_compaction_start=on_start)],
        )
        history: list[ModelMessage] = [
            ModelRequest(parts=[UserPromptPart("old task")]),
            ModelResponse(
                parts=[TextPart("old answer")],
                usage=RequestUsage(input_tokens=500_000, output_tokens=100),
            ),
        ]
        first = await agent.run("small new turn", message_history=history)

        assert _has_summary(model.requests[-1])
        assert _has_summary(first.all_messages())
        assert len(started) == 1

        second = await agent.run("next small turn", message_history=first.all_messages())
        third = await agent.run("another small turn", message_history=second.all_messages())

        assert _has_summary(third.all_messages())
        assert len(started) == 1
        assert len(model.requests) == 4

    asyncio.run(run())
