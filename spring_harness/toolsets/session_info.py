"""当前会话信息工具：模型不靠上下文也能确认自己在哪个会话/工作区工作。"""

from pathlib import Path

from pydantic_ai import FunctionToolset


def session_info_toolset(*, session_id: str, workspace: Path,
                         model_id: str, effort: str | None) -> FunctionToolset:
    """按会话构建工具集：会话事实在创建 agent 时固化进闭包。"""

    async def current_session() -> dict[str, str]:
        """查看当前会话的基本信息。

        返回当前会话 ID、项目工作区路径、当前模型与推理档位。
        需要定位会话文件时，先用返回的会话 ID 确认对应目录。

        Returns:
            会话信息字典
        """
        info = {
            "session_id": session_id,
            "workspace": str(workspace),
            "model": model_id,
        }
        if effort:
            info["effort"] = effort
        return info

    return FunctionToolset(tools=[current_session])
