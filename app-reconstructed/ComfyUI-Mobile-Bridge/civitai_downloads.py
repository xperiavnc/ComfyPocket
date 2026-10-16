"""Native, resumable Civitai queue. No Lora Manager calls or dependencies."""
from __future__ import annotations

import asyncio
import hashlib
import os
import re
import time
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path, PureWindowsPath
from urllib.parse import urlsplit

from yarl import URL

from .civitai_client import CivitaiError
from .credentials import atomic_json


def positive_id(value, label: str) -> int:
    try:
        if isinstance(value, bool) or int(value) <= 0 or str(int(value)) != str(value):
            raise ValueError()
        return int(value)
    except (TypeError, ValueError, OverflowError):
        raise CivitaiError(f"{label} 应为正整数")


def safe_filename(value: object) -> str:
    name = str(value or "")
    if (not name or len(name) > 220 or name.startswith(".") or
            re.search(r'[<>:"/\\|?*\x00-\x1f]', name) or name.endswith((".", " ")) or
            name.split(".")[0].upper() in
            {"CON", "PRN", "AUX", "NUL", *[f"COM{i}" for i in range(1, 10)],
             *[f"LPT{i}" for i in range(1, 10)]}):
        raise CivitaiError("官方文件名不适合本地保存")
    if Path(name).suffix.lower() not in {".safetensors", ".ckpt", ".pt", ".pth",
                                         ".bin", ".gguf", ".sft", ".onnx"}:
        raise CivitaiError("仅支持模型文件格式")
    return name


def model_folder_keys(model_type: str, file_type: str = "") -> list[str]:
    kind = file_type.strip().lower().replace("_", " ").replace("-", " ")
    if kind == "vae":
        return ["vae"]
    if kind in ("text encoder", "textencoder") or kind.endswith(" text encoder"):
        return ["text_encoders", "clip"]
    upper = model_type.upper()
    if upper == "VAE":
        return ["vae"]
    if upper in ("TEXT ENCODER", "TEXT_ENCODER", "TEXTENCODER"):
        return ["text_encoders", "clip"]
    if upper in ("CHECKPOINT", "CHECKPOINTS"):
        return ["checkpoints"]
    if upper in ("UNET", "GGUF", "DIFFUSION", "DIFFUSION_MODELS"):
        return ["diffusion_models", "unet", "checkpoints"]
    return ["loras"]


class ModelPaths:
    def __init__(self, folder_paths):
        self.folders = folder_paths

    def roots(self, keys=None) -> list[Path]:
        if self.folders is None:
            return []
        keys = keys or [
            "loras", "checkpoints", "diffusion_models", "unet", "vae", "text_encoders", "clip",
        ]
        result = []
        for key in keys:
            try:
                entries = self.folders.get_folder_paths(key) or []
            except Exception:
                entries = []
            for item in entries:
                resolved = Path(item).resolve()
                if resolved not in result:
                    result.append(resolved)
        return result

    def destination(self, root: str, subfolder: str, model_type: str, file_type: str):
        allowed = self.roots()
        defaults = self.roots(model_folder_keys(model_type, file_type))
        if root:
            selected = Path(root).resolve()
            if selected not in allowed:
                raise CivitaiError("保存根目录未在 ComfyUI 模型路径中注册")
        elif defaults:
            selected = defaults[0]
        else:
            raise CivitaiError("ComfyUI 尚未注册对应模型目录，请先配置目录")
        sub = str(subfolder or "").replace("\\", "/")
        if (len(sub) > 512 or PureWindowsPath(sub).drive or sub.startswith("/") or
                any(part in (".", "..") or re.search(r'[<>:"|?*\x00-\x1f]', part)
                    or part.endswith((" ", ".")) for part in sub.split("/") if part)):
            raise CivitaiError("子目录名称无效")
        target = (selected / sub).resolve()
        if not target.is_relative_to(selected):
            raise CivitaiError("子目录超出模型目录")
        return selected, target

    def check_job(self, job):
        selected, directory = self.destination(job.root, job.subfolder, "", "")
        if Path(job.root).absolute() != selected:
            raise CivitaiError("模型根目录链接发生变化，请重新创建任务")
        target = directory / safe_filename(job.filename)
        part = directory / f".mobile-{job.download_id}.part"
        if not re.fullmatch(r"[0-9a-f]{32}", job.download_id):
            raise CivitaiError("下载任务编号无效")
        # A symlink created while paused must not redirect writes/deletes.
        if part.is_symlink() or target.is_symlink() or directory.resolve() != directory:
            raise CivitaiError("下载目录或文件链接发生变化，请重新创建任务")
        return directory, target, part


