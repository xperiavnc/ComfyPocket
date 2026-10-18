"""ComfyUI routes for native downloads and server-side settings."""
from __future__ import annotations

import asyncio
from pathlib import Path
from urllib.parse import urlsplit

from aiohttp import web

from .civitai_client import CivitaiClient, CivitaiError
from .civitai_downloads import DownloadQueue, ModelPaths
from .credentials import CredentialStore, validate_key
from .model_center import ModelCenterService


def response(data=None, message="success", status=200):
    return web.json_response({"code": 0 if status == 200 else status,
                              "msg": message, "data": data},
                             status=status, headers={"Cache-Control": "no-store"})


async def json_body(request):
    if request.content_length and request.content_length > 4096:
        raise CivitaiError("设置请求过大", 413)
    try:
        raw = await request.content.read(4097)
        if len(raw) > 4096:
            raise CivitaiError("设置请求过大", 413)
        import json
        body = json.loads(raw)
        if not isinstance(body, dict):
            raise ValueError()
        return body
    except (ValueError, UnicodeError):
        raise CivitaiError("请求应为有效 JSON 对象")


def check_settings_origin(request):
    """Block cross-site form/fetch writes, independent of ComfyUI CORS options.

    ComfyUI access control is still required: this is not user authentication.
    """
    if request.headers.get("X-Comfy-Mobile-Settings") != "1":
        raise CivitaiError("请通过 ComfyUI 设置页操作", 403)
    origin = request.headers.get("Origin")
    if origin:
        try:
            parsed = urlsplit(origin)
            # TLS may terminate at the user's reverse proxy. Match Host strictly,
            # without trusting client-supplied X-Forwarded-* headers.
            if parsed.scheme not in ("http", "https") or parsed.netloc.lower() != request.host.lower():
                raise ValueError()
            if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
                raise ValueError()
        except ValueError:
            raise CivitaiError("设置页来源不匹配，请从当前 ComfyUI 页面打开设置", 403)
    if request.headers.get("Sec-Fetch-Site") == "cross-site":
        raise CivitaiError("请从当前 ComfyUI 页面打开设置", 403)


class CivitaiService:
    def __init__(self, folder_paths, directory: Path | None = None, client=None):
        if directory is None:
            if folder_paths is None or not hasattr(folder_paths, "get_user_directory"):
                raise RuntimeError("ComfyUI user directory API is required")
            directory = Path(folder_paths.get_user_directory()) / "mobile_bridge"
        self.credentials = CredentialStore(directory)
        self.client = client or CivitaiClient(self.credentials)
        self.queue = DownloadQueue(self.client, ModelPaths(folder_paths), directory)
        self.model_center = ModelCenterService(folder_paths, directory, self.client)
        self.queue.on_completed = self.model_center.download_completed
        self.settings_lock = asyncio.Lock()

    async def guarded(self, operation):
        try:
            return await operation()
        except CivitaiError as error:
            return response(message=str(error), status=error.status)
        except ValueError as error:
            # Validation errors originate here, never from raw remote response bodies.
            return response(message=str(error), status=400)
        except asyncio.CancelledError:
            raise
        except Exception:
            return response(message="电脑服务请求失败，请检查电脑网络、代理或本地配置权限", status=502)

    async def settings_get(self, request):
        async def run():
            check_settings_origin(request)
            return response(self.credentials.status())
        return await self.guarded(run)

    async def settings_post(self, request):
        async def run():
            check_settings_origin(request)
            body = await json_body(request)
            action = body.get("action")
            async with self.settings_lock:
                if action == "save":
                    self.credentials.save(body.get("key"))
                    return response(self.credentials.status(), "Key 已保存，新下载任务立即生效")
                if action == "clear":
                    self.credentials.clear()
                    return response(self.credentials.status(), "已清除保存的 Key；环境变量如存在仍生效")
                if action == "test":
                    draft = validate_key(body["key"]) if "key" in body else None
                    message = await self.client.test(draft)
                    return response(self.credentials.status(), message)
                raise CivitaiError("操作应为 save、clear 或 test")
        return await self.guarded(run)

    async def download(self, request):
        async def run():
            job = await self.queue.submit(await json_body(request))
            return response(job, "已加入 Bridge 原生下载队列")
        return await self.guarded(run)

    async def tasks(self, request):
        return response(self.queue.snapshot())

    async def task_action(self, request):
        async def run():
            body = await json_body(request)
            await self.queue.action(str(body.get("action") or ""), str(body.get("downloadId") or ""))
            return response(message="任务操作完成")
        return await self.guarded(run)

    async def shutdown(self, app):
        await self.queue.close()

    def register(self, app):
        app.router.add_get("/api/mobile/civitai/settings", self.settings_get)
        app.router.add_post("/api/mobile/civitai/settings", self.settings_post)
        # Old aliases let existing clients upgrade the plugin first.
        for prefix in ("", "native-"):
            app.router.add_post(f"/api/mobile/civitai/{prefix}download", self.download)
            app.router.add_get(f"/api/mobile/civitai/{prefix}tasks", self.tasks)
            app.router.add_post(f"/api/mobile/civitai/{prefix}task-action", self.task_action)
        app.on_shutdown.append(self.shutdown)
        self.model_center.register(app)
