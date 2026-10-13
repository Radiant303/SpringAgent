import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import httpx
import pytest

from spring_harness.cloud.app import create_app
from spring_harness.cloud.mq import TurnDispatcher
from spring_harness.cloud.registry import CloudSessionHandle, CloudSessionRegistry, set_registry
from spring_harness.cloud.ws import (
    CloudAppServer, SESSION_NOT_FOUND, SESSION_OCCUPIED, SESSION_REPLACED,
)
from spring_harness.core.config.settings import config
from spring_harness.core.rpc.connection import JsonRpcError
from spring_harness.core.rpc.schema import AttachParams


@pytest.fixture(autouse=True)
def reset_owners():
    CloudAppServer._active_owners.clear()
    set_registry(None)
    yield
    CloudAppServer._active_owners.clear()
    set_registry(None)


def server(user=1, connection_id="a"):
    connection = SimpleNamespace(send_notification=AsyncMock(), discard_response=Mock())
    closed = AsyncMock()
    result = CloudAppServer(connection, user_id=user, data_root=Path.cwd(),
                            connection_id=connection_id, close_connection=closed)
    return result, connection, closed


def handle():
    session = SimpleNamespace(session_id="session-1", busy=False, set_model=Mock(),
                              respond=Mock(), cancel=Mock(), cloud_user_id=None)
    result = Mock(spec=CloudSessionHandle)
    result.session_id = "session-1"
    result.session = session
    result.busy = False
    result.approval_target = None

    def target(value):
        result.approval_target = value
    result.set_approval_target.side_effect = target
    return result


def registry_with(h):
    registry = CloudSessionRegistry(None)
    registry._handles[h.session_id] = h
    set_registry(registry)
    return registry


def test_takeover_replaces_owner_without_cancelling_running_turn():
    async def run():
        old, connection, closed = server()
        new, _, _ = server(connection_id="b")
        h = handle()
        registry_with(h)
        old._adopt(h, None)
        old_token = old.control_token(h.session_id, 1, "a")
        h.busy = True
        h.session.busy = True
        old._inflight_requests["approval-1"] = (h.session_id, "approval")
        old._inflight_requests["question-1"] = (h.session_id, "question")
        with pytest.raises(JsonRpcError) as failure:
            new._adopt(h, None)
        assert failure.value.code == SESSION_OCCUPIED
        assert old.control_token(h.session_id, 1, "a") == old_token
        new._adopt(h, "different-model", takeover=True)
        assert CloudAppServer._active_owners[h.session_id] is new
        assert not old.controls(h.session_id, 1, old_token)
        assert new.control_token(h.session_id, 1, "b") != old_token
        h.session.cancel.assert_not_called()
        h.session.set_model.assert_not_called()
        h.session.respond.assert_any_call("approval-1", False)
        h.session.respond.assert_any_call("question-1", None)
        with pytest.raises(JsonRpcError) as rejected:
            await old.handle_request("turn/cancel", {"sessionId": h.session_id})
        assert rejected.value.code == SESSION_REPLACED
        await old.shutdown()
        assert CloudAppServer._active_owners[h.session_id] is new
        assert h.approval_target is new
        await asyncio.sleep(0)
        connection.send_notification.assert_awaited_once_with("session/replaced", {"sessionId": h.session_id})
        closed.assert_awaited_once_with(4409)
        await new.shutdown()
    asyncio.run(run())


def test_cached_session_still_checks_user_and_database_status():
    async def run():
        owner, _, _ = server()
        other, _, _ = server(user=2, connection_id="other")
        h = handle()
        registry_with(h)
        owner._adopt(h, None)
        with patch("spring_harness.cloud.ws.find_active_session", return_value=None) as find:
            with pytest.raises(JsonRpcError) as rejected:
                await other._session_attach(AttachParams(workspace=".", session_id=h.session_id, takeover=True))
            assert rejected.value.code == SESSION_NOT_FOUND
            find.assert_called_once_with(h.session_id, 2)
        assert CloudAppServer._active_owners[h.session_id] is owner
        await owner.shutdown()
    asyncio.run(run())


