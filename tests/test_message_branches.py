"""消息分支与重试输入回归测试。"""

import asyncio
import os
from contextlib import asynccontextmanager
from pathlib import Path

from pydantic_ai import Agent, ModelRequest, RequestUsage, TextPart, UserPromptPart
from pydantic_ai.models.test import TestModel

os.environ.setdefault(
    "SPRINGAGENT_CONFIG", str(Path(__file__).resolve().parents[1] / "config.toml.example"),
)

from spring_agent.cloud.gateway_store import _dump_message, _load_stored_message
from spring_agent.cloud.web_schema import conversation_limits
from spring_agent.core.session import AgentSession
from spring_agent.core.stream.events import TurnFinished


class CaptureModel(TestModel):
    def __init__(self) -> None:
        super().__init__(call_tools=[])
        self.requests = []

    async def request(self, messages, model_settings, model_request_parameters):
        self.requests.append(list(messages))
        response = await super().request(messages, model_settings, model_request_parameters)
        response.usage = RequestUsage(input_tokens=1, output_tokens=1)
        return response

    @asynccontextmanager
    async def request_stream(self, messages, model_settings, model_request_parameters, run_context=None):
        self.requests.append(list(messages))
        async with super().request_stream(
            messages, model_settings, model_request_parameters, run_context,
        ) as stream:
            yield stream


class BranchStore:
    def __init__(self) -> None:
        self.session_id = "s1"
        self.messages = []
        self.prepare_args = None
        self.selected = None

    def prepare_turn(self, text, *, action="normal", branch_id=None):
        self.prepare_args = (text, action, branch_id)
        return "original question" if action == "retry" else text

    def select_branch(self, branch_id):
        self.selected = branch_id

    def load_messages(self):
        return list(self.messages)

    def append(self, messages):
        self.messages.extend(messages)

    def append_rewritten(self, messages):
        self.messages = list(messages)


def test_retry_reuses_original_question(tmp_path: Path) -> None:
    async def run() -> None:
        store = BranchStore()
        model = CaptureModel()
        session = AgentSession(tmp_path, store=store, agent=Agent(model))

        await session.run_turn("ignored", action="retry", branch_id="node-1")

        assert store.prepare_args == ("ignored", "retry", "node-1")
        while True:
            event = await session._queue.get()
            if isinstance(event, TurnFinished):
                assert not event.error, event.error
                break
        request = model.requests[-1][-1]
        assert isinstance(request, ModelRequest)
        assert request.parts[-1].content == "original question"
        assert store.messages[-1].parts[-1].content == "success (no tool calls)"

    asyncio.run(run())


def test_select_branch_invalidates_cached_history(tmp_path: Path) -> None:
    async def run() -> None:
        store = BranchStore()
        session = AgentSession(tmp_path, store=store, agent=Agent(TestModel()))
        await session.run_turn("hello")
        assert session._history_loaded

        await session.select_branch("node-2")

        assert store.selected == "node-2"
        assert not session._history_loaded
        assert session.history == []

    asyncio.run(run())


def test_stored_messages_keep_branch_marker_during_rewrite() -> None:
    original = ModelRequest(parts=[UserPromptPart("hello")])
    item = {"payload": _dump_message(original), "nodeId": "node-1", "legacyId": 0}

    loaded = _load_stored_message(item)

    assert loaded.metadata["_springAgentStored"] is True
    assert loaded.metadata["_springAgentNodeId"] == "node-1"
    dumped = _dump_message(loaded)
    assert dumped["metadata"]["_springAgentStored"] is True
    assert dumped["metadata"]["_springAgentNodeId"] == "node-1"
    assert dumped["metadata"]["_springAgentLegacyId"] == 0
    assert dumped["parts"][0]["content"] == "hello"


def test_conversation_limits_default_and_clamp(monkeypatch) -> None:
    class Settings:
        def __init__(self, values):
            self.values = values

        def snapshot(self):
            return self.values

    import spring_agent.core.config.platform as platform

    monkeypatch.setattr(platform, "_settings", Settings({}))
    assert conversation_limits() == {"message_edit_limit": 6, "answer_retry_limit": 6}
    monkeypatch.setattr(platform, "_settings", Settings({
        "message_edit_limit": "0", "answer_retry_limit": "99",
    }))
    assert conversation_limits() == {"message_edit_limit": 0, "answer_retry_limit": 6}
