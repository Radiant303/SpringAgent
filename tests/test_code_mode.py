"""Code Mode 工具说明回归测试。"""

from typing import cast

from pydantic_ai import RunContext
from pydantic_ai.tools import ToolDefinition

from spring_agent.capabilities.code_mode import _prepare_description


def test_run_code_description_requires_non_none_final_expression() -> None:
    tool = ToolDefinition(
        name="run_code",
        description=(
            "Base instructions.\n"
            "The following functions are available inside the sandbox. Call them directly.\n"
            "```python\n"
            "async def batch_read_file(path: str) -> str: ...\n"
            "```\n"
        ),
        parameters_json_schema={
            "type": "object",
            "properties": {"code": {"type": "string"}, "restart": {"type": "boolean"}},
        },
    )

    prepared = _prepare_description(cast(RunContext[object], None), [tool])

    description = prepared[0].description or ""
    assert "非 None 表达式" in description
    assert "仅赋值" in description
    assert "另起一行写 result" in description
    assert "async def batch_read_file" in description