def test_losing_attach_preserves_previous_session():
    async def run():
        first, _, _ = server()
        second, _, _ = server(connection_id="second")
        a, b = handle(), handle()
        b.session_id = "session-2"
        b.session.session_id = "session-2"
        registry = registry_with(a)
        registry._handles[b.session_id] = b
        first._adopt(a, None)
        second._adopt(b, None)
        with pytest.raises(JsonRpcError):
            second._adopt(a, None)
        assert second._current_session == b.session_id
        assert b.approval_target is second
        await first.shutdown()
        await second.shutdown()
    asyncio.run(run())


def message(payload):
    return SimpleNamespace(body=json.dumps(payload).encode(), ack=AsyncMock(), nack=AsyncMock())


def test_mq_stale_dispatch_and_cancel_are_fenced():
    async def run():
        old, _, _ = server()
        new, _, _ = server(connection_id="b")
        h = handle()
        registry_with(h)
        old._adopt(h, None)
        old_token = old.control_token(h.session_id, 1, "a")
        new._adopt(h, None, takeover=True)
        token = new.control_token(h.session_id, 1, "b")
        dispatcher = TurnDispatcher("unused")
        dispatcher.publish_lifecycle = AsyncMock()
        payload = {"turnId": "turn-old", "sessionId": h.session_id, "userId": 1, "controlToken": old_token, "input": "hi"}
        stale = message(payload)
        await dispatcher._on_dispatch(stale)
        h.start_turn.assert_not_called()
        assert dispatcher.publish_lifecycle.call_args.args[0]["usage"] == []
        stale.ack.assert_awaited_once()
        await dispatcher._on_cancel(message(payload))
        h.cancel_turn.assert_not_called()
        await dispatcher._on_dispatch(message({**payload, "turnId": "turn-new", "controlToken": token}))
        h.start_turn.assert_called_once_with("hi", turn_id="turn-new", user_id=1)
        await dispatcher._on_cancel(message({**payload, "controlToken": token}))
        h.cancel_turn.assert_called_once()
        await asyncio.sleep(0)
        await new.shutdown()
    asyncio.run(run())


def test_internal_control_requires_secret_and_matching_connection():
    async def run():
        owner, _, _ = server()
        h = handle()
        registry_with(h)
        owner._adopt(h, None)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app()), base_url="http://test") as client:
            path = f"/internal/sessions/{h.session_id}/control"
            body = {"user_id": 1, "connection_id": "a"}
            assert (await client.post(path, json=body)).status_code == 401
            headers = {"X-Internal-Token": config.cloud.internal_token}
            authorized = (await client.post(path, json=body, headers=headers)).json()
            assert authorized["authorized"]
            assert authorized["controlToken"] == owner.control_token(h.session_id, 1, "a")
            for value in ({"user_id": 2, "connection_id": "a"}, {"user_id": 1, "connection_id": "other"}):
                assert not (await client.post(path, json=value, headers=headers)).json()["authorized"]
        await owner.shutdown()
    asyncio.run(run())


def test_registry_constructs_single_runtime_under_concurrency(tmp_path):
    async def run():
        registry = CloudSessionRegistry(None)
        fake = SimpleNamespace(session_id="session-1")
        store = Mock()
        with patch("spring_harness.cloud.registry.HarnessSession", return_value=fake) as factory, \
                patch("spring_harness.cloud.registry.CloudSessionHandle", return_value=handle()):
            handles = await asyncio.gather(*[
                registry.get_or_create("session-1", workspace=tmp_path, store=store) for _ in range(5)
            ])
            factory.assert_called_once()
            assert all(item is handles[0] for item in handles)
    asyncio.run(run())


def test_handle_prevents_duplicate_start_before_task_runs():
    async def run():
        gate = asyncio.Event()
        session = SimpleNamespace(session_id="session-1", busy=False, cloud_user_id=None, cancel=Mock())
        async def run_turn(*args, **kwargs):
            session.busy = True
            await gate.wait()
            session.busy = False
        async def events():
            await asyncio.Event().wait()
            yield None
        session.run_turn = run_turn
        session.events = events
        h = CloudSessionHandle(session, None)
        h.start_turn("hi", turn_id="t1", user_id=1)
        assert h.busy
        with pytest.raises(JsonRpcError):
            h.start_turn("duplicate", turn_id="t2", user_id=1)
        await asyncio.sleep(0)
        gate.set()
        await asyncio.sleep(0)
        assert not h.busy
        h._fanout_task.cancel()
        await asyncio.gather(h._fanout_task, return_exceptions=True)
    asyncio.run(run())


