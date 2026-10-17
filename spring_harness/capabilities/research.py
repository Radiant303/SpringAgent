from pydantic_ai.capabilities import WebSearch

from spring_harness.core.agent.deps import CodingAgentDeps
from spring_harness.toolsets.search import web_search


def researcher() -> WebSearch[CodingAgentDeps]:
    return WebSearch(local=web_search)
