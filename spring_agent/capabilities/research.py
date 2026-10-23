from pydantic_ai.capabilities import WebSearch

from spring_agent.core.agent.deps import CodingAgentDeps
from spring_agent.toolsets.search import web_search


def researcher(native: bool) -> WebSearch[CodingAgentDeps]:
    return WebSearch(native=native, local=web_search)
