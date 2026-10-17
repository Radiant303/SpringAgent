import asyncio
from typing import Protocol, TypedDict

from exa_py import AsyncExa
from tavily import AsyncTavilyClient


class SearchResult(TypedDict):
    title: str
    url: str
    content: str


class SearchProvider(Protocol):
    id: str
    name: str

    async def search(self, query: str, max_results: int, *, api_key: str) -> list[SearchResult]:
        ...


def _result(title: str | None, url: str | None, content: str | None) -> SearchResult:
    return {
        "title": (title or "")[:300],
        "url": (url or "")[:2048],
        "content": (content or "")[:1000],
    }


class TavilySearchProvider:
    id = "tavily"
    name = "Tavily"

    async def search(self, query: str, max_results: int, *, api_key: str) -> list[SearchResult]:
        async with asyncio.timeout(30):
            async with AsyncTavilyClient(api_key=api_key) as client:
                response = await client.search(
                    query,
                    search_depth="basic",
                    max_results=max_results,
                    include_answer=False,
                    include_raw_content=False,
                    timeout=25,
                )
        return [
            _result(item.get("title"), item.get("url"), item.get("content"))
            for item in response.get("results", [])[:max_results]
        ]


class ExaSearchProvider:
    id = "exa"
    name = "Exa"

    async def search(self, query: str, max_results: int, *, api_key: str) -> list[SearchResult]:
        client = AsyncExa(api_key=api_key)
        try:
            async with asyncio.timeout(30):
                response = await client.search(
                    query,
                    type="auto",
                    num_results=max_results,
                    contents={"highlights": {"max_characters": 1000}},
                )
        finally:
            await client.client.aclose()
        return [
            _result(item.title, item.url, "\n".join(item.highlights or []))
            for item in response.results[:max_results]
        ]


SEARCH_PROVIDERS: dict[str, SearchProvider] = {
    "tavily": TavilySearchProvider(),
    "exa": ExaSearchProvider(),
}


def get_search_provider(provider_id: str) -> SearchProvider | None:
    return SEARCH_PROVIDERS.get(provider_id)
