from pydantic_ai.capabilities import WebSearch

from spring_agent.core.agent.deps import CodingAgentDeps
from spring_agent.toolsets.search import web_search


def researcher() -> WebSearch[CodingAgentDeps]:
    return WebSearch(local=web_search)
