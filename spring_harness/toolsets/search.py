import asyncio
from typing import Annotated
from uuid import uuid4

from pydantic import Field
from pydantic_ai import RunContext

from spring_harness.core.agent.deps import CodingAgentDeps
from spring_harness.core.billing import BillingError
from spring_harness.core.config.platform import platform_settings
from spring_harness.core.hooks.billing import finish_shielded
from spring_harness.core.search import SearchResult, get_search_provider


async def web_search(
    ctx: RunContext[CodingAgentDeps],
    query: Annotated[str, Field(min_length=1, max_length=1000)],
    max_results: Annotated[int, Field(ge=1, le=10)] = 5,
) -> list[SearchResult] | str:
    """搜索网页，返回标题、链接与内容摘要。"""
    query = query.strip()
    if not query or len(query) > 1000:
        return "搜索词不能为空，且长度不能超过 1000 个字符"
    if isinstance(max_results, bool) or not isinstance(max_results, int) or not 1 <= max_results <= 10:
        return "搜索结果数量必须为 1 到 10 之间的整数"

    settings = await asyncio.to_thread(platform_settings().snapshot)
    if settings.get("search.local.enabled", "false").strip().lower() != "true":
        return "站长未启用平台搜索"
    provider_id = settings.get("search.local.provider", "tavily").strip().lower()
    provider = get_search_provider(provider_id)
    if provider is None:
        return "站长配置的平台搜索服务不受支持，请联系站长"
    api_key = settings.get(f"search.providers.{provider.id}.api_key", "").strip()
    if not api_key:
        return f"站长尚未配置{provider.name}搜索API key，请联系站长"

    try:
        results = await provider.search(query, max_results, api_key=api_key)
    except BillingError:
        raise
    except Exception:
        return "平台搜索服务暂时不可用，本次调用未记录搜索用量；请联系站长排查"

    billing = ctx.deps.billing
    if billing is not None:
        payload = {
            "eventId": uuid4().hex,
            "source": "local",
            "provider": provider.id,
            "searches": 1,
            "userId": billing.user_id,
            "sessionId": billing.session_id,
            "turnId": billing.turn_id,
            "requestId": None,
            "agentName": "searcher",
        }
        try:
            _, cancelled = await finish_shielded(billing.gateway.record_search(payload))
        except Exception:
            raise BillingError(
                "搜索已完成，但计费记录未能持久化，已停止后续操作；请联系站长核对，请勿重复发起搜索"
            ) from None
        if cancelled:
            raise asyncio.CancelledError
    return results
