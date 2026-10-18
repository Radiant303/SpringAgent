from pathlib import Path

from pydantic_ai_harness import Skills


def skills() -> Skills:
    """从 ~/.springagent/skills 目录加载技能。"""
    return Skills(Path.home() / ".springagent" / "skills")