@dataclass
class DownloadJob:
    download_id: str
    model_id: int
    model_version_id: int
    file_id: int
    model_name: str
    filename: str
    root: str
    subfolder: str
    sha256: str
    status: str = "queued"
    downloaded_size: int = 0
    total_size: int = 0
    etag: str = ""
    last_modified: str = ""
    error: str = ""
    speed_bytes: float = 0

    def public(self):
        result = asdict(self)
        for key in ("root", "subfolder", "sha256", "etag", "last_modified"):
            result.pop(key)
        result["progress"] = (100.0 if self.status == "completed" else
                              min(99.9, 100 * self.downloaded_size / self.total_size)
                              if self.total_size else 0)
        return result


class DownloadQueue:
    ACTIVE = {"queued", "downloading", "verifying"}
    TERMINAL = {"completed", "cancelled"}

    def __init__(self, client, paths: ModelPaths, directory: Path, concurrency=2):
        self.client = client
        self.paths = paths
        self.state_file = Path(directory) / "download-tasks.json"
        self.jobs: dict[str, DownloadJob] = {}
        self.workers: dict[str, asyncio.Task] = {}
        self.slots = asyncio.Semaphore(concurrency)
        self.closing = False
        if self.state_file.exists():
            try:
                import json
                for item in json.loads(self.state_file.read_text(encoding="utf-8"))[-100:]:
                    job = DownloadJob(**item)
                    self.paths.check_job(job)
                    if job.status in self.ACTIVE:
                        job.status = "paused"
                        job.error = "电脑重启后已暂停，点击继续"
                    job.speed_bytes = 0
                    self.jobs[job.download_id] = job
            except Exception:
                # Never execute or auto-start data from a damaged state file.
                self.jobs.clear()

    def persist(self):
        # URLs, key and headers are intentionally absent from the persisted schema.
        atomic_json(self.state_file, [
            {**asdict(job), "speed_bytes": 0} for job in self.jobs.values()
        ])

    async def metadata(self, model_id, version_id, file_id=None):
        data = await self.client.json(f"model-versions/{version_id}")
        if data.get("id") != version_id or data.get("modelId") != model_id:
            raise CivitaiError("模型与版本编号不匹配")
        if data.get("canDownload") is False:
            raise CivitaiError("当前电脑 Key 对应账号暂无此版本的下载权限", 403)
        files = [item for item in data.get("files", []) if isinstance(item, dict)]
        if file_id is not None:
            selected = next((item for item in files if item.get("id") == file_id), None)
        else:
            models = [item for item in files if item.get("type", "Model").lower() == "model"]
            selected = next((item for item in models if item.get("primary")), None)
            selected = selected or (models[0] if models else None)
        if not selected:
            raise CivitaiError("此版本没有选中的文件，请刷新模型详情")
        filename = safe_filename(selected.get("name"))
        digest = str((selected.get("hashes") or {}).get("SHA256") or "").lower()
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise CivitaiError("官方尚未提供文件 SHA-256，待文件处理完成后重试")
        url = str(selected.get("downloadUrl") or "")
        if not self.client.official(url) or not urlsplit(url).path.startswith("/api/download/"):
            raise CivitaiError("官方未返回有效的模型下载地址")
        # Old token query parameters from metadata are never used or persisted.
        parsed = URL(url)
        url = str(parsed.with_query([(k, v) for k, v in parsed.query.items()
                                     if k.lower() not in ("token", "api_key", "apikey")]))
        return data, selected, filename, digest, url

    async def submit(self, body: dict):
        model = positive_id(body.get("modelId", body.get("model_id")), "modelId")
        version = positive_id(body.get("modelVersionId", body.get("model_version_id")), "modelVersionId")
        file_id = body.get("fileId", body.get("file_id"))
        file_id = positive_id(file_id, "fileId") if file_id is not None else None
        data, selected, filename, digest, _ = await self.metadata(model, version, file_id)
        # Client URL / fileType / role are hints only; trusted metadata selects the file.
        model_type = str(body.get("modelType") or (data.get("model") or {}).get("type") or "LORA")
        selected_root, directory = self.paths.destination(
            str(body.get("modelRoot") or body.get("model_root") or ""),
            str(body.get("subfolder") or body.get("relative_path") or ""),
            model_type, str(selected.get("type") or "Model"))
        job = DownloadJob(uuid.uuid4().hex, model, version, positive_id(selected.get("id"), "fileId"),
                          str((data.get("model") or {}).get("name") or filename), filename,
                          str(selected_root), directory.relative_to(selected_root).as_posix(), digest)
        if job.subfolder == ".":
            job.subfolder = ""
        self.paths.check_job(job)
        if (directory / filename).exists():
            raise CivitaiError("目标文件已存在，不会覆盖；请选择其他目录", 409)
        for other in self.jobs.values():
            if (other.root, other.subfolder, other.filename) == (job.root, job.subfolder, job.filename):
                if other.status not in self.TERMINAL:
                    raise CivitaiError("此目录中已有相同文件的下载任务，请在队列中继续", 409)
        if len([j for j in self.jobs.values() if j.status not in self.TERMINAL]) >= 50:
            raise CivitaiError("下载队列已满，请先处理现有任务", 409)
        if self.closing:
            raise CivitaiError("电脑服务正在关闭，请稍后重试", 503)
        self.jobs[job.download_id] = job
        # Bound history without dropping resumable tasks.
        for key in list(self.jobs):
            if len(self.jobs) <= 100:
                break
            if self.jobs[key].status in self.TERMINAL:
                del self.jobs[key]
        try:
            self.persist()
        except OSError:
            self.jobs.pop(job.download_id, None)
            raise CivitaiError("任务保存失败，请检查电脑用户目录权限或磁盘空间", 500)
        self.start(job)
        return job.public()

    def start(self, job):
        self.workers[job.download_id] = asyncio.create_task(self.run(job))

    async def run(self, job):
        try:
            async with self.slots:
                if job.status != "queued":
                    return
                job.status = "downloading"
                job.error = ""
                self.persist()
                await self.download(job)
        except asyncio.CancelledError:
            if job.status in self.ACTIVE:
                job.status = "paused"
        except CivitaiError as error:
            job.status, job.error = "failed", str(error)
        except Exception:
            # No exception URL, headers or remote response body enters logs/state/UI.
            job.status, job.error = "failed", "电脑下载失败，请检查电脑网络、代理、磁盘空间或稍后继续"
        finally:
            job.speed_bytes = 0
            try:
                self.persist()
            except OSError:
                job.error = "任务状态保存失败，请检查电脑用户目录权限或磁盘空间"
            self.workers.pop(job.download_id, None)

    async def download(self, job):
        _, _, filename, digest, url = await self.metadata(
            job.model_id, job.model_version_id, job.file_id)
        if filename != job.filename or digest != job.sha256:
            raise CivitaiError("官方文件信息已变化，请取消后重新创建任务")
        directory, target, part = self.paths.check_job(job)
        directory.mkdir(parents=True, exist_ok=True)
        self.paths.check_job(job)
        if target.exists():
            raise CivitaiError("目标文件已存在，不会覆盖", 409)
        offset = part.stat().st_size if part.exists() else 0
        headers = {}
        if offset:
            headers["Range"] = f"bytes={offset}-"
            validator = job.etag if not job.etag.startswith("W/") else ""
            if validator or job.last_modified:
                headers["If-Range"] = validator or job.last_modified
        async with self.client.session() as session:
            response = await self.client.open(session, url, headers)
            try:
                if response.status == 416 and offset:
                    if response.headers.get("Content-Range") != f"bytes */{offset}":
                        raise CivitaiError("断点范围已失效，请取消后重新下载", 502)
                    job.total_size = offset
                else:
                    if response.status not in (200, 206):
                        self.client.check_status(response.status)
                    encoding = response.headers.get("Content-Encoding", "identity")
                    if encoding not in ("", "identity"):
                        raise CivitaiError("下载服务器返回了不支持的压缩格式", 502)
                    content_type = response.headers.get("Content-Type", "").lower()
                    if "text/html" in content_type or "application/json" in content_type:
                        raise CivitaiError("下载返回了网页而非模型，请检查账号权限或电脑网络", 502)
                    expected = None
                    if response.status == 206:
                        match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)",
                                             response.headers.get("Content-Range", ""))
                        if not match or int(match[1]) != offset or not (offset <= int(match[2]) < int(match[3])):
                            raise CivitaiError("下载服务器返回的断点范围不匹配", 502)
                        expected = int(match[2]) - offset + 1
                        total = int(match[3])
                    else:
                        # A 200 response to Range means restart, never append duplicate bytes.
                        offset = 0
                        total = response.content_length or 0
                        expected = response.content_length
                    job.total_size = total
                    job.downloaded_size = offset
                    job.etag = response.headers.get("ETag", "")
                    job.last_modified = response.headers.get("Last-Modified", "")
                    self.persist()
                    flags = os.O_WRONLY | os.O_CREAT | getattr(os, "O_BINARY", 0)
                    flags |= os.O_APPEND if offset else os.O_TRUNC
                    flags |= getattr(os, "O_NOFOLLOW", 0)
                    fd = os.open(part, flags, 0o600)
                    received = 0
                    started, previous = time.monotonic(), time.monotonic()
                    with os.fdopen(fd, "ab" if offset else "wb") as stream:
                        async for chunk in response.content.iter_chunked(256 * 1024):
                            stream.write(chunk)
                            received += len(chunk)
                            if expected is not None and received > expected:
                                raise CivitaiError("实际下载长度超出服务器声明", 502)
                            job.downloaded_size = offset + received
                            now = time.monotonic()
                            job.speed_bytes = received / max(0.01, now - started)
                            if now - previous > 2:
                                self.persist()
                                previous = now
                        stream.flush()
                        os.fsync(stream.fileno())
                    if expected is not None and received != expected:
                        raise CivitaiError("下载未完成，可在队列中继续", 502)
                    if job.total_size and job.downloaded_size != job.total_size:
                        raise CivitaiError("文件长度不匹配，可在队列中继续", 502)
            finally:
                response.release()
        job.status = "verifying"
        job.downloaded_size = part.stat().st_size
        self.persist()
        # Hash in chunks on the worker coroutine: cancellable between chunks and no detached
        # thread still reading a .part file after a pause/cancel response.
        checksum = hashlib.sha256()
        with part.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                checksum.update(chunk)
                await asyncio.sleep(0)
        if checksum.hexdigest() != job.sha256:
            part.unlink(missing_ok=True)
            job.downloaded_size = 0
            job.etag = job.last_modified = ""
            raise CivitaiError("SHA-256 校验未通过，临时文件已删除，请点击继续重试", 502)
        self.paths.check_job(job)
        if os.name == "nt":
            os.rename(part, target)  # Windows rename fails if target exists.
        else:
            os.link(part, target)  # Atomic publication, never overwrite a competing target.
            part.unlink()
        job.total_size = job.downloaded_size
        job.status = "completed"

    async def action(self, action: str, download_id: str):
        job = self.jobs.get(download_id)
        if job is None:
            raise CivitaiError("下载任务不存在", 404)
        if action not in ("pause", "resume", "cancel"):
            raise CivitaiError("操作应为 pause、resume 或 cancel")
        if job.status in self.TERMINAL:
            raise CivitaiError("此任务已结束，已有模型文件保留", 409)
        if action == "resume":
            if job.status in self.ACTIVE:
                return
            if self.closing:
                raise CivitaiError("电脑服务正在关闭", 503)
            self.paths.check_job(job)
            job.status, job.error = "queued", ""
            self.persist()
            self.start(job)
            return
        job.status = "paused" if action == "pause" else "cancelled"
        worker = self.workers.get(download_id)
        if worker:
            worker.cancel()
            await asyncio.gather(worker, return_exceptions=True)
            self.workers.pop(download_id, None)
        if action == "cancel":
            _, _, part = self.paths.check_job(job)
            part.unlink(missing_ok=True)
            job.downloaded_size = 0
        self.persist()

    def snapshot(self):
        jobs = list(self.jobs.values())
        return {
            "queue": [job.public() for job in reversed(jobs)],
            "stats": {state: sum(job.status == state for job in jobs) for state in
                      ("queued", "downloading", "paused", "verifying", "completed", "failed", "cancelled")},
        }

    async def close(self):
        self.closing = True
        workers = list(self.workers.values())
        for job in self.jobs.values():
            if job.status in self.ACTIVE:
                job.status = "paused"
        for worker in workers:
            worker.cancel()
        if workers:
            await asyncio.gather(*workers, return_exceptions=True)
        if self.jobs:
            self.persist()
