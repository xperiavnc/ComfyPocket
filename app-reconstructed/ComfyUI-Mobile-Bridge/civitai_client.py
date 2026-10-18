"""Civitai transport: normal TLS, bounded JSON, per-hop credential isolation."""
from __future__ import annotations

import ipaddress
import json
import os
import time
from email.utils import parsedate_to_datetime
from urllib.parse import urlsplit

from aiohttp import ClientSession, ClientTimeout
from yarl import URL


class CivitaiError(Exception):
    def __init__(self, message: str, status: int = 400, retry_after: int = 0):
        super().__init__(message)
        self.status = status
        self.retry_after = retry_after


class CivitaiClient:
    API_BASE = "https://civitai.com/api/v1/"

    def __init__(self, credentials):
        self.credentials = credentials

    def session(self):
        return ClientSession(trust_env=True, auto_decompress=False,
                             timeout=ClientTimeout(total=None, sock_connect=30, sock_read=90))

    @staticmethod
    def official(url: str) -> bool:
        parsed = urlsplit(url)
        return (parsed.scheme == "https" and parsed.hostname == "civitai.com"
                and parsed.port in (None, 443) and not parsed.username and not parsed.password)

    def headers(self, url: str, extra: dict | None = None) -> dict:
        result = {"User-Agent": "ComfyPocket-Mobile-Bridge/2.0",
                  "Accept-Encoding": "identity", **(extra or {})}
        # Recompute after every redirect; never attach the API key to a CDN.
        if self.official(url):
            parsed = urlsplit(url)
            if parsed.path.startswith(("/api/v1/", "/api/download/")):
                key = self.credentials.key()
                if key:
                    result["Authorization"] = f"Bearer {key}"
        return result

    @staticmethod
    def check_url(url: str) -> None:
        try:
            parsed = urlsplit(url)
            if (parsed.scheme != "https" or not parsed.hostname or parsed.username
                    or parsed.password or parsed.port not in (None, 443)):
                raise ValueError()
            host = parsed.hostname.lower()
            if host in ("localhost", "metadata.google.internal") or host.endswith((".local", ".localhost")):
                raise ValueError()
            try:
                address = ipaddress.ip_address(host)
            except ValueError:
                address = None
            if address is not None and not address.is_global:
                raise ValueError()
        except ValueError:
            raise CivitaiError("下载地址格式不受支持")

    async def open(self, session, url: str, extra: dict | None = None):
        for _ in range(6):
            self.check_url(url)
            response = await session.get(
                url, headers=self.headers(url, extra), allow_redirects=False,
                proxy=os.environ.get("CIVITAI_HTTP_PROXY") or None)
            if response.status not in (301, 302, 303, 307, 308):
                return response
            location = response.headers.get("Location")
            response.release()
            if not location:
                raise CivitaiError("Civitai 返回了无效的重定向", 502)
            url = str(URL(url).join(URL(location)))
        raise CivitaiError("Civitai 重定向次数过多", 502)

    @staticmethod
    def check_status(status: int) -> None:
        if status == 401:
            raise CivitaiError("Civitai Key 无效或已过期，请在 ComfyUI 设置中更新", 401)
        if status in (402, 403):
            raise CivitaiError("此 Key 对应账号暂无下载权限，请检查账号、模型访问级别或购买状态", 403)
        if status == 404:
            raise CivitaiError("模型、版本或文件已移除，或当前账号不可见", 404)
        if status == 429:
            raise CivitaiError("Civitai 请求过于频繁，请稍后重试", 429)
        if status != 200:
            raise CivitaiError(f"Civitai 请求失败（HTTP {status}）", 502)

    async def json(self, endpoint: str, params=None, key: str | None = None) -> dict:
        url = str(URL(self.API_BASE + endpoint).with_query(params or []))
        # Draft connection tests don't mutate the saved key or an active download.
        transport = self if key is None else CivitaiClient(_DraftCredential(key))
        transport.API_BASE = self.API_BASE
        transport.check_url = self.check_url
        async with self.session() as session:
            response = await transport.open(session, url)
            try:
                if response.status == 429:
                    retry = response.headers.get("Retry-After", "")
                    try:
                        seconds = int(retry) if retry.isdigit() else int(
                            parsedate_to_datetime(retry).timestamp() - time.time())
                    except (ValueError, TypeError, OverflowError):
                        seconds = 60
                    raise CivitaiError("Civitai 请求过于频繁，请按任务中的重试时间继续", 429,
                                       max(60, min(seconds, 7 * 86400)))
                self.check_status(response.status)
                data = bytearray()
                async for chunk in response.content.iter_chunked(64 * 1024):
                    data.extend(chunk)
                    if len(data) > 8 * 1024 * 1024:
                        raise CivitaiError("Civitai 数据过大，请缩小查询范围", 502)
                try:
                    result = json.loads(data)
                    if not isinstance(result, dict):
                        raise ValueError()
                    return result
                except (ValueError, UnicodeError):
                    raise CivitaiError("Civitai 返回的不是有效数据，请检查电脑网络", 502)
            finally:
                response.release()

    async def test(self, key: str | None = None) -> str:
        effective = self.credentials.key() if key is None else key
        await self.json("me" if effective else "models",
                        None if effective else [("limit", "1"), ("nsfw", "false")], key)
        return "电脑连接成功，Key 验证通过" if effective else "电脑连接成功，当前为匿名访问"


class _DraftCredential:
    def __init__(self, value):
        self.value = value

    def key(self):
        return self.value
