"""Redis 登录会话鉴权。"""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from functools import lru_cache
from typing import Annotated, Literal

import redis
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session, sessionmaker

from spring_agent.cloud.db import UserRow, get_sessionmaker
from spring_agent.core.config.settings import config

_bearer_scheme = HTTPBearer(auto_error=False)


@dataclass(frozen=True)
class AccessAuthentication:
    """访问令牌鉴权结果。"""

    user_id: int | None = None
    failure: Literal["token_expired", "token_invalid"] | None = None


@lru_cache(maxsize=1)
def get_auth_redis() -> redis.Redis:
    """获取鉴权 Redis 客户端。"""
    return redis.from_url(config.cloud.redis_url, decode_responses=True, protocol=2)


def _token_hash(token: str) -> str | None:
    try:
        parsed = uuid.UUID(token)
    except (ValueError, AttributeError):
        return None
    if str(parsed) != token.lower():
        return None
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def authenticate_access_token(token: str) -> AccessAuthentication:
    """校验访问令牌及其用户会话版本。"""
    token_hash = _token_hash(token)
    if token_hash is None:
        return AccessAuthentication(failure="token_invalid")
    cache = get_auth_redis()
    raw = cache.get(f"auth:access:{token_hash}")
    if raw is None:
        failure = "token_expired" if cache.exists(f"auth:expired:{token_hash}") else "token_invalid"
        return AccessAuthentication(failure=failure)
    try:
        value = json.loads(raw)
        user_id = int(value["userId"])
        version = str(value["version"])
    except (json.JSONDecodeError, KeyError, TypeError, ValueError):
        return AccessAuthentication(failure="token_invalid")
    if cache.get(f"auth:version:{user_id}") != version:
        return AccessAuthentication(failure="token_invalid")
    return AccessAuthentication(user_id=user_id)


def get_session_factory() -> sessionmaker[Session]:
    """获取数据库会话工厂。"""
    return get_sessionmaker()


def get_current_user(
    factory: Annotated[sessionmaker[Session], Depends(get_session_factory)],
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer_scheme)],
) -> UserRow:
    """加载访问令牌对应的活跃用户。"""
    if credentials is None or credentials.scheme.lower() != "bearer":
        authentication = AccessAuthentication(failure="token_invalid")
    else:
        authentication = authenticate_access_token(credentials.credentials)
    if authentication.failure is not None or authentication.user_id is None:
        failure = authentication.failure or "token_invalid"
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED,
            "登录已过期" if failure == "token_expired" else "登录状态无效",
            headers={"WWW-Authenticate": "Bearer", "X-Auth-Error": failure},
        )
    with factory() as s:
        user = s.get(UserRow, authentication.user_id)
    if user is None:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED, "登录状态无效",
            headers={"WWW-Authenticate": "Bearer", "X-Auth-Error": "token_invalid"},
        )
    if user.status != "active":
        raise HTTPException(status.HTTP_403_FORBIDDEN, "账号已被禁用")
    return user
