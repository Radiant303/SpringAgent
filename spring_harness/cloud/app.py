import asyncio
import time
from pathlib import Path
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import redis
import redis.asyncio as aioredis
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from pydantic_ai import Agent

from spring_harness.cloud.model_store import DbCatalog, load_model_fresh
from spring_harness.cloud.mq import TurnDispatcher, set_dispatcher
from spring_harness.cloud.registry import (
    CloudSessionRegistry,
    get_registry,
    set_registry,
)
from spring_harness.cloud.ws import CloudAppServer, ws_endpoint
from spring_harness.core.config.model import build_model, set_catalog
from spring_harness.core.config.settings import config
from spring_harness.core.config.thinking import thinking_capability
from spring_harness.core.log import logger
from spring_harness.core.services.web_server import DEFAULT_FRONTEND_DIR


class TestModelRequest(BaseModel):
    model_id: str


class ControlRequest(BaseModel):
    user_id: int
    connection_id: str


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    # 模型配置来源切到 DB；Redis 快照缓存 + 写后延迟双删。
    # catalog 用同步客户端（模型解析在同步代码路径上），与下方异步事件流客户端互不干扰
    catalog_cache = redis.from_url(config.cloud.redis_url, decode_responses=True)
    set_catalog(DbCatalog(catalog_cache))
    # system_settings 同样走 Redis 快照（平台域名等热点配置）
    from spring_harness.cloud.settings_store import DbSettings
    from spring_harness.core.config.platform import set_platform_settings

    set_platform_settings(DbSettings(catalog_cache))

    # 进程级会话注册表 + Redis Stream 事件流（断线续传的前提）
    redis_client = aioredis.from_url(config.cloud.redis_url, decode_responses=True)
    from spring_harness.cloud.billing import BillingClient, set_billing_client

    billing = BillingClient(config.cloud.gateway_base_url, config.cloud.internal_token,
                            Path(config.cloud.data_root).expanduser() / "billing-outbox.sqlite3")
    await billing.start()
    set_billing_client(billing)
    registry = CloudSessionRegistry(redis_client)
    set_registry(registry)

    # MQ 消费者：连接失败不阻断启动（turn/start 另有 WS 透传兜底），仅缺席生命周期回传
    dispatcher = TurnDispatcher(config.cloud.rabbitmq_url)
    try:
        await dispatcher.start()
    except Exception:
        logger.exception("MQ 控制面连接失败，turn 派发不可用（可回退 WS 透传）")
        set_dispatcher(None)
    else:
        set_dispatcher(dispatcher)
    try:
        yield
    finally:
        set_dispatcher(None)
        await dispatcher.stop()
        set_registry(None)
        await registry.close_all()
        await billing.retry_pending()
        set_billing_client(None)
        await billing.close()
        await redis_client.aclose()
        catalog_cache.close()


def create_app() -> FastAPI:
    app = FastAPI(title="spring-harness cloud", version="0.1.0", lifespan=lifespan)
    app.add_api_websocket_route("/ws", ws_endpoint)
    app.mount("/static", StaticFiles(directory=DEFAULT_FRONTEND_DIR), name="static")

    @app.get("/", include_in_schema=False)
    def index() -> FileResponse:
        return FileResponse(DEFAULT_FRONTEND_DIR / "index.html")

    @app.get("/internal/works/{work_id}/active-sessions")
    def active_sessions(
        work_id: str, x_internal_token: str | None = Header(default=None),
    ) -> dict[str, int]:
        """网关删除 work 前的活跃校验：进程内注册（含在跑的 turn）即活跃。"""
        if x_internal_token != config.cloud.internal_token:
            raise HTTPException(status_code=401, detail="未认证")
        registry = get_registry()
        active = registry.active_count_in_work(work_id) if registry is not None else 0
        return {"active": active}

    @app.post("/internal/sessions/{session_id}/control")
    async def session_control(
        session_id: str, body: ControlRequest, x_internal_token: str | None = Header(default=None),
    ) -> dict:
        if x_internal_token != config.cloud.internal_token:
            raise HTTPException(status_code=401, detail="未认证")
        token = CloudAppServer.control_token(session_id, body.user_id, body.connection_id)
        return {"authorized": token is not None, "controlToken": token}

    @app.post("/internal/models/test")
    async def test_model(
        body: TestModelRequest, x_internal_token: str | None = Header(default=None),
    ) -> dict:
        """管理后台测试模型连接：真实构造模型并发一次最小请求，返回延迟或错误。"""
        if x_internal_token != config.cloud.internal_token:
            raise HTTPException(status_code=401, detail="未认证")

        loaded = load_model_fresh(body.model_id)
        if loaded is None:
            return {"ok": False, "error": f"模型 '{body.model_id}' 不存在"}
        model_config, provider_config = loaded

        started = time.monotonic()
        try:
            agent = Agent(build_model(model_config, provider_config),
                          capabilities=[thinking_capability(model_config)])
            await asyncio.wait_for(agent.run("say hi"), timeout=30)
        except asyncio.TimeoutError:
            return {"ok": False, "error": "测试超时（30s）"}
        except Exception as e:
            logger.warning("模型 {} 连接测试失败: {}", body.model_id, e)
            return {"ok": False, "error": str(e)}
        return {"ok": True, "latency_ms": int((time.monotonic() - started) * 1000)}

    return app
