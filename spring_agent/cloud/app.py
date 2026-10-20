import asyncio
import os
import stat
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

from spring_agent.cloud.cache_crypto import CacheSecretCipher
from spring_agent.cloud.model_store import DbCatalog, load_model_fresh
from spring_agent.cloud.mq import TurnDispatcher, set_dispatcher
from spring_agent.cloud.registry import (
    CloudSessionRegistry,
    get_registry,
    set_registry,
)
from spring_agent.cloud.web_schema import DEFAULT_FRONTEND_DIR
from spring_agent.cloud.ws import CloudAppServer, ws_endpoint
from spring_agent.core.config.model import build_model, set_catalog
from spring_agent.core.config.settings import config
from spring_agent.core.config.thinking import thinking_capability
from spring_agent.core.filesystem import PAGE_DIRECTORY, grant_public_read
from spring_agent.core.log import logger


class TestModelRequest(BaseModel):
    model_id: str


class ControlRequest(BaseModel):
    user_id: int
    connection_id: str


class BusySessionsRequest(BaseModel):
    session_ids: list[str]


class SessionDeletionRequest(BaseModel):
    user_id: int
    token: str
    committed: bool = False


def _initialize_works(data_root: Path) -> None:
    works = data_root / "works"
    try:
        for directory in (*reversed(works.parents), works):
            try:
                info = directory.lstat()
            except FileNotFoundError:
                directory.mkdir(exist_ok=True, mode=0o755)
                info = directory.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                raise ValueError("存储路径包含链接")
            if not stat.S_ISDIR(info.st_mode):
                raise ValueError("存储路径不是目录")
        if not os.access(works, os.W_OK | os.X_OK):
            raise PermissionError("工作区目录不可写")
    except (OSError, ValueError) as e:
        raise RuntimeError(f"本地存储工作区初始化失败: {works}") from e


def _open_published_pages(data_root: Path) -> None:
    """公开页面的权限校正。"""
    works = data_root / "works"
    try:
        projects = list(works.iterdir())
    except OSError as error:
        logger.warning("公开页面权限校正失败，跳过本轮: {}", error)
        return
    grant_public_read(works, directory=True)
    for project in projects:
        if project.is_symlink():
            continue
        page = project / PAGE_DIRECTORY
        if page.is_symlink() or not page.is_dir():
            continue
        grant_public_read(page, directory=True, parents=works)
        for root, directories, files in os.walk(page):
            for name in directories:
                grant_public_read(Path(root) / name, directory=True)
            for name in files:
                grant_public_read(Path(root) / name)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    data_root = Path(config.cloud.data_root).expanduser().absolute()
    _initialize_works(data_root)
    if os.name != "nt":
        await asyncio.to_thread(_open_published_pages, data_root)
    cipher = CacheSecretCipher.load(data_root)
    catalog_cache = redis.from_url(config.cloud.redis_url, decode_responses=True)
    set_catalog(DbCatalog(catalog_cache, cipher))
    from spring_agent.cloud.settings_store import DbSettings
    from spring_agent.core.config.platform import set_platform_settings

    set_platform_settings(DbSettings(catalog_cache, cipher))

    # 进程级会话注册表 + Redis Stream 事件流（断线续传的前提）
    redis_client = aioredis.from_url(config.cloud.redis_url, decode_responses=True)
    from spring_agent.cloud.billing import BillingClient, set_billing_client

    billing = BillingClient(config.cloud.gateway_base_url, config.cloud.internal_token,
                            data_root / "billing-outbox.sqlite3")
    await billing.start()
    set_billing_client(billing)
    registry = CloudSessionRegistry(
        redis_client, idle_ttl=config.cloud.session_idle_ttl_seconds,
        max_idle_handles=config.cloud.session_max_idle_handles,
        sweep_interval=config.cloud.session_sweep_interval_seconds,
    )
    set_registry(registry)
    registry.start()

    dispatcher = TurnDispatcher(config.cloud.rabbitmq_url)

    async def connect_dispatcher() -> None:
        while True:
            try:
                await dispatcher.start()
            except Exception:
                logger.exception("MQ 连接失败，等待重试")
                await dispatcher.stop()
                await asyncio.sleep(5)
            else:
                set_dispatcher(dispatcher)
                await billing.retry_pending()
                return

    mq_worker = asyncio.create_task(connect_dispatcher())
    try:
        yield
    finally:
        mq_worker.cancel()
        await asyncio.gather(mq_worker, return_exceptions=True)
        set_registry(None)
        await registry.close_all()
        await billing.retry_pending()
        set_billing_client(None)
        await billing.close()
        set_dispatcher(None)
        await dispatcher.stop()
        await redis_client.aclose()
        catalog_cache.close()


def create_app() -> FastAPI:
    app = FastAPI(title="SpringAgent cloud", version="0.1.0", lifespan=lifespan)
    app.add_api_websocket_route("/ws", ws_endpoint)
    app.mount("/static", StaticFiles(directory=DEFAULT_FRONTEND_DIR), name="static")

    @app.get("/", include_in_schema=False)
    def index() -> FileResponse:
        return FileResponse(DEFAULT_FRONTEND_DIR / "index.html")

    @app.get("/internal/works/{work_id}/active-sessions")
    async def active_sessions(
        work_id: str, x_internal_token: str | None = Header(default=None),
    ) -> dict[str, int]:
        """查询项目内已挂载或仍在使用的会话数。"""
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

    @app.post("/internal/sessions/{session_id}/deletion/prepare")
    async def prepare_session_deletion(
        session_id: str, body: SessionDeletionRequest, x_internal_token: str | None = Header(default=None),
    ) -> dict:
        if x_internal_token != config.cloud.internal_token:
            raise HTTPException(status_code=401, detail="未认证")
        registry = get_registry()
        if registry is None:
            raise HTTPException(status_code=503, detail="会话服务暂不可用")
        from spring_agent.core.rpc.connection import JsonRpcError
        from spring_agent.core.rpc.server import BUSY

        try:
            await registry.prepare_deletion(session_id, body.user_id, body.token)
        except JsonRpcError as error:
            raise HTTPException(status_code=409 if error.code == BUSY else 404, detail=str(error)) from error
        return {}

    @app.post("/internal/sessions/{session_id}/deletion/finish")
    async def finish_session_deletion(
        session_id: str, body: SessionDeletionRequest, x_internal_token: str | None = Header(default=None),
    ) -> dict:
        if x_internal_token != config.cloud.internal_token:
            raise HTTPException(status_code=401, detail="未认证")
        registry = get_registry()
        if registry is None:
            raise HTTPException(status_code=503, detail="会话服务暂不可用")
        await registry.finish_deletion(session_id, body.user_id, body.token, body.committed)
        return {}

    @app.post("/internal/sessions/busy")
    async def busy_sessions(
        body: BusySessionsRequest, x_internal_token: str | None = Header(default=None),
    ) -> dict[str, list[str]]:
        if x_internal_token != config.cloud.internal_token:
            raise HTTPException(status_code=401, detail="未认证")
        registry = get_registry()
        return {"busy": [
            sid for sid in body.session_ids
            if registry is not None and (handle := registry.get(sid)) is not None and handle.busy
        ]}

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
