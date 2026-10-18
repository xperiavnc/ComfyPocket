"""Local v1 contract/data tests; never queues a ComfyUI prompt."""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from test_bridge import PACKAGE, transport
index_module = __import__(f"{PACKAGE}.model_index", fromlist=["ModelIndex"])
center_module = __import__(f"{PACKAGE}.model_center", fromlist=["ModelCenterService"])


class Folders:
    def __init__(self, directory):
        self.values = {"loras": [str(directory / "loras"), str(directory / "second")],
                       "diffusion_models": [str(directory / "unet")]}
        for values in self.values.values():
            for path in values:
                Path(path).mkdir()

    def get_folder_paths(self, key):
        return self.values.get(key, [])

    def get_filename_list(self, key):
        return sorted({p.relative_to(root).as_posix() for root in map(Path, self.values.get(key, []))
                       for p in root.rglob("*") if p.is_file()})

    def get_full_path(self, key, name):
        for root in self.values.get(key, []):
            p = Path(root) / name
            if p.is_file():
                return str(p)


class Client:
    def __init__(self):
        self.version = {}
        self.error = None
        self.calls = []
        self.delay = 0

    async def json(self, endpoint):
        self.calls.append(endpoint)
        await asyncio.sleep(self.delay)
        if self.error:
            raise self.error
        return self.version


class ModelCenterTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.folders = Folders(self.directory)
        self.file = Path(self.folders.values["loras"][0]) / "original.safetensors"
        self.file.write_bytes(b"fixture original")
        self.digest = hashlib.sha256(self.file.read_bytes()).hexdigest()
        self.client = Client()
        self.client.version = {"id": 20, "modelId": 10, "name": "常服",
                               "model": {"name": "Fixture"}, "baseModel": "SDXL",
                               "trainedWords": ["Exact_Trigger (A)", "UPPER_case"],
                               "description": "<p>Fixture description</p>",
                               "files": [{"id": 99, "primary": True, "hashes": {"SHA256": "0"*64}},
                                         {"id": 30, "hashes": {"SHA256": self.digest.upper()}}]}
        self.center = center_module.ModelCenterService(self.folders, self.directory / "user", self.client)
        self.index = self.center.index
        app = web.Application()
        self.center.register(app)
        self.http = TestClient(TestServer(app))
        await self.http.start_server()
        await asyncio.to_thread(self.index.scan, threading.Event(), {"discovered": 0})
        self.item = (await asyncio.to_thread(self.index.query, {}))["items"][0]

    async def asyncTearDown(self):
        await self.http.close()
        self.temp.cleanup()

    async def query(self, **params):
        reply = await self.http.get("/api/mobile/model-center/models", params=params)
        self.assertEqual(reply.status, 200, await reply.text())
        return (await reply.json())["data"]

    async def enrich(self, **options):
        reply = await self.http.post("/api/mobile/model-center/enrichment",
                                    json={"locationIds": [self.item["id"]], **options})
        self.assertEqual(reply.status, 200, await reply.text())
        data = (await reply.json())["data"]
        if data["errors"]:
            return data
        job = data["tasks"][0]
        for _ in range(300):
            current = self.center.jobs.get(job["id"], job)
            if current["status"] not in ("queued", "hashing", "matching"):
                return dict(current)
            await asyncio.sleep(.01)
        self.fail("Task did not settle")

    async def annotate(self, annotation, revision=0, **extra):
        return await self.http.post("/api/mobile/model-center/models/" + self.item["id"] + "/annotation",
                                    json={"expectedRevision": revision, "annotation": annotation, **extra})

    async def test_local_cover_before_hash_or_provider_matching(self):
        image = b"\x89PNG\r\n\x1a\n" + b"fixture preview"
        self.file.with_suffix(".preview.png").write_bytes(image)
        item = (await self.query())["items"][0]
        self.assertIsNone(item["sha256"])
        self.assertEqual("local", item["preview"]["source"])
        reply = await self.http.get("/api/mobile/model-center/models/" + item["id"] + "/preview")
        self.assertEqual(200, reply.status)
        self.assertEqual("image/png", reply.content_type)
        self.assertEqual(image, await reply.read())
        self.assertEqual([], self.client.calls)
        self.assertEqual(b"fixture original", self.file.read_bytes())

    async def test_cover_revision_changes_and_file_extension_sidecars_work(self):
        cover = Path(str(self.file) + ".jpg")
        cover.write_bytes(b"\xff\xd8" + b"cover one")
        before = (await self.query())["items"][0]["preview"]
        cover.write_bytes(b"\xff\xd8" + b"cover two changed")
        after = (await self.query())["items"][0]["preview"]
        self.assertEqual("local", after["source"])
        self.assertNotEqual(before["version"], after["version"])
        cover.unlink()
        self.assertEqual("none", (await self.query())["items"][0]["preview"]["source"])

    async def test_duplicate_model_names_keep_location_specific_covers(self):
        second = Path(self.folders.values["loras"][1]) / self.file.name
        second.write_bytes(self.file.read_bytes())
        self.file.with_suffix(".png").write_bytes(b"\x89PNG\r\n\x1a\nfirst")
        second.with_suffix(".png").write_bytes(b"\x89PNG\r\n\x1a\nsecond")
        self.index.scan(threading.Event(), {"discovered": 0})
        items = (await self.query())["items"]
        self.assertEqual(2, len(items))
        payloads = []
        for item in items:
            reply = await self.http.get("/api/mobile/model-center/models/" + item["id"] + "/preview")
            self.assertEqual(200, reply.status)
            payloads.append(await reply.read())
        self.assertEqual(2, len(set(payloads)))

    async def test_missing_or_invalid_cover_has_explicit_empty_state(self):
        self.file.with_suffix(".png").write_text("not an image")
        item = (await self.query())["items"][0]
        self.assertEqual("none", item["preview"]["source"])
        reply = await self.http.get("/api/mobile/model-center/models/" + item["id"] + "/preview")
        self.assertEqual(404, reply.status)
        self.assertEqual([], self.client.calls)

    async def test_provider_cover_fallback_and_local_cover_priority(self):
        url = "https://image.civitai.com/fixture.png"
        self.client.version["images"] = [{"url": url, "type": "image"}]
        result = await self.enrich()
        self.assertEqual("completed", result["status"])
        item = (await self.query())["items"][0]
        self.assertEqual("provider", item["preview"]["source"])
        cache = self.index.directory / "previews" / self.digest / (hashlib.sha256(url.encode()).hexdigest() + ".image")
        cache.parent.mkdir(parents=True)
        payload = b"\x89PNG\r\n\x1a\nprovider fixture"
        cache.write_bytes(payload)
        reply = await self.http.get("/api/mobile/model-center/models/" + item["id"] + "/preview")
        self.assertEqual(200, reply.status)
        self.assertEqual("image/png", reply.content_type)
        self.assertEqual(payload, await reply.read())
        self.file.with_suffix(".webp").write_bytes(b"RIFF1234WEBPlocal")
        self.assertEqual("local", (await self.query())["items"][0]["preview"]["source"])
        reply = await self.http.get("/api/mobile/model-center/models/" + item["id"] + "/preview")
        self.assertEqual(b"RIFF1234WEBPlocal", await reply.read())

    async def test_only_requested_page_probes_cover_files(self):
        for number in range(4):
            self.file.with_name(f"page-{number}.safetensors").write_bytes(b"fixture")
        self.index.scan(threading.Event(), {"discovered": 0})
        with patch.object(self.index, "with_preview", wraps=self.index.with_preview) as cover:
            result = await self.query(pageSize=2, page=2)
            self.assertEqual(2, len(result["items"]))
            self.assertEqual(2, cover.call_count)

    async def test_contract_and_persistent_server_id(self):
        reply = await self.http.get("/api/mobile/model-center/capabilities")
        data = (await reply.json())["data"]
        self.assertEqual(data["schemaVersion"], 1)
        self.assertIn("hash-enrichment", data["capabilities"])
        fresh = index_module.ModelIndex(self.folders, self.directory / "user")
        self.assertEqual(data["serverId"], fresh.info()["serverId"])
        other = index_module.ModelIndex(self.folders, self.directory / "other")
        self.assertNotEqual(data["serverId"], other.info()["serverId"])
        self.assertFalse(self.client.calls)

    async def test_exact_node_option_and_format_not_use_case(self):
        data = await self.query()
        self.assertEqual(data["items"][0]["nodeModelName"], "original.safetensors")
        self.assertEqual(data["items"][0]["loadStatus"], "selectable")
        gguf = Path(self.folders.values["diffusion_models"][0]) / "model.gguf"
        gguf.write_bytes(b"gguf fixture")
        await asyncio.to_thread(self.index.scan, threading.Event(), {"discovered": 0})
        result = await self.query(category="UNET")
        self.assertEqual(result["items"][0]["format"], "GGUF")
        self.assertEqual(result["items"][0]["annotation"].get("useCases", []), [])

    async def test_multiple_roots_same_name_remains_two_ambiguous_locations(self):
        second = Path(self.folders.values["loras"][1]) / self.file.name
        second.write_bytes(self.file.read_bytes())
        await asyncio.to_thread(self.index.scan, threading.Event(), {"discovered": 0})
        data = await self.query()
        self.assertEqual(data["stats"]["locations"], 2)
        self.assertEqual(len({i["rootId"] for i in data["items"]}), 2)
        for item in data["items"]:
            self.assertEqual(item["loadStatus"], "ambiguous")
            self.assertIsNone(item["nodeModelName"])
        for item in data["items"]:
            await asyncio.to_thread(self.index.hash_location, item["id"], threading.Event())
        self.assertEqual((await self.query())["stats"]["uniqueContents"], 1)

    async def test_hash_file_match_is_not_primary_file(self):
        job = await self.enrich()
        self.assertEqual(job["status"], "completed", job)
        item = self.index.detail(self.item["id"])
        self.assertEqual(item["provider"]["fileId"], 30)
        self.assertEqual(item["provider"]["trainedWords"], ["Exact_Trigger (A)", "UPPER_case"])
        self.assertTrue((self.index.directory / "metadata" / self.digest / "provider.json").is_file())
        self.assertEqual(self.client.calls, ["model-versions/by-hash/" + self.digest])

    async def test_mismatched_return_hash_is_not_associated(self):
        self.client.version["files"] = [self.client.version["files"][0]]
        job = await self.enrich()
        self.assertEqual(job["code"], 409)
        self.assertIsNone(self.index.detail(self.item["id"])["provider"])

    async def test_provider_refresh_preserves_user_fields_and_revision(self):
        reply = await self.annotate({"alias": "角色A·常服", "useCases": ["character", "clothing"],
                                     "trainedWords": ["USER_Exact"], "fieldLocks": ["alias", "trainedWords"],
                                     "classificationSource": "user", "notes": "中文说明"})
        self.assertEqual(reply.status, 200)
        await self.enrich()
        item = self.index.detail(self.item["id"])
        self.assertEqual(item["annotation"]["alias"], "角色A·常服")
        self.assertEqual(item["annotation"]["trainedWords"], ["USER_Exact"])
        self.assertEqual(item["revision"], 1)
        result = await self.query(q="角色a", useCase="clothing")
        self.assertEqual(result["total"], 1)
        self.assertEqual((await self.query(useCase="style"))["total"], 0)

    async def test_annotation_revision_conflict_and_restart_undo(self):
        self.assertEqual((await self.annotate({"alias": "first"})).status, 200)
        self.assertEqual((await self.annotate({"alias": "late"})).status, 409)
        self.assertEqual((await self.annotate({"alias": "second"}, revision=1)).status, 200)
        fresh = index_module.ModelIndex(self.folders, self.directory / "user")
        restored = fresh.annotate(self.item["id"], 2, {}, undo_revision=2)
        self.assertEqual(restored["annotation"]["alias"], "first")
        self.assertEqual(restored["revision"], 3)

    async def test_unknown_source_can_be_annotated_without_network(self):
        self.assertEqual((await self.annotate({"alias": "私人模型", "baseModel": "自训",
                                              "favorite": True, "character": "角色A"})).status, 200)
        data = await self.query(source="unknown", favorite="true", q="角色A")
        self.assertEqual(data["total"], 1)
        self.assertFalse(self.client.calls)

    async def test_404_cooldown_and_no_automatic_repeat_on_browse(self):
        self.client.error = transport.CivitaiError("未匹配来源", 404)
        job = await self.enrich()
        self.assertEqual(job["status"], "unmatched")
        self.assertGreater(job["retryAt"], index_module.stamp())
        await self.query()
        await self.query()
        data = await self.enrich()
        self.assertEqual(data["errors"][0]["code"], 429)
        self.assertEqual(len(self.client.calls), 1)

    async def test_429_respects_provider_retry_after(self):
        self.client.error = transport.CivitaiError("频繁", 429, retry_after=7200)
        job = await self.enrich()
        self.assertGreater(job["retryAt"], index_module.stamp() + 7190_000)

    async def test_401_and_403_do_not_delete_previous_provider_or_annotation(self):
        await self.enrich()
        await self.annotate({"notes": "preserve"}, revision=0)
        for code in (401, 403):
            for job in self.center.jobs.values():
                job["retryAt"] = 0
            self.client.error = transport.CivitaiError("账号访问失败", code)
            job = await self.enrich(refresh=True)
            self.assertEqual(job["code"], code)
            item = self.index.detail(self.item["id"])
            self.assertEqual(item["provider"]["fileId"], 30)
            self.assertEqual(item["annotation"]["notes"], "preserve")

    async def test_cancel_joins_worker_and_keeps_file(self):
        self.client.delay = 5
        job = await self.center.enqueue(self.item["id"])
        for _ in range(100):
            if job["status"] == "matching":
                break
            await asyncio.sleep(.01)
        reply = await self.http.post("/api/mobile/model-center/task-action",
                                    json={"id": job["id"], "action": "cancel"})
        self.assertEqual(reply.status, 200)
        self.assertEqual(job["status"], "cancelled")
        self.assertNotIn(job["id"], self.center.workers)
        self.assertEqual(self.file.read_bytes(), b"fixture original")

    async def test_move_and_missing_keep_content_metadata_and_annotations(self):
        await self.enrich()
        await self.annotate({"alias": "持久角色"}, revision=0)
        moved = self.file.with_name("renamed.safetensors")
        self.file.rename(moved)
        await asyncio.to_thread(self.index.scan, threading.Event(), {"discovered": 0})
        data = await self.query()
        self.assertEqual(data["stats"]["missing"], 1)
        new_item = next(i for i in data["items"] if i["present"])
        await asyncio.to_thread(self.index.hash_location, new_item["id"], threading.Event())
        new_item = self.index.detail(new_item["id"])
        self.assertEqual(new_item["annotation"]["alias"], "持久角色")
        self.assertEqual(new_item["provider"]["fileId"], 30)
        self.assertEqual(self.index.detail(self.item["id"])["annotation"]["alias"], "持久角色")

    async def test_incremental_invalidation_does_not_reuse_old_hash(self):
        await self.enrich()
        self.file.write_bytes(b"new different content")
        await asyncio.to_thread(self.index.scan, threading.Event(), {"discovered": 0})
        item = self.index.detail(self.item["id"])
        self.assertIsNone(item["sha256"])
        self.assertIsNone(item["provider"])
        digest = await asyncio.to_thread(self.index.hash_location, item["id"], threading.Event())
        self.assertNotEqual(digest, self.digest)

    async def test_scan_pause_keeps_previous_complete_results(self):
        reply = await self.http.post("/api/mobile/model-center/scan", json={"action": "start"})
        self.assertEqual(reply.status, 200)
        reply = await self.http.post("/api/mobile/model-center/scan", json={"action": "pause"})
        self.assertEqual(reply.status, 200)
        self.assertEqual(self.center.scan_state["status"], "paused")
        self.assertEqual((await self.query())["total"], 1)

    async def test_download_ingest_uses_verified_hash_without_rehash(self):
        from types import SimpleNamespace
        job = SimpleNamespace(root=str(self.file.parent), sha256=self.digest, download_id="fixture")
        location_id = await self.center.download_completed(job, self.file, self.client.version)
        self.assertEqual(self.index.detail(location_id)["provider"]["fileId"], 30)
        self.assertFalse(self.client.calls)

    async def test_installed_status_is_file_and_hash_scoped_and_rechecks_stat(self):
        await self.enrich()
        self.assertEqual(self.index.installation(10, 20, 30)["status"], "exact-file")
        self.assertEqual(self.index.installation(10, 21, 31)["status"], "other-version")
        self.assertEqual(self.index.installation(11, 20, 30)["status"], "not-indexed")
        self.file.write_bytes(b"replaced")
        self.assertEqual(self.index.installation(10, 20, 30)["status"], "not-indexed")

    async def test_cancel_before_worker_runs_can_resume_without_zombie_worker(self):
        self.client.delay = 1
        job = await self.center.enqueue(self.item["id"])
        reply = await self.http.post("/api/mobile/model-center/task-action",
                                    json={"id": job["id"], "action": "pause"})
        self.assertEqual(reply.status, 200)
        self.assertEqual(job["status"], "paused")
        self.assertNotIn(job["id"], self.center.workers)
        reply = await self.http.post("/api/mobile/model-center/task-action",
                                    json={"id": job["id"], "action": "resume"})
        self.assertEqual(reply.status, 200)

    async def test_duplicate_enrichment_uses_active_latest_job(self):
        self.client.delay = 1
        first = await self.center.enqueue(self.item["id"])
        again = await self.center.enqueue(self.item["id"])
        self.assertEqual(first["id"], again["id"])
        self.assertEqual(len(self.center.jobs), 1)

    async def test_pause_during_provider_lookup_does_not_advance_to_network(self):
        entered, release = threading.Event(), threading.Event()
        original = self.index.provider
        def delayed(digest):
            entered.set()
            release.wait(5)
            return original(digest)
        with patch.object(self.index, "provider", delayed):
            job = await self.center.enqueue(self.item["id"])
            for _ in range(200):
                if entered.is_set():
                    break
                await asyncio.sleep(.01)
            self.assertTrue(entered.is_set())
            pending = asyncio.create_task(self.http.post("/api/mobile/model-center/task-action",
                json={"id": job["id"], "action": "pause"}))
            for _ in range(200):
                stop = self.center.stops.get(job["id"])
                if stop and stop.is_set():
                    break
                await asyncio.sleep(.01)
            release.set()
            reply = await pending
        self.assertEqual(reply.status, 200)
        self.assertEqual(job["status"], "paused")
        self.assertFalse(self.client.calls)
        self.assertEqual(self.index.jobs()[0]["status"], "paused")

    async def test_paused_job_and_refresh_intent_survive_restart(self):
        await self.enrich()
        self.client.delay = 1
        job = await self.center.enqueue(self.item["id"], force=True)
        await self.center.shutdown(None)
        fresh = center_module.ModelCenterService(self.folders, self.directory / "user", self.client)
        await fresh.initialize()
        self.assertEqual(fresh.jobs[job["id"]]["status"], "paused")
        self.assertTrue(fresh.jobs[job["id"]]["refresh"])
        await fresh.shutdown(None)

    async def test_unreadable_root_preserves_records_without_claiming_complete(self):
        def broken_walk(path, onerror, **kwargs):
            onerror(PermissionError("fixture"))
            return iter([])
        with patch.object(index_module.os, "walk", broken_walk):
            await asyncio.to_thread(self.index.scan, threading.Event(), {"discovered": 0})
        item = self.index.detail(self.item["id"])
        self.assertTrue(item["present"])
        self.assertEqual(item["loadStatus"], "unconfirmed")
        self.assertFalse((await self.query())["scanComplete"])

    async def test_registered_output_directory_not_created_yet_does_not_block_completion(self):
        optional = self.directory / "output" / "loras"
        self.folders.values["loras"].append(str(optional))
        self.index.scan(threading.Event(), {"discovered": 0})
        data = await self.query()
        root = next(r for r in self.index.info()["roots"] if r["path"] == str(optional.resolve()))
        print(f"\nMODEL_INDEX_OPTIONAL status={root['status']} complete={data['scanComplete']}")
        self.assertTrue(data["scanComplete"])
        self.assertEqual("absent", root["status"])
        self.assertEqual(1, data["stats"]["locations"])
        # A later save creates the directory: the next scan discovers its model.
        optional.mkdir(parents=True)
        (optional / "saved.safetensors").write_bytes(b"saved fixture")
        self.index.scan(threading.Event(), {"discovered": 0})
        self.assertEqual(2, (await self.query(category="LORA"))["total"])
        fresh = index_module.ModelIndex(self.folders, self.directory / "user")
        self.assertTrue(fresh.info()["scanComplete"])

    async def test_previously_seen_root_disappearing_is_not_treated_as_unused(self):
        self.file.unlink()
        self.file.parent.rmdir()
        self.index.scan(threading.Event(), {"discovered": 0})
        data = await self.query()
        self.assertFalse(data["scanComplete"])
        item = self.index.detail(self.item["id"])
        self.assertTrue(item["present"])
        self.assertEqual("unconfirmed", item["loadStatus"])
        root = next(r for r in self.index.info()["roots"] if r["id"] == item["rootId"])
        self.assertEqual("unreadable", root["status"])

    async def test_empty_root_seen_before_restart_still_counts_if_it_disappears(self):
        empty = Path(self.folders.values["loras"][1])
        self.assertEqual([], list(empty.iterdir()))
        empty.rmdir()
        fresh = index_module.ModelIndex(self.folders, self.directory / "user")
        fresh.scan(threading.Event(), {"discovered": 0})
        self.assertFalse(fresh.info()["scanComplete"])
        root = next(r for r in fresh.info()["roots"] if r["path"] == str(empty))
        self.assertEqual("unreadable", root["status"])

    async def test_legacy_ready_empty_root_remains_known_across_multiple_failed_scans(self):
        # Upgrade from 2.2.1, which has roots.status but no seenRoot marker.
        with self.index.db() as db:
            db.execute("DELETE FROM info WHERE key LIKE 'seenRoot:%'")
        empty = Path(self.folders.values["loras"][1])
        empty.rmdir()
        for _ in range(2):
            fresh = index_module.ModelIndex(self.folders, self.directory / "user")
            fresh.scan(threading.Event(), {"discovered": 0})
            self.assertFalse(fresh.info()["scanComplete"])

    async def test_permission_failure_on_never_seen_root_is_not_treated_as_absent(self):
        protected = self.directory / "protected"
        self.folders.values["loras"].append(str(protected))
        real_stat = Path.stat
        def denied(path, *args, **kwargs):
            if path == protected:
                raise PermissionError("fixture root permission")
            return real_stat(path, *args, **kwargs)
        with patch.object(Path, "stat", denied):
            self.index.scan(threading.Event(), {"discovered": 0})
        self.assertFalse(self.index.info()["scanComplete"])
        root = next(r for r in self.index.info()["roots"] if r["path"] == str(protected))
        self.assertEqual("unreadable", root["status"])

    async def test_lora_and_checkpoint_filters_keep_distinct_paginated_results(self):
        checkpoints = self.directory / "checkpoints"
        checkpoints.mkdir()
        self.folders.values["checkpoints"] = [str(checkpoints)]
        for number in range(2):
            (checkpoints / f"checkpoint-{number}.safetensors").write_bytes(b"checkpoint")
        for number in range(47):
            self.file.with_name(f"lora-{number}.safetensors").write_bytes(b"lora")
        self.index.scan(threading.Event(), {"discovered": 0})
        checkpoint = await self.query(category="CHECKPOINT")
        first = await self.query(category="LORA", page=1, pageSize=40)
        second = await self.query(category="LORA", page=2, pageSize=40)
        self.assertEqual(2, checkpoint["total"])
        self.assertTrue(all(i["category"] == "CHECKPOINT" for i in checkpoint["items"]))
        self.assertEqual(48, first["total"])
        self.assertEqual(40, len(first["items"]))
        self.assertTrue(first["hasMore"])
        self.assertEqual(8, len(second["items"]))
        self.assertFalse(second["hasMore"])
        self.assertTrue(all(i["category"] == "LORA" for i in first["items"] + second["items"]))

    async def test_cancelling_hash_before_first_chunk_publishes_no_digest(self):
        stop = threading.Event()
        stop.set()
        self.assertIsNone(await asyncio.to_thread(self.index.hash_location, self.item["id"], stop))
        self.assertIsNone(self.index.detail(self.item["id"])["sha256"])

    async def test_file_changed_during_hash_discards_the_result(self):
        real_open = Path.open
        mutated = False
        target = self.file
        class Stream:
            def __init__(self, inner):
                self.inner = inner
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return self.inner.__exit__(*args)
            def read(self, size):
                nonlocal mutated
                chunk = self.inner.read(size)
                if chunk and not mutated:
                    mutated = True
                    with real_open(target, "ab") as output:
                        output.write(b"-changed")
                return chunk
        def intercept(path, mode="r", *args, **kwargs):
            opened = real_open(path, mode, *args, **kwargs)
            return Stream(opened) if path == target and mode == "rb" else opened
        with patch.object(Path, "open", intercept):
            with self.assertRaises(transport.CivitaiError) as result:
                await asyncio.to_thread(self.index.hash_location, self.item["id"], threading.Event())
        self.assertEqual(result.exception.status, 409)
        self.assertIsNone(self.index.detail(self.item["id"])["sha256"])

    async def test_same_hash_different_location_annotations_are_not_discarded(self):
        await self.annotate({"notes": "first location"})
        duplicate = Path(self.folders.values["loras"][1]) / "duplicate.safetensors"
        duplicate.write_bytes(self.file.read_bytes())
        await asyncio.to_thread(self.index.scan, threading.Event(), {"discovered": 0})
        new_item = next(i for i in (await self.query())["items"] if i["name"] == duplicate.name)
        self.index.annotate(new_item["id"], 0, {"notes": "second location"})
        self.index.hash_location(self.item["id"], threading.Event())
        self.index.hash_location(new_item["id"], threading.Event())
        self.assertEqual(self.index.detail(self.item["id"])["annotation"]["notes"], "first location")
        self.assertEqual(self.index.detail(new_item["id"])["annotation"]["notes"], "second location")

    async def test_page_parameters_and_bad_annotation_do_not_mutate(self):
        self.assertEqual((await self.http.get("/api/mobile/model-center/models?pageSize=10000")).status, 400)
        self.assertEqual((await self.annotate({"useCases": ["guessed-format"]})).status, 400)
        self.assertEqual((await self.annotate({"preferredStrength": 999})).status, 400)
        self.assertEqual(self.index.detail(self.item["id"])["revision"], 0)

    async def test_10000_rows_unicode_search_pagination_stays_off_event_loop(self):
        def seed(start, end):
            with self.index.db() as db:
                root_id = self.item["rootId"]
                db.executemany("INSERT INTO locations VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    [(f"fixture-{i}", root_id, "LORA", f"folder/{i}.gguf", f"词库-{i}.gguf",
                      "GGUF", i+1, i+1, None, 1, "fixture", None, "unresolved", None) for i in range(start, end)])
        await asyncio.to_thread(seed, 0, 1000)
        first_start = time.monotonic()
        first = await self.query(q="词库", page="2", pageSize="40")
        print(f"\nMODEL_CENTER_BENCH rows=1000 page=40 elapsed={time.monotonic()-first_start:.3f}s")
        self.assertEqual(first["total"], 1000)
        await asyncio.to_thread(seed, 1000, 10000)
        start = time.monotonic()
        data = await self.query(q="词库", page="2", pageSize="40")
        elapsed = time.monotonic()-start
        self.assertEqual(data["total"], 10000)
        self.assertEqual(len(data["items"]), 40)
        self.assertTrue(data["hasMore"])
        print(f"\nMODEL_CENTER_BENCH rows=10000 page=40 elapsed={elapsed:.3f}s")
        self.assertLess(elapsed, 15)


if __name__ == "__main__":
    unittest.main()
