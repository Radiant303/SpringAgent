"""JavaScript 语法校验回归测试。"""

import asyncio
from pathlib import Path
from types import SimpleNamespace
from typing import cast

import pytest
from pydantic_ai import FunctionToolset, ModelRetry, RunContext

from spring_agent.toolsets.syntax_validation import _javascript_location, validate_javascript_source


def test_validate_javascript_source_handles_long_files() -> None:
    content = 'const 值 = "😀";\n' * 5000 + "const = ;\n"

    result = validate_javascript_source(content)

    assert result["valid"] is False
    assert result["error_count"] == 1
    assert result["errors"][0]["line"] == 5001
    assert result["errors"][0]["column"] == 1


def test_javascript_location_clamps_byte_offsets() -> None:
    content = "const 值 = 1;\nconst b = 2;".encode("utf-8")
    second_line = "const 值 = 1;".encode("utf-8")
    line_starts = [0, len(second_line) + 1]

    assert _javascript_location(content, line_starts, len(content) + 100) == (2, 13)
    assert _javascript_location(content, line_starts, -1) == (1, 1)


def test_validate_js_converts_parser_errors_to_model_retry(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    config = Path(__file__).parents[1] / "config.toml.example"
    monkeypatch.setenv("SPRINGAGENT_CONFIG", str(config))
    monkeypatch.setenv("SPRINGAGENT_LOG_STDOUT", "1")
    import spring_agent.toolsets.filesystem as filesystem_module
    from spring_agent.core.agent.deps import CodingAgentDeps

    (tmp_path / "page.js").write_text("const value = 1;", encoding="utf-8")
    toolset = filesystem_module.filesystem(str(tmp_path), read_only=True)
    assert isinstance(toolset.wrapped, FunctionToolset)
    tool = toolset.wrapped.tools["validate_js"]
    ctx = cast(RunContext[CodingAgentDeps], SimpleNamespace(deps=CodingAgentDeps.create_default(tmp_path)))

    def fail_validation(_: str):
        raise IndexError("list index out of range")

    monkeypatch.setattr(filesystem_module, "validate_javascript_source", fail_validation)
    with pytest.raises(ModelRetry, match="语法校验暂时失败"):
        asyncio.run(tool.function(ctx, "page.js"))
