"""页面托管工具：page_url 返回当前项目 page/ 目录的公网访问地址。"""

from pydantic_ai import FunctionToolset

from spring_harness.core.config.platform import (
    KEY_PLATFORM_DOMAIN,
    KEY_PLATFORM_HTTPS,
    platform_settings,
)


def page_site_toolset(*, page_id: int) -> FunctionToolset:
    """按会话构建工具集。"""

    async def page_url() -> str:
        """获取当前项目 page/ 目录的公网访问地址。

        page/ 目录下的静态文件会被挂载到公网：入口固定是 page/index.html，
        返回的地址以斜杠结尾，访问它即打开入口页；子文件与子目录按相对
        路径访问，以斜杠结尾的子目录地址返回其中的 index.html。
        写好或更新页面后，调用本工具获取地址发给用户；不要自己拼接地址。

        Returns:
            带结尾斜杠的页面访问地址
        """
        domain = platform_settings().get(KEY_PLATFORM_DOMAIN).strip()
        if not domain:
            return "站长未配置平台域名，无法生成页面访问地址，请联系站长"
        scheme = "https" if platform_settings().get(KEY_PLATFORM_HTTPS).strip().lower() == "true" else "http"
        return f"{scheme}://{domain}/page/{page_id}/"

    return FunctionToolset(tools=[page_url])
