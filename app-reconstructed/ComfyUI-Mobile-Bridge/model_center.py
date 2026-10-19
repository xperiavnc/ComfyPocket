"""Versioned model-center routes and two independent background queues."""
from __future__ import annotations

import asyncio
import hashlib
import json
from pathlib import Path
import threading
import uuid

from aiohttp import web

from .civitai_client import CivitaiError
from .model_index import ModelIndex, stamp
from .model_files import ModelFiles
from .civitai_downloads import positive_id


def response(data=None, status=200, message="success"):
    return web.json_response({"code": 0 if status == 200 else status, "msg": message, "data": data},
                             status=status, headers={"Cache-Control": "no-store"})


async def body(request):
    raw = await request.content.read(65537)
    if len(raw) > 65536:
        raise CivitaiError("模型资料请求过大", 413)
    try:
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (ValueError, UnicodeError):
        raise CivitaiError("请求应为 JSON 对象")


async def threaded(function, *args):
    """Do not leave a DB publisher / file reader detached after cancellation."""
    worker = asyncio.create_task(asyncio.to_thread(function, *args))
    try:
        return await asyncio.shield(worker)
    except asyncio.CancelledError:
        await asyncio.gather(worker, return_exceptions=True)
        raise


class ModelCenterService:
    def __init__(self, folders, directory, client):
        self.index = ModelIndex(folders, directory)
        self.client = client
        self.scan_task = None
        self.scan_stop = threading.Event()
        self.scan_state = {"status": "idle", "discovered": 0, "hashed": 0, "message": ""}
        self.jobs = {}
        self.workers = {}
        self.stops = {}
        self.slots = asyncio.Semaphore(1)
        self.init_lock = asyncio.Lock()
        self.initialized = False
        self.closing = False
        self.provider_retry_at = 0
        self.file_lock = asyncio.Lock()
        self.file_busy = False

    async def initialize(self):
        async with self.init_lock:
            if self.initialized:
                return
            saved = await threaded(self.index.jobs)
            for job in saved:
                if job["status"] in ("queued", "hashing", "matching"):
                    job.update(status="paused", message="电脑重启后已暂停，可继续")
                    await threaded(self.index.job_put, job)
                self.jobs[job["id"]] = job
                if job["code"] == 429:
                    self.provider_retry_at = max(self.provider_retry_at, job["retryAt"])
            self.initialized = True

    async def guarded(self, operation):
        try:
            await self.initialize()
            return await operation()
        except CivitaiError as error:
            return response(status=error.status, message=str(error))
        except (ValueError, TypeError):
            return response(status=400, message="模型中心参数无效")
        except asyncio.CancelledError:
            raise
        except Exception:
            return response(status=502, message="模型中心请求失败，请检查注册目录、用户资料目录和电脑连接")

    async def capabilities(self, request):
        async def run():
            info = await threaded(self.index.info)
            info["scan"] = dict(self.scan_state)
            return response(info)
        return await self.guarded(run)

    async def listing(self, request):
        async def run():
            result = await threaded(self.index.query, dict(request.query))
            # A first listing starts a local scan only; no provider/network request.
            if not result["lastSync"] and not self.scan_task:
                self.start_scan()
            result["scan"] = dict(self.scan_state)
            return response(result)
        return await self.guarded(run)

    async def detail(self, request):
        async def run():
            return response(await threaded(self.index.detail, request.match_info["id"]))
        return await self.guarded(run)

    async def installation(self, request):
        async def run():
            args = [positive_id(request.query.get(key), key) for key in ("modelId", "versionId", "fileId")]
            return response(await threaded(self.index.installation, *args))
        return await self.guarded(run)

    def start_scan(self):
        if self.file_busy:
            raise CivitaiError("正在管理模型文件，请稍后同步", 409)
        if self.closing:
            raise CivitaiError("服务正在关闭", 503)
        if self.scan_task and not self.scan_task.done():
            return
        self.scan_stop = threading.Event()
        self.scan_state = {"status": "scanning", "discovered": 0, "hashed": 0, "message": ""}
        self.scan_task = asyncio.create_task(self.scan())

    async def scan(self):
        try:
            completed = await threaded(self.index.scan, self.scan_stop, self.scan_state)
            if completed and not self.scan_stop.is_set():
                self.scan_state["status"] = "hashing"
                for location_id in await threaded(self.index.hash_candidates):
                    if self.scan_stop.is_set():
                        break
                    try:
                        digest = await threaded(self.index.hash_location, location_id, self.scan_stop)
                        if digest:
                            self.scan_state["hashed"] += 1
                    except (CivitaiError, OSError):
                        self.scan_state["message"] = "部分文件变化或不可读，下次同步重试；其他结果已保留"
            if not self.scan_stop.is_set():
                self.scan_state["status"] = "completed"
        except Exception:
            self.scan_state.update(status="failed", message="索引同步未完成，请检查模型目录和用户目录权限")

    async def scan_action(self, request):
        async def run():
            value = await body(request)
            action = value.get("action")
            if action in ("start", "resume"):
                self.start_scan()
            elif action in ("pause", "cancel"):
                self.scan_stop.set()
                if self.scan_task:
                    await self.scan_task  # join the thread; no abandoned filesystem reader
                self.scan_state["status"] = "paused" if action == "pause" else "cancelled"
            else:
                raise CivitaiError("同步操作无效")
            return response(dict(self.scan_state))
        return await self.guarded(run)

    async def annotation(self, request):
        async def run():
            value = await body(request)
            revision = value.get("expectedRevision")
            if isinstance(revision, bool) or not isinstance(revision, int):
                raise CivitaiError("保存标注需要 expectedRevision")
            data = value.get("annotation", {})
            if not isinstance(data, dict):
                raise CivitaiError("annotation 应为对象")
            undo = value.get("undoRevision")
            if undo is not None and (isinstance(undo, bool) or not isinstance(undo, int) or undo < 1):
                raise CivitaiError("撤销修订无效")
            return response(await threaded(
                self.index.annotate, request.match_info["id"], revision, data, undo))
        return await self.guarded(run)

    async def directories(self, request):
        async def run():
            return response(await threaded(ModelFiles(self.index).directories, request.match_info["id"],
                                           request.query.get("rootId"), request.query.get("path", "")))
        return await self.guarded(run)

    async def file_action(self, request):
        async def run():
            value = await body(request)
            async with self.file_lock:
                self.file_busy = True
                restart_scan = self.scan_task and not self.scan_task.done()
                resumable = [key for key, job in self.jobs.items()
                             if job["status"] in ("queued", "hashing", "matching")]
                try:
                    self.scan_stop.set()
                    if self.scan_task:
                        await asyncio.gather(self.scan_task, return_exceptions=True)
                    for key in resumable:
                        stop = self.stops.get(key)
                        if stop:
                            stop.set()
                        worker = self.workers.get(key)
                        if worker and self.jobs[key]["status"] in ("queued", "matching"):
                            worker.cancel()
                    if self.workers:
                        await asyncio.gather(*list(self.workers.values()), return_exceptions=True)
                    result = await threaded(ModelFiles(self.index).mutate, request.match_info["id"], value)
                    # The filesystem and DB committed first; now rebind in-memory queue entries.
                    for job in self.jobs.values():
                        if job["locationId"] == result["previousId"]:
                            if result["item"]:
                                job["locationId"] = result["item"]["id"]
                            else:
                                await self.save_job(job, status="cancelled", message="模型文件已删除")
                    return response(result)
                finally:
                    self.file_busy = False
                    # Also covers a disconnected/cancelled HTTP caller after the worker committed.
                    for saved in await threaded(self.index.jobs):
                        if saved["id"] in self.jobs:
                            self.jobs[saved["id"]].update(saved)
                    for key in resumable:
                        job = self.jobs.get(key)
                        if job and job["status"] in ("queued", "hashing", "matching", "paused") and not self.closing:
                            await self.save_job(job, status="queued", message="")
                            self.start_job(job)
                    if restart_scan and not self.closing:
                        self.scan_task = None
                        self.start_scan()
        return await self.guarded(run)

    async def save_job(self, job, **updates):
        job.update(updates, updated=max(stamp(), job.get("updated", 0) + 1))
        await threaded(self.index.job_put, dict(job))

    async def enqueue(self, location_id, force=False):
        await self.initialize()
        if self.closing:
            raise CivitaiError("服务正在关闭", 503)
        if self.file_busy:
            raise CivitaiError("正在管理模型文件，请稍后补全资料", 409)
        item = await threaded(self.index.detail, location_id)
        digest = item["sha256"]
        matching = [j for j in self.jobs.values() if j["locationId"] == location_id or
                    (digest and j["sha256"] == digest)]
        duplicate = next((j for j in matching if j["status"] in ("queued", "hashing", "matching", "paused")), None)
        duplicate = duplicate or max(matching, key=lambda j: j["updated"], default=None)
        if duplicate and duplicate["status"] in ("queued", "hashing", "matching", "paused"):
            return duplicate
        if duplicate and duplicate["retryAt"] > stamp():
            raise CivitaiError("此资料仍在冷却期，请到任务页查看下次重试时间", 429)
        if self.provider_retry_at > stamp():
            raise CivitaiError("Civitai 正在退避冷却，稍后再添加资料任务", 429)
        if item["provider"] and not force:
            return {"id": "", "status": "completed", "message": "已有本地资料；刷新请显式选择"}
        if len([j for j in self.jobs.values() if j["status"] in ("queued", "hashing", "matching", "paused")]) >= 100:
            raise CivitaiError("资料队列已满，请先处理已有任务", 409)
        job = {"id": uuid.uuid4().hex, "locationId": location_id, "sha256": digest,
               "status": "queued", "message": "", "code": 0, "retryAt": 0, "updated": stamp()}
        self.jobs[job["id"]] = job
        job["refresh"] = force
        # Keep the latest 200 settled entries; all resumable jobs are retained.
        for old in sorted(self.jobs.values(), key=lambda j: j["updated"]):
            if len(self.jobs) <= 200:
                break
            if old["status"] not in ("queued", "hashing", "matching", "paused"):
                self.jobs.pop(old["id"], None)
        await self.save_job(job)
        self.start_job(job)
        return job

    def start_job(self, job):
        stop = threading.Event()
        self.stops[job["id"]] = stop
        self.workers[job["id"]] = asyncio.create_task(self.enrich(job, stop))

    async def enrich(self, job, stop):
        submitted_at = job["updated"]
        try:
            async with self.slots:
                if stop.is_set() or job["status"] != "queued":
                    return
                await self.save_job(job, status="hashing", message="电脑端计算文件指纹")
                if stop.is_set():
                    return
                digest = await threaded(self.index.hash_location, job["locationId"], stop)
                if stop.is_set() or not digest:
                    return
                already_completed = any(other["id"] != job["id"] and other.get("sha256") == digest and
                                        other["status"] == "completed" and other["updated"] >= submitted_at
                                        for other in self.jobs.values())
                existing = await threaded(self.index.provider, digest)
                if stop.is_set():
                    return
                if already_completed or (not job.get("refresh") and existing):
                    await self.save_job(job, sha256=digest, status="completed", message="复用同内容已保存资料")
                    return
                if self.provider_retry_at > stamp():
                    await self.save_job(job, sha256=digest, status="failed", code=429,
                                        retryAt=self.provider_retry_at, message="服务端退避中，稍后继续")
                    return
                await self.save_job(job, sha256=digest, status="matching", message="使用电脑 Civitai 配置匹配")
                if stop.is_set():
                    return
                # Every files[] result must include this exact full SHA256.
                async with asyncio.timeout(120):
                    version = await self.client.json("model-versions/by-hash/" + digest)
                if stop.is_set():
                    return
                await threaded(self.index.save_provider, digest, version)
                if stop.is_set():
                    return
                await self.save_job(job, status="completed", code=0, retryAt=0, message="资料已保存到电脑")
        except asyncio.CancelledError:
            # pause/cancel assigns the desired state before cancellation
            if job["status"] in ("matching", "queued", "hashing"):
                await self.save_job(job, status="paused", message="已暂停")
        except CivitaiError as error:
            if stop.is_set():
                return
            retry = max(60, getattr(error, "retry_after", 0)) if error.status == 429 else (
                24 * 3600 if error.status == 404 else 5 * 60)
            retry_at = stamp() + retry * 1000
            if error.status == 429:
                self.provider_retry_at = max(self.provider_retry_at, retry_at)
            await self.save_job(job, status="unmatched" if error.status == 404 else "failed",
                                code=error.status, retryAt=retry_at, message=str(error))
        except (asyncio.TimeoutError, TimeoutError):
            if stop.is_set():
                return
            await self.save_job(job, status="failed", code=408, retryAt=stamp()+300000,
                                message="资料请求超时，旧资料保留")
        except Exception:
            if stop.is_set():
                return
            await self.save_job(job, status="failed", code=502, retryAt=stamp()+300000,
                                message="资料补全失败，原文件、已有资料和人工标注保留")
        finally:
            self.workers.pop(job["id"], None)
            self.stops.pop(job["id"], None)

    async def enrichment(self, request):
        async def run():
            value = await body(request)
            ids = value.get("locationIds")
            if not isinstance(ids, list) or not 1 <= len(ids) <= 100 or any(not isinstance(i, str) for i in ids):
                raise CivitaiError("请选择 1–100 个模型位置")
            results, errors = [], []
            for location_id in dict.fromkeys(ids):
                try:
                    results.append(dict(await self.enqueue(location_id, value.get("refresh") is True)))
                except CivitaiError as error:
                    errors.append({"locationId": location_id, "code": error.status, "message": str(error)})
            return response({"tasks": results, "errors": errors})
        return await self.guarded(run)

    async def tasks(self, request):
        async def run():
            return response({"scan": dict(self.scan_state),
                             "enrichment": [dict(j) for j in sorted(self.jobs.values(),
                                                                    key=lambda j: -j["updated"])]})
        return await self.guarded(run)

    async def task_action(self, request):
        async def run():
            value = await body(request)
            job = self.jobs.get(value.get("id"))
            action = value.get("action")
            if not job:
                raise CivitaiError("资料任务不存在", 404)
            if action == "resume":
                if self.file_busy:
                    raise CivitaiError("正在管理模型文件，请稍后继续", 409)
                if self.closing:
                    raise CivitaiError("电脑服务正在关闭", 503)
                if job["id"] in self.workers:
                    return response(dict(job))
                if job["retryAt"] > stamp():
                    raise CivitaiError("服务端冷却期尚未结束", 429)
                await self.save_job(job, status="queued", message="")
                self.start_job(job)
            elif action in ("pause", "cancel"):
                if job["status"] == "completed":
                    raise CivitaiError("资料已完成，保留已保存结果", 409)
                worker = self.workers.get(job["id"])
                phase = job["status"]
                # Signal synchronously before awaiting persistence. Otherwise a
                # hash can finish during the DB write and advance to matching.
                if job["id"] in self.stops:
                    self.stops[job["id"]].set()
                await self.save_job(job, status="paused" if action == "pause" else "cancelled", message="已暂停" if action == "pause" else "已取消")
                if worker:
                    if phase in ("queued", "matching"):
                        worker.cancel()
                    await asyncio.gather(worker, return_exceptions=True)
                    self.workers.pop(job["id"], None)
                    self.stops.pop(job["id"], None)
            else:
                raise CivitaiError("任务操作无效")
            return response(dict(job))
        return await self.guarded(run)

    async def download_completed(self, job, target, version):
        """Called only after the download queue verifies and publishes a file."""
        await self.initialize()
        def ingest():
            roots = self.index.registered_roots()
            root = next((r for r in roots if Path(r["path"]) == Path(job.root)), None)
            if root is None:
                raise CivitaiError("下载已完成，但注册目录已变化，请重新同步", 409)
            # Register roots before publishing a location; no full rescan/hash.
            with self.index.db() as db:
                db.execute("INSERT OR REPLACE INTO roots VALUES(?,?,?,?,?)",
                           (root["id"], root["category"], root["path"], json.dumps(root["keys"]), "ready"))
            location_id = self.index.put_location(root, target, "download:" + job.download_id)
            self.index.attach_hash(location_id, job.sha256, Path(target).stat())
            self.index.save_provider(job.sha256, version)
            return location_id
        return await threaded(ingest)

    async def preview(self, request):
        async def run():
            item = await threaded(self.index.detail, request.match_info["id"])
            number = int(request.query.get("index", "0"))
            if number < 0:
                raise CivitaiError("图片索引无效", 400)
            local = await threaded(self.index.local_preview, item)
            if local and number == 0:
                return web.FileResponse(local, headers={"Cache-Control": "private, max-age=86400"})
            digest = item["sha256"]
            images = (item["provider"] or {}).get("images", [])
            if local:
                number -= 1
            if not digest or not 0 <= number < len(images):
                raise CivitaiError("此模型暂无来源图片", 404)
            url = images[number]["url"]
            path = self.index.directory / "previews" / digest / (hashlib.sha256(url.encode()).hexdigest() + ".image")
            if not await threaded(path.is_file):
                async with self.client.session() as session:
                    reply = await self.client.open(session, url)
                    try:
                        self.client.check_status(reply.status)
                        data = bytearray()
                        async for chunk in reply.content.iter_chunked(65536):
                            data.extend(chunk)
                            if len(data) > 10 * 1024 * 1024:
                                raise CivitaiError("来源图片过大", 413)
                        payload = bytes(data)
                    finally:
                        reply.release()
                if not (payload.startswith(b"\xff\xd8") or payload.startswith(b"\x89PNG") or
                        (payload.startswith(b"RIFF") and payload[8:12] == b"WEBP")):
                    raise CivitaiError("来源未返回支持的图片", 502)
                def publish():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    temp = path.with_suffix("." + uuid.uuid4().hex + ".tmp")
                    temp.write_bytes(payload)
                    temp.replace(path)
                await threaded(publish)
            def image_type():
                with path.open("rb") as stream:
                    header = stream.read(12)
                return "image/png" if header.startswith(b"\x89PNG") else (
                    "image/webp" if header[8:12] == b"WEBP" else "image/jpeg")
            return web.FileResponse(path, headers={"Cache-Control": "private, max-age=86400",
                                                   "Content-Type": await threaded(image_type)})
        return await self.guarded(run)

    async def shutdown(self, app):
        self.closing = True
        self.scan_stop.set()
        if self.scan_task:
            await asyncio.gather(self.scan_task, return_exceptions=True)
        for stop in self.stops.values():
            stop.set()
        workers = list(self.workers.items())
        for key, worker in workers:
            job = self.jobs[key]
            phase = job["status"]
            if phase in ("queued", "hashing", "matching"):
                await self.save_job(job, status="paused", message="电脑停止后已暂停")
            if phase in ("queued", "matching"):
                worker.cancel()
        if workers:
            await asyncio.gather(*(w for _, w in workers), return_exceptions=True)

    def register(self, app):
        base = "/api/mobile/model-center"
        app.router.add_get(base + "/capabilities", self.capabilities)
        app.router.add_get(base + "/models", self.listing)
        app.router.add_get(base + "/installation", self.installation)
        app.router.add_get(base + "/models/{id}", self.detail)
        app.router.add_post(base + "/models/{id}/annotation", self.annotation)
        app.router.add_get(base + "/models/{id}/directories", self.directories)
        app.router.add_post(base + "/models/{id}/file-action", self.file_action)
        app.router.add_get(base + "/models/{id}/preview", self.preview)
        app.router.add_post(base + "/scan", self.scan_action)
        app.router.add_post(base + "/enrichment", self.enrichment)
        app.router.add_get(base + "/tasks", self.tasks)
        app.router.add_post(base + "/task-action", self.task_action)
        app.on_shutdown.append(self.shutdown)
