"""Run with: python -m unittest discover -s tests -v (aiohttp from ComfyUI).

All downloads are small generated loopback fixtures; no accounts, model files or
Lora Manager service are required.
"""
import asyncio
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit

from aiohttp import ClientSession, ClientTimeout, web
from aiohttp.test_utils import TestClient, TestServer


PLUGIN = Path(__file__).resolve().parents[1]
PACKAGE = "_mobile_bridge_test"
if PACKAGE not in sys.modules:
    package = types.ModuleType(PACKAGE)
    package.__path__ = [str(PLUGIN)]
    sys.modules[PACKAGE] = package

credentials = __import__(f"{PACKAGE}.credentials", fromlist=["CredentialStore"])
transport = __import__(f"{PACKAGE}.civitai_client", fromlist=["CivitaiClient"])
downloads = __import__(f"{PACKAGE}.civitai_downloads", fromlist=["DownloadQueue"])
service_module = __import__(f"{PACKAGE}.civitai_service", fromlist=["CivitaiService"])


class FakeFolders:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.values = {key: [str(self.directory / key)] for key in
                       ("loras", "checkpoints", "vae", "text_encoders", "diffusion_models")}
        for values in self.values.values():
            Path(values[0]).mkdir()

    def get_folder_paths(self, key):
        return self.values.get(key, [])

    def get_user_directory(self):
        return str(self.directory / "user")

    def get_filename_list(self, key):
        return []


class RewritingSession:
    """Exercise the real HTTP/redirect code against loopback, preserving original headers."""
    def __init__(self, base):
        self.base = base
        self.session = None

    async def __aenter__(self):
        self.session = ClientSession(auto_decompress=False, timeout=ClientTimeout(total=8))
        return self

    async def __aexit__(self, *args):
        await self.session.close()

    async def get(self, url, **kwargs):
        parsed = urlsplit(url)
        if parsed.hostname not in ("civitai.com", "cdn.example"):
            raise AssertionError("Unexpected fixture host")
        target = self.base + parsed.path + ("?" + parsed.query if parsed.query else "")
        # Never inherit the developer computer's external proxy for fixture tests.
        kwargs["proxy"] = None
        return await self.session.get(target, **kwargs)


class FixtureClient(transport.CivitaiClient):
    def __init__(self, store, base):
        super().__init__(store)
        self.base = base

    def session(self):
        return RewritingSession(self.base)


class BridgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.folders = FakeFolders(self.directory)
        self.store = credentials.CredentialStore(self.directory / "private")
        self.store.save("TEST_KEY")
        self.body = b"fixture-model-data-" * 16384
        self.digest = hashlib.sha256(self.body).hexdigest()
        self.requests = []
        self.slow = False
        self.range_mode = "normal"
        self.forbidden = False
        self.corrupt = False
        self.html = False
        self.catalog = {"items": [], "metadata": {}}
        self.plugins = []
        self.version = {
            "id": 20, "modelId": 10, "name": "fixture-version", "canDownload": True,
            "model": {"name": "fixture", "type": "LORA"},
            "files": [{
                "id": 30, "name": "fixture.safetensors", "type": "Model", "primary": True,
                "downloadUrl": "https://civitai.com/api/download/models/20?token=OLD_TOKEN",
                "hashes": {"SHA256": self.digest},
            }, {
                "id": 31, "name": "fixture-vae.safetensors", "type": "VAE",
                "downloadUrl": "https://civitai.com/api/download/models/20",
                "hashes": {"SHA256": self.digest},
            }],
        }
        upstream = web.Application()
        upstream.router.add_get("/{tail:.*}", self.upstream)
        self.server = TestServer(upstream)
        await self.server.start_server()
        self.client = FixtureClient(self.store, str(self.server.make_url("")).rstrip("/"))
        self.queue = downloads.DownloadQueue(
            self.client, downloads.ModelPaths(self.folders), self.directory / "queue")
        self.service = service_module.CivitaiService(self.folders, self.directory / "service", self.client)
        self.service.credentials = self.store
        app = web.Application()
        self.service.register(app)
        self.http = TestClient(TestServer(app))
        await self.http.start_server()
        self.headers = {"X-Comfy-Mobile-Settings": "1"}

    async def asyncTearDown(self):
        for plugin in self.plugins:
            await plugin._get_civitai_service().queue.close()
        await self.queue.close()
        await self.http.close()
        await self.server.close()
        self.temp.cleanup()

    async def upstream(self, request):
        self.requests.append({"path": request.path, "query": dict(request.query),
                              "headers": dict(request.headers)})
        if request.path == "/api/v1/me":
            if request.headers.get("Authorization") != "Bearer TEST_KEY":
                return web.json_response({"secret": "never echo"}, status=401)
            return web.json_response({"id": 1})
        if request.path == "/api/v1/models":
            return web.json_response(self.catalog)
        if request.path == "/api/v1/model-versions/20":
            return web.json_response(self.version)
        if request.path.startswith("/api/download/"):
            if self.forbidden:
                return web.json_response({"error": "TEST_KEY secret provider response"}, status=403)
            raise web.HTTPFound("https://cdn.example/files/fixture")
        if request.path == "/files/fixture":
            if self.html:
                return web.Response(text="<html>not a model</html>", content_type="text/html")
            payload = (b"x" + self.body[1:]) if self.corrupt else self.body
            offset = 0
            code = 200
            headers = {"ETag": '"fixture-etag"'}
            if request.headers.get("Range") and self.range_mode != "ignore":
                offset = int(request.headers["Range"].split("=")[1].split("-")[0])
                if offset >= len(payload):
                    return web.Response(status=416, headers={"Content-Range": f"bytes */{len(payload)}"})
                code = 206
                start = offset + 1 if self.range_mode == "wrong" else offset
                headers["Content-Range"] = f"bytes {start}-{len(payload)-1}/{len(payload)}"
            remaining = payload[offset:]
            headers["Content-Length"] = str(len(remaining))
            response = web.StreamResponse(status=code, headers=headers)
            await response.prepare(request)
            try:
                for index in range(0, len(remaining), 2048 if self.slow else len(remaining)):
                    await response.write(remaining[index:index + (2048 if self.slow else len(remaining))])
                    if self.slow:
                        await asyncio.sleep(0.004)
                await response.write_eof()
            except (ConnectionResetError, asyncio.CancelledError):
                pass
            return response
        return web.Response(status=404)

    async def submit(self, **overrides):
        return await self.queue.submit({"modelId": 10, "modelVersionId": 20, "fileId": 30, **overrides})

    async def settled(self, download_id):
        for _ in range(500):
            job = self.queue.jobs[download_id]
            if job.status in {"completed", "failed", "paused", "cancelled"}:
                return job
            await asyncio.sleep(0.01)
        self.fail("Fixture download did not settle")

    async def partial(self, download_id):
        for _ in range(500):
            job = self.queue.jobs[download_id]
            if job.downloaded_size >= 4096:
                return job
            if job.status == "failed":
                self.fail(job.error)
            await asyncio.sleep(0.005)
        self.fail("Fixture did not make progress")

    async def settings(self, action, **values):
        return await self.http.post("/api/mobile/civitai/settings",
                                    json={"action": action, **values}, headers=self.headers)

    async def test_settings_status_is_redacted(self):
        response = await self.http.get("/api/mobile/civitai/settings", headers=self.headers)
        self.assertEqual(response.status, 200)
        body = await response.text()
        self.assertNotIn("TEST_KEY", body)
        self.assertTrue(json.loads(body)["data"]["configured"])
        self.assertEqual(response.headers["Cache-Control"], "no-store")

    async def test_settings_save_reload_and_clear(self):
        response = await self.settings("save", key="NEW_KEY")
        self.assertEqual(response.status, 200)
        self.assertNotIn("NEW_KEY", await response.text())
        self.assertEqual(credentials.CredentialStore(self.directory / "private").key(), "NEW_KEY")
        with patch.dict(os.environ, {"CIVITAI_API_KEY": ""}):
            await self.settings("clear")
            self.assertEqual(self.store.key(), "")

    async def test_empty_save_preserves_existing_key(self):
        response = await self.settings("save", key="")
        self.assertEqual(response.status, 400)
        self.assertEqual(self.store.key(), "TEST_KEY")

    async def test_key_with_header_injection_is_rejected(self):
        response = await self.settings("save", key="ABC\r\nX-Header: value")
        self.assertEqual(response.status, 400)
        self.assertEqual(self.store.key(), "TEST_KEY")

    async def test_test_saved_key_and_draft_does_not_save(self):
        response = await self.settings("test")
        self.assertEqual(response.status, 200)
        response = await self.settings("test", key="BAD_DRAFT_KEY")
        self.assertEqual(response.status, 401)
        self.assertNotIn("BAD_DRAFT_KEY", await response.text())
        self.assertEqual(self.store.key(), "TEST_KEY")
        self.assertEqual(credentials.CredentialStore(self.directory / "private").key(), "TEST_KEY")

    async def test_clear_falls_back_to_environment(self):
        with patch.dict(os.environ, {"CIVITAI_API_KEY": "ENV_KEY"}):
            response = await self.settings("clear")
            data = await response.json()
            self.assertEqual(data["data"]["source"], "environment")
            self.assertEqual(self.store.key(), "ENV_KEY")
            self.assertNotIn("ENV_KEY", json.dumps(data))

    async def test_cross_origin_settings_write_is_blocked(self):
        response = await self.http.post("/api/mobile/civitai/settings", json={"action": "clear"},
                                        headers={**self.headers, "Origin": "https://unrelated.example"})
        self.assertEqual(response.status, 403)
        self.assertEqual(self.store.key(), "TEST_KEY")

    async def test_settings_write_requires_custom_header(self):
        response = await self.http.post("/api/mobile/civitai/settings", json={"action": "clear"})
        self.assertEqual(response.status, 403)

    async def test_same_origin_settings_write(self):
        origin = str(self.http.make_url("")).rstrip("/")
        response = await self.http.post("/api/mobile/civitai/settings", json={"action": "test"},
                                        headers={**self.headers, "Origin": origin})
        self.assertEqual(response.status, 200)

    async def test_https_terminating_reverse_proxy_same_host(self):
        origin = str(self.http.make_url("")).rstrip("/").replace("http:", "https:", 1)
        response = await self.http.post("/api/mobile/civitai/settings", json={"action": "test"},
                                        headers={**self.headers, "Origin": origin,
                                                 "Sec-Fetch-Site": "same-origin"})
        self.assertEqual(response.status, 200)

    async def test_cross_site_fetch_header_is_rejected_even_without_origin(self):
        response = await self.http.post("/api/mobile/civitai/settings", json={"action": "clear"},
                                        headers={**self.headers, "Sec-Fetch-Site": "cross-site"})
        self.assertEqual(response.status, 403)

    async def test_bad_json_body_and_size_limit(self):
        response = await self.http.post("/api/mobile/civitai/settings", json=[], headers=self.headers)
        self.assertEqual(response.status, 400)
        response = await self.http.post("/api/mobile/civitai/settings", data="x" * 5000, headers=self.headers)
        self.assertEqual(response.status, 413)

    async def test_native_routes_and_legacy_aliases(self):
        for route in ("tasks", "native-tasks"):
            response = await self.http.get("/api/mobile/civitai/" + route)
            self.assertEqual(response.status, 200)
        response = await self.http.post("/api/mobile/civitai/native-download",
                                        json={"modelId": 10, "modelVersionId": 20, "fileId": 30})
        self.assertEqual(response.status, 200)
        response = await self.http.post("/api/mobile/civitai/native-task-action",
                                        json={"action": "invalid", "downloadId": "missing"})
        self.assertEqual(response.status, 404)

    async def test_successful_download_and_credential_redirect_isolation(self):
        job = await self.settled((await self.submit())["download_id"])
        self.assertEqual(job.status, "completed", job.error)
        target = Path(self.folders.values["loras"][0]) / job.filename
        self.assertEqual(hashlib.sha256(target.read_bytes()).hexdigest(), self.digest)
        self.assertFalse(list(target.parent.glob("*.part")))
        download_request = next(r for r in self.requests if r["path"].startswith("/api/download/"))
        self.assertEqual(download_request["headers"].get("Authorization"), "Bearer TEST_KEY")
        self.assertNotIn("token", download_request["query"])
        cdn_request = next(r for r in self.requests if r["path"].startswith("/files/"))
        self.assertNotIn("Authorization", cdn_request["headers"])
        self.assertNotIn("TEST_KEY", self.queue.state_file.read_text())
        self.assertNotIn("https://", self.queue.state_file.read_text())
        self.assertNotIn("index_status", self.queue.state_file.read_text(),
                         "The old queue schema must remain readable on plugin rollback")

    async def test_client_download_url_is_ignored(self):
        job = await self.settled((await self.submit(downloadUrl="http://127.0.0.1/private"))["download_id"])
        self.assertEqual(job.status, "completed")
        self.assertTrue(all("private" not in r["path"] for r in self.requests))

    async def test_server_selects_primary_file_and_uses_own_account_permissions(self):
        # A phone may browse anonymously / with a different account. Only the fresh,
        # server-authenticated version metadata decides download permission.
        job = await self.settled((await self.submit(fileId=None, canDownload=False))["download_id"])
        self.assertEqual(job.status, "completed", job.error)
        self.assertEqual(job.file_id, 30)

    async def test_wrong_model_version_is_rejected(self):
        with self.assertRaises(transport.CivitaiError):
            await self.submit(modelId=11)

    async def test_unknown_file_is_rejected(self):
        with self.assertRaises(transport.CivitaiError):
            await self.submit(fileId=999)

    async def test_metadata_denied_does_not_create_download(self):
        self.version["canDownload"] = False
        with self.assertRaises(transport.CivitaiError) as error:
            await self.submit()
        self.assertEqual(error.exception.status, 403)
        self.assertFalse(self.queue.jobs)

    async def test_duplicate_queued_destination_is_rejected(self):
        self.slow = True
        job = await self.submit()
        with self.assertRaises(transport.CivitaiError) as error:
            await self.submit()
        self.assertEqual(error.exception.status, 409)
        await self.queue.action("cancel", job["download_id"])

    async def test_queue_runs_at_most_two_downloads_concurrently(self):
        self.slow = True
        jobs = [await self.submit(subfolder=f"slot-{index}") for index in range(3)]
        for _ in range(100):
            active = [job for job in self.queue.jobs.values() if job.status == "downloading"]
            if len(active) == 2:
                break
            await asyncio.sleep(0.005)
        self.assertEqual(len(active), 2)
        self.assertEqual(self.queue.jobs[jobs[2]["download_id"]].status, "queued")
        for item in jobs:
            await self.queue.action("cancel", item["download_id"])

    async def test_missing_hash_is_rejected(self):
        self.version["files"][0]["hashes"] = {}
        with self.assertRaises(transport.CivitaiError):
            await self.submit()

    async def test_existing_model_is_never_overwritten(self):
        target = Path(self.folders.values["loras"][0]) / "fixture.safetensors"
        target.write_bytes(b"ORIGINAL")
        with self.assertRaises(transport.CivitaiError):
            await self.submit()
        self.assertEqual(target.read_bytes(), b"ORIGINAL")

    async def test_path_traversal_and_unregistered_root(self):
        for sub in ("../outside", "/absolute", "C:\\outside", "safe/../../outside"):
            with self.subTest(sub=sub), self.assertRaises(transport.CivitaiError):
                await self.submit(subfolder=sub)
        with self.assertRaises(transport.CivitaiError):
            await self.submit(modelRoot=str(self.directory / "outside"))

    async def test_unsafe_official_filename(self):
        for name in ("../model.safetensors", "CON.safetensors", "model.exe", "a:stream.safetensors"):
            with self.subTest(name=name), self.assertRaises(transport.CivitaiError):
                downloads.safe_filename(name)

    async def test_component_defaults_use_vae_before_lora_root(self):
        job = await self.settled((await self.submit(fileId=31, modelType="LORA", fileType="Model"))["download_id"])
        self.assertEqual(job.status, "completed")
        self.assertEqual(Path(job.root), Path(self.folders.values["vae"][0]))

    async def test_explicit_registered_directory_is_respected(self):
        root = self.folders.values["checkpoints"][0]
        job = await self.settled((await self.submit(modelRoot=root, subfolder="nested"))["download_id"])
        self.assertEqual(job.status, "completed")
        self.assertTrue((Path(root) / "nested" / job.filename).is_file())

    async def test_checksum_failure_removes_only_part(self):
        self.corrupt = True
        job = await self.settled((await self.submit())["download_id"])
        self.assertEqual(job.status, "failed")
        directory, target, part = self.queue.paths.check_job(job)
        self.assertFalse(part.exists())
        self.assertFalse(target.exists())
        self.assertIn("SHA-256", job.error)
        self.corrupt = False
        await self.queue.action("resume", job.download_id)
        self.assertEqual((await self.settled(job.download_id)).status, "completed")

    async def test_pause_resume_uses_validated_range(self):
        self.slow = True
        job = await self.partial((await self.submit())["download_id"])
        await self.queue.action("pause", job.download_id)
        self.assertEqual(job.status, "paused")
        self.assertTrue(self.queue.paths.check_job(job)[2].exists())
        await self.queue.action("resume", job.download_id)
        self.assertEqual((await self.settled(job.download_id)).status, "completed", job.error)
        ranges = [r["headers"] for r in self.requests if "Range" in r["headers"]]
        self.assertTrue(ranges)
        self.assertEqual(ranges[-1].get("If-Range"), '"fixture-etag"')

    async def test_ignored_range_restarts_instead_of_appending(self):
        self.slow = True
        job = await self.partial((await self.submit())["download_id"])
        await self.queue.action("pause", job.download_id)
        self.range_mode = "ignore"
        await self.queue.action("resume", job.download_id)
        self.assertEqual((await self.settled(job.download_id)).status, "completed", job.error)
        self.assertEqual((Path(job.root) / job.filename).read_bytes(), self.body)

    async def test_incorrect_range_is_not_written(self):
        self.slow = True
        job = await self.partial((await self.submit())["download_id"])
        await self.queue.action("pause", job.download_id)
        part = self.queue.paths.check_job(job)[2]
        previous = part.read_bytes()
        self.range_mode = "wrong"
        await self.queue.action("resume", job.download_id)
        self.assertEqual((await self.settled(job.download_id)).status, "failed")
        self.assertEqual(part.read_bytes(), previous)

    async def test_cancel_preserves_other_files_and_cannot_delete_completed(self):
        self.slow = True
        job = await self.partial((await self.submit())["download_id"])
        other = Path(job.root) / "other.safetensors"
        other.write_bytes(b"ORIGINAL")
        await self.queue.action("cancel", job.download_id)
        self.assertFalse(self.queue.paths.check_job(job)[2].exists())
        self.assertEqual(other.read_bytes(), b"ORIGINAL")
        self.assertEqual(job.status, "cancelled")
        self.slow = False
        finished = await self.settled((await self.submit())["download_id"])
        with self.assertRaises(transport.CivitaiError):
            await self.queue.action("cancel", finished.download_id)
        self.assertTrue((Path(finished.root) / finished.filename).exists())

    async def test_immediate_pause_before_worker_starts(self):
        job = await self.submit()
        await self.queue.action("pause", job["download_id"])
        self.assertEqual(self.queue.jobs[job["download_id"]].status, "paused")
        self.assertNotIn(job["download_id"], self.queue.workers)

    async def test_restart_does_not_autostart_incomplete_download(self):
        self.slow = True
        job = await self.partial((await self.submit())["download_id"])
        await self.queue.close()
        self.queue = downloads.DownloadQueue(self.client, downloads.ModelPaths(self.folders),
                                             self.directory / "queue")
        restored = self.queue.jobs[job.download_id]
        self.assertEqual(restored.status, "paused")
        self.assertFalse(self.queue.workers)
        await self.queue.action("resume", job.download_id)
        self.assertEqual((await self.settled(job.download_id)).status, "completed", job.error)

    async def test_error_does_not_echo_provider_body_or_key(self):
        self.forbidden = True
        job = await self.settled((await self.submit())["download_id"])
        self.assertEqual(job.status, "failed")
        self.assertIn("权限", job.error)
        self.assertNotIn("TEST_KEY", json.dumps(self.queue.snapshot()))
        self.assertNotIn("secret provider", job.error)

    async def test_html_is_not_saved_as_a_model(self):
        self.html = True
        job = await self.settled((await self.submit())["download_id"])
        self.assertEqual(job.status, "failed")
        self.assertFalse((Path(job.root) / job.filename).exists())

    async def test_no_auth_on_third_party_http_or_non_api(self):
        for url in ("https://cdn.example/file", "http://civitai.com/api/v1/me",
                    "https://civitai.com:8443/api/v1/me", "https://civitai.com/images"):
            self.assertNotIn("Authorization", self.client.headers(url))
        self.assertIn("Authorization", self.client.headers("https://civitai.com/api/v1/me"))

    async def test_non_https_or_private_redirects_are_rejected(self):
        for url in ("http://civitai.com/api/download/models/20", "https://127.0.0.1/file",
                    "https://user:pass@civitai.com/file", "https://localhost/file"):
            with self.subTest(url=url), self.assertRaises(transport.CivitaiError):
                self.client.check_url(url)

    async def test_corrupt_credentials_file_is_preserved_until_explicit_save(self):
        self.store.path.write_text("broken", encoding="utf-8")
        other = credentials.CredentialStore(self.store.path.parent)
        self.assertTrue(other.status()["unreadable"])
        self.assertEqual(self.store.path.read_text(), "broken")
        other.save("REPLACED_KEY")
        self.assertFalse(other.status()["unreadable"])

    async def test_credentials_storage_has_owner_scope(self):
        raw = self.store.path.read_text()
        if os.name == "nt":
            self.assertNotIn("TEST_KEY", raw)
            self.assertEqual(json.loads(raw)["storage"], "windows-dpapi")
        else:
            self.assertEqual(self.store.path.stat().st_mode & 0o777, 0o600)

    def load_plugin(self):
        name = "_bridge_integration_" + str(len(self.plugins))
        server = types.ModuleType("server")
        app = web.Application()
        server.PromptServer = types.SimpleNamespace(instance=types.SimpleNamespace(app=app))
        folders = types.ModuleType("folder_paths")
        folders.get_folder_paths = self.folders.get_folder_paths
        folders.get_user_directory = self.folders.get_user_directory
        spec = importlib.util.spec_from_file_location(name, PLUGIN / "__init__.py",
                                                     submodule_search_locations=[str(PLUGIN)])
        module = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {"server": server, "folder_paths": folders, name: module}):
            spec.loader.exec_module(module)
        self.plugins.append(module)
        module._get_civitai_service().client = self.client
        return module, app

    async def test_comfyui_plugin_exports_and_native_routes_register(self):
        module, app = self.load_plugin()
        self.assertEqual(module.WEB_DIRECTORY, "./web")
        routes = {route.resource.canonical for route in app.router.routes()}
        self.assertIn("/api/mobile/civitai/native-download", routes)
        self.assertIn("/api/mobile/civitai/settings", routes)
        status = await module.handle_mobile_status(None)
        self.assertIn("native-civitai-downloads", json.loads(status.text)["data"]["capabilities"])

    async def test_bridge_search_uses_server_key_and_opaque_cursor(self):
        _, app = self.load_plugin()
        version = dict(self.version, images=[
            {"id": 1, "url": "https://image.civitai.com/fixture/width=450/test.jpg",
             "meta": {"prompt": "fixture prompt", "negativePrompt": "fixture negative", "seed": 0}}
        ])
        self.catalog = {
            "items": [{"id": 10, "name": "fixture", "type": "LORA", "modelVersions": [version]}],
            "metadata": {"nextPage": "https://civitai.com/api/v1/models?cursor=opaque-next"},
        }
        async with TestClient(TestServer(app)) as client:
            response = await client.get("/api/mobile/civitai/search?query=fixture&cursor=opaque-start&page=5")
            self.assertEqual(response.status, 200)
            body = await response.json()
        self.assertEqual(body["data"]["nextCursor"], "opaque-next")
        image = body["data"]["items"][0]["versions"][0]["images"][0]
        self.assertEqual(image["seed"], "0")
        self.assertEqual(image["prompt"], "fixture prompt")
        upstream = self.requests[-1]
        self.assertEqual(upstream["headers"].get("Authorization"), "Bearer TEST_KEY")
        self.assertEqual(upstream["query"]["cursor"], "opaque-start")
        self.assertNotIn("page", upstream["query"])
        self.assertNotIn("sort", upstream["query"])
        self.assertFalse(any("/api/lm/" in item["path"] for item in self.requests))

    async def test_search_categories_use_canonical_types_and_gguf_array(self):
        _, app = self.load_plugin()
        captured = []
        async def fake_catalog(endpoint, params):
            captured.append(list(params))
            return {"items": [], "metadata": {}}
        with patch.object(self.client, "json", new=fake_catalog):
            async with TestClient(TestServer(app)) as client:
                for kind in ("CHECKPOINT", "UNET", "GGUF"):
                    response = await client.get("/api/mobile/civitai/search", params={"type": kind})
                    self.assertEqual(response.status, 200)
        self.assertEqual([v for k, v in captured[0] if k == "types"], ["Checkpoint"])
        self.assertEqual([v for k, v in captured[1] if k == "types"], ["UNet"])
        self.assertEqual([v for k, v in captured[2] if k == "types"], ["Checkpoint", "UNet"])
        self.assertEqual([v for k, v in captured[2] if k == "fileFormats"], ["GGUF", "GGUF"])
        self.assertTrue(all(k != "earlyAccess" for params in captured for k, _ in params))

    async def test_gguf_search_keeps_base_filter_and_cursor(self):
        _, app = self.load_plugin()
        captured = []
        async def fake_catalog(endpoint, params):
            captured.extend(params)
            return {"items": [], "metadata": {}}
        with patch.object(self.client, "json", new=fake_catalog):
            async with TestClient(TestServer(app)) as client:
                response = await client.get("/api/mobile/civitai/search", params={
                    "type": "GGUF", "baseModel": "Krea 2", "cursor": "opaque|next"})
                self.assertEqual(response.status, 200)
        self.assertIn(("baseModels", "Krea 2"), captured)
        self.assertIn(("cursor", "opaque|next"), captured)
        self.assertFalse(any(k == "page" for k, _ in captured))

    async def test_search_author_sort_time_and_lora_family_reach_official_api(self):
        _, app = self.load_plugin()
        captured = []
        async def fake_catalog(endpoint, params):
            captured.append(list(params))
            return {"items": [], "metadata": {}}
        with patch.object(self.client, "json", new=fake_catalog):
            async with TestClient(TestServer(app)) as client:
                response = await client.get("/api/mobile/civitai/search", params={
                    "username": "@fixture-author", "sort": "Most Liked", "period": "Week",
                    "type": "LORA", "nsfw": "false"})
                self.assertEqual(response.status, 200)
        params = captured[0]
        self.assertIn(("username", "fixture-author"), params)
        self.assertIn(("sort", "Most Liked"), params)
        self.assertIn(("period", "Week"), params)
        self.assertIn(("nsfw", "false"), params)
        self.assertEqual([v for k, v in params if k == "types"], ["LORA", "LoCon", "DoRA"])

    async def test_model_id_lookup_and_keyword_search_use_different_query_paths(self):
        _, app = self.load_plugin()
        captured = []
        async def fake_catalog(endpoint, params):
            captured.append(list(params))
            return {"items": [], "metadata": {}}
        with patch.object(self.client, "json", new=fake_catalog):
            async with TestClient(TestServer(app)) as client:
                for values in [
                    {"ids": "123", "query": "https://civitai.com/models/123", "nsfw": "false"},
                    {"query": "fixture", "username": "artist", "sort": "Newest",
                     "period": "Month", "cursor": "opaque|2", "page": "4"},
                ]:
                    response = await client.get("/api/mobile/civitai/search", params=values)
                    self.assertEqual(response.status, 200)
        self.assertIn(("ids", "123"), captured[0])
        self.assertIn(("nsfw", "false"), captured[0])
        self.assertFalse(any(k == "query" for k, _ in captured[0]))
        self.assertIn(("query", "fixture"), captured[1])
        self.assertIn(("username", "artist"), captured[1])
        self.assertIn(("period", "Month"), captured[1])
        self.assertIn(("cursor", "opaque|2"), captured[1])
        self.assertFalse(any(k in {"page", "sort"} for k, _ in captured[1]))

    async def test_search_preserves_publication_dates_and_popularity(self):
        _, app = self.load_plugin()
        self.catalog = {"items": [{"id": 10, "stats": {"downloadCount": 20, "thumbsUpCount": 30},
            "modelVersions": [dict(self.version, publishedAt="2026-10-17T00:00:00Z")]}]}
        async with TestClient(TestServer(app)) as client:
            response = await client.get("/api/mobile/civitai/search")
            self.assertEqual(response.status, 200)
            data = (await response.json())["data"]["items"][0]
        self.assertEqual(data["publishedAt"], "2026-10-17T00:00:00Z")
        self.assertEqual(data["versions"][0]["publishedAt"], data["publishedAt"])
        self.assertEqual(data["downloadCount"], 20)
        self.assertEqual(data["thumbsUpCount"], 30)

    async def test_bridge_search_invalid_pagination_does_not_contact_upstream(self):
        _, app = self.load_plugin()
        async with TestClient(TestServer(app)) as client:
            response = await client.get("/api/mobile/civitai/search?page=bad")
            self.assertEqual(response.status, 400)
        self.assertFalse(self.requests)

    async def test_bridge_search_rejects_external_next_url_and_handles_null_meta(self):
        _, app = self.load_plugin()
        self.catalog = {
            "items": [None, {"id": 10, "modelVersions": [None, dict(self.version, images=[
                None, {"url": "https://image.civitai.com/test.jpg", "meta": {"seed": None}}
            ])]}],
            "metadata": {"nextPage": "https://unrelated.example/models?cursor=untrusted"},
        }
        async with TestClient(TestServer(app)) as client:
            response = await client.get("/api/mobile/civitai/search")
            self.assertEqual(response.status, 200)
            body = await response.json()
        self.assertIsNone(body["data"]["nextCursor"])
        self.assertEqual(body["data"]["items"][0]["versions"][0]["images"][0]["seed"], "")


if __name__ == "__main__":
    unittest.main()
