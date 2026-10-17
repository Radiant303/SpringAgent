"""图片读取工具：view_url_image 读公网直链，view_local_image 读本站上传的图片。"""

import mimetypes
import re
from urllib.parse import urlparse

import httpx
from pydantic_ai import FunctionToolset, ImageUrl, ModelRetry, ToolReturn

from spring_harness.core.config.platform import (
    KEY_PLATFORM_DOMAIN,
    KEY_PLATFORM_HTTPS,
    platform_settings,
)

_PROBE_TIMEOUT_SECONDS = 10

# 本站上传文件的扁平文件名：{雪花 ID}.{ext}
_LOCAL_FILENAME = re.compile(r"\d{1,19}\.[A-Za-z0-9]{1,10}")


async def _probe_image_headers(url: str) -> str:
    """只读响应头探活，返回服务端声明的图片类型"""
    parsed = urlparse(url)
    headers = {"Referer": f"{parsed.scheme}://{parsed.netloc}/"}
    try:
        async with httpx.AsyncClient(timeout=_PROBE_TIMEOUT_SECONDS,
                                     follow_redirects=True) as client:
            async with client.stream("GET", url, headers=headers) as response:
                if response.status_code != 200:
                    raise ModelRetry(f"图片地址返回 HTTP {response.status_code}，无法读取")
                content_type = response.headers.get("content-type", "").split(";")[0].strip().lower()
                if not content_type.startswith("image/"):
                    raise ModelRetry("该地址返回的内容不是图片，请传入图片直链")
                if "content-length" not in response.headers:
                    raise ModelRetry("图片响应缺少 Content-Length 头，服务商无法抓取，请换用其它图片直链")
                return content_type
    except httpx.HTTPError:
        raise ModelRetry("图片地址无法访问，请确认地址可公开访问") from None


async def view_url_image(url: str) -> ToolReturn:
    """查看一张公网图片的内容。

    图片内容会随本工具的结果进入后续模型请求，调用后即可直接看到图片。
    公网图片直链均可传入；用户上传到本站的图片请改用 view_local_image。
    网页里的图片请先取得图片直链，再调用本工具。

    Args:
        url: 图片的 http(s) 直链地址
    """
    if not url.startswith(("http://", "https://")):
        raise ModelRetry("url 必须是 http(s) 图片地址")
    guessed = mimetypes.guess_type(url)[0]
    if guessed is not None and not guessed.startswith("image/"):
        raise ModelRetry(f"该地址不是图片（类型为 {guessed}），请传入图片直链")
    media_type = await _probe_image_headers(url)
    return ToolReturn(return_value=f"已读取图片: {url}",
                      content=[ImageUrl(url=url, media_type=media_type)])


async def view_local_image(path: str) -> ToolReturn:
    """查看用户上传到本平台的图片的内容。

    传入用户上传后获得的图片地址或文件名，如 /369158185545314304.png。
    图片内容随结果进入后续模型请求。

    Args:
        path: 本站图片的扁平地址或文件名
    """
    domain = platform_settings().get(KEY_PLATFORM_DOMAIN).strip()
    if not domain:
        return ToolReturn(return_value="站长未配置平台域名，无法读取上传图片，请联系站长")
    filename = path.rstrip("/").rsplit("/", 1)[-1]
    if not _LOCAL_FILENAME.fullmatch(filename):
        raise ModelRetry("上传图片地址格式不正确，请传入用户上传图片返回的扁平地址")
    scheme = "https" if platform_settings().get(KEY_PLATFORM_HTTPS).strip().lower() == "true" else "http"
    url = f"{scheme}://{domain}/{filename}"
    media_type = await _probe_image_headers(url)
    return ToolReturn(return_value=f"已读取图片: {url}",
                      content=[ImageUrl(url=url, media_type=media_type)])


view_image_toolset = FunctionToolset(tools=[view_url_image, view_local_image])
