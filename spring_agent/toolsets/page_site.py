"""页面托管工具：page_url 返回当前项目 page/ 目录的公网访问地址。"""

from pydantic_ai import FunctionToolset

from spring_agent.core.config.platform import (
    KEY_PLATFORM_DOMAIN,
    KEY_PLATFORM_HTTPS,
    platform_settings,
)


def page_site_toolset(*, page_id: int) -> FunctionToolset:
    """按会话构建工具集。"""

    async def page_url() -> str:
        """获取当前项目 page/ 目录的公网访问地址。

        用于用户已授权公开的页面，入口为 page/index.html，子资源按相对路径访问。
        返回地址以斜杠结尾，可直接分享给用户。页面内容通过文件工具单独确认。

        Returns:
            带结尾斜杠的页面访问地址
        """
        domain = platform_settings().get(KEY_PLATFORM_DOMAIN).strip()
        if not domain:
            return "站长未配置平台域名，无法生成页面访问地址，请联系站长"
        scheme = "https" if platform_settings().get(KEY_PLATFORM_HTTPS).strip().lower() == "true" else "http"
        return f"{scheme}://{domain}/page/{page_id}/"

    return FunctionToolset(tools=[page_url])