def test_running_task_survives_old_connection_shutdown():
    async def run():
        gate = asyncio.Event()
        session = SimpleNamespace(session_id="session-1", busy=False, cloud_user_id=None,
                                  cancel=Mock(), respond=Mock(), set_model=Mock(), close=AsyncMock())
        async def run_turn(*args, **kwargs):
            session.busy = True
            await gate.wait()
            session.busy = False
        async def events():
            await asyncio.Event().wait()
            yield None
        session.run_turn = run_turn
        session.events = events
        h = CloudSessionHandle(session, None)
        registry_with(h)
        old, _, _ = server()
        new, _, _ = server(connection_id="b")
        old._adopt(h, None)
        h.start_turn("hi", turn_id="active-turn", user_id=1)
        await asyncio.sleep(0)
        new._adopt(h, "other-model", takeover=True)
        await old.shutdown()
        assert h.busy
        session.cancel.assert_not_called()
        session.close.assert_not_called()
        session.set_model.assert_not_called()
        status = await new.handle_request("session/status", {"sessionId": h.session_id})
        assert status["busy"]
        gate.set()
        await asyncio.sleep(0)
        assert not h.busy
        await new.shutdown()
        await h.close()
    asyncio.run(run())


def test_pending_cancel_emits_lifecycle_without_model_request():
    async def run():
        async def events():
            await asyncio.Event().wait()
            yield None
        session = SimpleNamespace(session_id="session-1", busy=False, cloud_user_id=None,
                                  cancel=Mock(), run_turn=AsyncMock(), close=AsyncMock(), events=events)
        h = CloudSessionHandle(session, None)
        h._publish_lifecycle = AsyncMock()
        h._write_stream = AsyncMock()
        h.start_turn("hi", turn_id="pending-turn", user_id=1)
        h.cancel_turn()
        await asyncio.sleep(0)
        session.run_turn.assert_not_awaited()
        assert not h.busy
        event = h._publish_lifecycle.call_args.args[0]
        assert event.cancelled and event.turn_id == "pending-turn"
        await h.close()
    asyncio.run(run())


def test_approval_post_in_flight_cannot_bind_to_replaced_connection():
    async def run():
        started, gate = asyncio.Event(), asyncio.Event()
        old, connection, _ = server()
        async def post(*args, **kwargs):
            started.set()
            await gate.wait()
        connection.post_request = AsyncMock(side_effect=post)
        new, _, _ = server(connection_id="b")
        h = handle()
        registry_with(h)
        old._adopt(h, None)
        event = SimpleNamespace(request_id="approval-race", tool_call_id="call", tool_name="write",
                                args={}, diff=None)
        posting = asyncio.create_task(old._post_approval(h.session, event))
        await started.wait()
        new._adopt(h, None, takeover=True)
        gate.set()
        await posting
        h.session.respond.assert_called_once_with("approval-race", False)
        connection.discard_response.assert_called_once_with("approval-race")
        assert not old._inflight_requests
        await old.shutdown()
        await new.shutdown()
        await asyncio.sleep(0)
    asyncio.run(run())


def test_failed_approval_transport_does_not_break_fanout():
    async def run():
        from spring_harness.core.stream.events import ApprovalRequest
        async def events():
            await asyncio.Event().wait()
            yield None
        session = SimpleNamespace(session_id="session-1", respond=Mock(), close=AsyncMock(), events=events)
        h = CloudSessionHandle(session, None)
        target = SimpleNamespace(post_session_request=AsyncMock(side_effect=ConnectionError("closed")))
        h.set_approval_target(target)
        event = ApprovalRequest(request_id="request", tool_call_id="call", tool_name="write", args={}, diff=None)
        await h._route_request(event)
        session.respond.assert_called_once_with("request", False)
        assert not h._fanout_task.done()
        await h.close()
    asyncio.run(run())


def test_rpc_pending_responses_are_discarded():
    async def run():
        from spring_harness.core.rpc.connection import JsonRpcConnection
        connection = JsonRpcConnection(AsyncMock(), AsyncMock())
        await connection.post_request("approval/request", {}, rid="approval")
        pending = connection._pending["approval"]
        connection.discard_response("approval")
        assert pending.cancelled() and not connection._pending
        connection._write_line = AsyncMock(side_effect=ConnectionError("closed"))
        with pytest.raises(ConnectionError):
            await connection.post_request("question/request", {}, rid="question")
        assert not connection._pending
    asyncio.run(run())
