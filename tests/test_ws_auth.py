"""WebSocket 身份签名与访问令牌鉴权回归测试。"""

import hashlib
import hmac
import os
import time
from pathlib import Path

import pytest

os.environ.setdefault(
    "SPRINGAGENT_CONFIG", str(Path(__file__).resolve().parents[1] / "config.toml.example"),
)

from spring_agent.cloud import auth as auth_module
from spring_agent.cloud.ws import _gateway_identity
from spring_agent.core.config.settings import config


def _headers(secret: str, user_id: int = 123, expires_at: int | None = None) -> dict[str, str]:
    connection_id = "connection-1"
    expires_at = expires_at or int(time.time()) + 60
    payload = f"ws\n{user_id}\n{connection_id}\n{expires_at}".encode("utf-8")
    signature = hmac.new(secret.encode("utf-8"), payload, hashlib.sha256).hexdigest()
    return {
        "x-user-id": str(user_id),
        "x-connection-id": connection_id,
        "x-auth-expires": str(expires_at),
        "x-internal-signature": signature,
    }


def test_gateway_identity_accepts_valid_signature(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config.cloud, "internal_token", "test-internal-token")

    assert _gateway_identity(_headers("test-internal-token")) == (123, "connection-1")


def test_gateway_identity_rejects_tampering_and_expiry(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config.cloud, "internal_token", "test-internal-token")
    tampered = _headers("test-internal-token")
    tampered["x-user-id"] = "456"

    assert _gateway_identity(tampered) is None
    assert _gateway_identity(_headers("test-internal-token", expires_at=int(time.time()) - 1)) is None
    assert _gateway_identity(_headers("wrong-secret")) is None
    assert _gateway_identity({}) is None


def test_access_authentication_uses_one_registered_script(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = []

    def script(*, keys: list[str]) -> str:
        calls.append(keys)
        return "ok:42"

    monkeypatch.setattr(auth_module, "_authenticate_script", lambda: script)
    token = "00000000-0000-0000-0000-000000000000"

    result = auth_module.authenticate_access_token(token)

    assert result == auth_module.AccessAuthentication(user_id=42)
    token_hash = auth_module._token_hash(token)
    assert calls == [[f"auth:access:{token_hash}", f"auth:expired:{token_hash}"]]


@pytest.mark.parametrize(
    ("script_result", "failure"),
    [("expired", "token_expired"), ("invalid", "token_invalid"), ("ok:bad", "token_invalid")],
)
def test_access_authentication_maps_script_failures(
    monkeypatch: pytest.MonkeyPatch, script_result: str, failure: str,
) -> None:
    monkeypatch.setattr(auth_module, "_authenticate_script", lambda: lambda **kwargs: script_result)

    result = auth_module.authenticate_access_token("00000000-0000-0000-0000-000000000000")

    assert result.failure == failure
    assert result.user_id is None
