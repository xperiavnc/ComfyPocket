"""Native picker regressions. No real model weights or live service writes."""
import importlib.util
import hashlib
from pathlib import Path
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

PLUGIN = Path(__file__).resolve().parents[1]


class PickerFolders:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.folder_names_and_paths = {key: ([str(self.directory / key)], set()) for key in (
            "diffusion_models", "unet", "unet_gguf", "diffusion_models_gguf", "loras", "checkpoints")}
        self.options = {key: [] for key in self.folder_names_and_paths}
        self.filename_list_cache = {}
        self.cache_helper = types.SimpleNamespace(cache={})
        for paths, _ in self.folder_names_and_paths.values():
            Path(paths[0]).mkdir()

    def add(self, key, name):
        self.options[key].append(name)
        file = self.file(key, name)
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(b"model-picker-fixture")

    def file(self, key, name):
        return self.directory / key / name.replace("\\", "/")

    def get_filename_list(self, key):
        if key not in self.options:
            raise KeyError(key)
        if key not in self.cache_helper.cache:
            if key not in self.filename_list_cache:
                self.filename_list_cache[key] = list(self.options[key])
            self.cache_helper.cache[key] = list(self.filename_list_cache[key])
        return self.cache_helper.cache[key]

    def get_full_path(self, key, name):
        return str(self.file(key, name)) if name in self.options.get(key, []) else None

    def get_folder_paths(self, key):
        return self.folder_names_and_paths.get(key, ([], set()))[0]


class ModelPickerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folders = PickerFolders(self.temp.name)
        name = "_bridge_picker_test"
        spec = importlib.util.spec_from_file_location(name, PLUGIN / "__init__.py",
                                                     submodule_search_locations=[str(PLUGIN)])
        self.plugin = importlib.util.module_from_spec(spec)
        server = types.ModuleType("server")
        server.PromptServer = None
        with patch.dict(sys.modules, {"server": server, "folder_paths": self.folders, name: self.plugin}):
            spec.loader.exec_module(self.plugin)
            index_type = sys.modules[name + ".model_index"].ModelIndex
        self.index = index_type(self.folders, Path(self.temp.name) / "data")
        self.plugin._civitai_service = types.SimpleNamespace(
            model_center=types.SimpleNamespace(index=self.index))
        app = web.Application()
        app.router.add_get("/api/mobile/resource/list", self.plugin.handle_resource_list)
        app.router.add_post("/api/mobile/resource/list", self.plugin.handle_resource_list)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.temp.cleanup()

    def listing(self, kind="UNET", keyword="", page=1, size=24, base=None, refresh=False):
        return self.plugin._list_native_models(kind, keyword, page, size, base, refresh, self.index)

    def names(self, result):
        return [item["nodeModelName"] for item in result["records"]]

    def scan(self):
        self.assertTrue(self.index.scan(threading.Event(), {"discovered": 0}))

    def metadata(self, key="diffusion_models", name="plain.safetensors"):
        self.folders.add(key, name)
        self.scan()
        item = next(item for item in self.index.query({})["items"] if item["relativePath"] == name.replace("\\", "/"))
        file = self.folders.file(key, name)
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        self.index.attach_hash(item["id"], digest, file.stat())
        self.index.save_provider(digest, {
            "id": 12, "modelId": 11, "name": "v1", "model": {"name": "来源模型"},
            "baseModel": "Anima", "trainedWords": ["provider_trigger"],
            "description": "<p>同一份模型简介</p>",
            "files": [{"id": 13, "hashes": {"SHA256": digest}}]
        })
        return self.index.detail(item["id"])

    async def test_modern_and_legacy_unet_categories_are_merged(self):
        self.folders.add("diffusion_models", "Anima\\modern.safetensors")
        self.folders.add("unet", "Legacy\\legacy.safetensors")
        self.assertEqual(self.names(self.listing()),
                         ["Anima\\modern.safetensors", "Legacy\\legacy.safetensors"])

    async def test_gguf_uses_format_and_all_registered_gguf_categories(self):
        self.folders.add("diffusion_models", "ordinary.safetensors")
        self.folders.add("unet", "also-ordinary.safetensors")
        self.folders.add("unet_gguf", "Flux\\model-Q4.gguf")
        self.folders.add("diffusion_models_gguf", "Other/model-Q8.GGUF")
        self.folders.add("diffusion_models", "shared/model-Q6.gguf")
        result = self.listing("GGUF")
        self.assertEqual(result["total"], 3)
        self.assertTrue(all(n.lower().endswith(".gguf") for n in self.names(result)))
        self.assertEqual(self.listing("UNET")["total"], 2)

    async def test_empty_gguf_does_not_show_ordinary_unet(self):
        self.folders.add("unet", "Anima/base.safetensors")
        self.assertEqual(self.listing("GGUF")["total"], 0)

    async def test_shared_alias_options_are_deduplicated(self):
        self.folders.add("diffusion_models", "Anima\\same.safetensors")
        self.folders.add("unet", "Anima/same.safetensors")
        self.assertEqual(self.listing()["total"], 1)
        self.assertEqual(self.names(self.listing()), ["Anima\\same.safetensors"])

    async def test_refresh_discovers_new_files_and_removes_stale_options(self):
        self.folders.add("diffusion_models", "old.safetensors")
        self.assertEqual(self.listing()["total"], 1)
        self.folders.file("diffusion_models", "old.safetensors").unlink()
        self.folders.options["diffusion_models"].remove("old.safetensors")
        self.folders.add("diffusion_models", "new.safetensors")
        self.assertEqual(self.listing()["total"], 0)
        self.assertEqual(self.names(self.listing(refresh=True)), ["new.safetensors"])

    async def test_refresh_is_targeted_and_only_for_first_page(self):
        self.folders.add("diffusion_models", "first.safetensors")
        self.folders.add("loras", "lora.safetensors")
        self.listing("LORA")
        untouched = self.folders.filename_list_cache["loras"]
        self.listing()
        self.folders.add("diffusion_models", "second.safetensors")
        self.assertEqual(self.listing(page=2, size=1, refresh=True)["total"], 1)
        self.assertEqual(self.listing(refresh=True)["total"], 2)
        self.assertIs(untouched, self.folders.filename_list_cache["loras"])

    async def test_all_51_loras_are_reachable_without_duplicates(self):
        for number in range(51):
            self.folders.add("loras", f"nested/model-{number:02}.safetensors")
        results = [self.listing("LORA", page=number) for number in (1, 2, 3)]
        self.assertEqual([len(p["records"]) for p in results], [24, 24, 3])
        self.assertEqual([p["hasNext"] for p in results], [True, True, False])
        self.assertEqual(len({name for p in results for name in self.names(p)}), 51)

    async def test_filters_are_applied_before_pagination(self):
        for number in range(30):
            self.folders.add("diffusion_models", f"Anima/model-{number:02}.safetensors")
        self.folders.add("diffusion_models", "Flux/other.safetensors")
        result = self.listing(keyword="model", page=2, base="Anima")
        self.assertEqual(result["total"], 30)
        self.assertEqual(len(result["records"]), 6)

    async def test_exact_windows_option_is_preserved_but_title_is_basename(self):
        self.folders.add("diffusion_models", "Anima\\nested\\My Model.safetensors")
        record = self.listing()["records"][0]
        self.assertEqual(record["resourceName"], "My Model")
        self.assertEqual(record["nodeModelName"], "Anima\\nested\\My Model.safetensors")

    async def test_optional_missing_keys_do_not_hide_other_roots(self):
        self.folders.add("unet", "legacy.safetensors")
        del self.folders.folder_names_and_paths["diffusion_models"]
        self.assertEqual(self.names(self.listing()), ["legacy.safetensors"])

    async def test_http_refresh_get_and_post_bypass_both_comfy_caches(self):
        self.folders.add("diffusion_models", "first.safetensors")
        for method in ("GET", "POST"):
            self.listing()
            name = method.lower() + ".safetensors"
            self.folders.add("diffusion_models", name)
            values = {"resourceType": "UNET", "refresh": "true"}
            if method == "GET":
                response = await self.client.get("/api/mobile/resource/list", params=values)
            else:
                response = await self.client.post("/api/mobile/resource/list", json=values)
            result = await response.json()
            self.assertIn(name, self.names(result["data"]))
            self.assertEqual(response.headers["Cache-Control"], "no-store")

    async def test_directory_errors_return_retryable_failure_not_empty_success(self):
        with self.assertLogs("ComfyUI-Mobile-Bridge", level="ERROR"), \
                patch.object(self.folders, "get_filename_list", side_effect=OSError("offline folder")):
            response = await self.client.get("/api/mobile/resource/list?resourceType=UNET")
            self.assertEqual(response.status, 502)
            self.assertIsNone((await response.json())["data"])

    async def test_directory_scan_runs_off_event_loop(self):
        loop_thread = threading.get_ident()
        threads = []
        scan = self.plugin._list_native_models
        def observed(*args):
            threads.append(threading.get_ident())
            return scan(*args)
        with patch.object(self.plugin, "_list_native_models", side_effect=observed):
            response = await self.client.get("/api/mobile/resource/list?resourceType=UNET")
            self.assertEqual(response.status, 200)
        self.assertEqual(len(threads), 1)
        self.assertNotEqual(threads[0], loop_thread)

    async def test_unknown_type_is_explicit_error(self):
        response = await self.client.get("/api/mobile/resource/list?resourceType=UNKNOWN")
        self.assertEqual(response.status, 400)
        self.assertEqual((await response.json())["code"], 400)

    async def test_shared_model_center_annotation_and_provider_are_in_picker_response(self):
        model = self.metadata()
        annotation = {"alias": "中文角色名称", "aliases": ["其他中文名"], "tags": ["长发"],
                      "useCases": ["character"], "notes": "服装使用心得", "trainedWords": ["manual_trigger"]}
        updated = self.index.annotate(model["id"], 0, annotation)
        response = await self.client.get("/api/mobile/resource/list?resourceType=UNET")
        record = (await response.json())["data"]["records"][0]
        self.assertEqual(record["localModel"], updated)
        self.assertEqual(record["resourceName"], "plain")
        self.assertEqual(record["nodeModelName"], "plain.safetensors")
        self.assertEqual(record["versions"][0]["triggerWords"], "manual_trigger")
        self.assertEqual(record["versions"][0]["baseModel"], "Anima")
        self.assertEqual(record["localModel"]["provider"]["description"], "<p>同一份模型简介</p>")
        for keyword in ("中文角色", "其他中文", "长发", "服装使用", "来源模型", "manual_trigger"):
            self.assertEqual(self.listing(keyword=keyword)["total"], 1, keyword)
        self.assertEqual(self.listing(base="Anima")["total"], 1)

    async def test_annotation_updates_are_immediately_shared_and_empty_trigger_lock_is_respected(self):
        model = self.metadata()
        self.index.annotate(model["id"], 0, {"alias": "旧中文名"})
        self.assertEqual(self.listing()["records"][0]["localModel"]["annotation"]["alias"], "旧中文名")
        updated = self.index.annotate(model["id"], 1,
                                      {"alias": "新中文名", "trainedWords": [], "fieldLocks": ["trainedWords"]})
        record = self.listing()["records"][0]
        self.assertEqual(record["localModel"], updated)
        self.assertEqual(record["versions"][0]["triggerWords"], "")
        self.assertEqual(self.listing(keyword="旧中文")["total"], 0)
        self.assertEqual(self.listing(keyword="新中文")["total"], 1)

    async def test_same_basename_does_not_leak_annotations_between_directories(self):
        self.folders.add("diffusion_models", "B/same.safetensors")
        self.folders.file("diffusion_models", "B/same.safetensors").write_bytes(b"different-content")
        model = self.metadata(name="A/same.safetensors")
        self.index.annotate(model["id"], 0, {"alias": "只有A的中文标注"})
        rows = {r["nodeModelName"]: r for r in self.listing()["records"]}
        self.assertEqual(rows["A/same.safetensors"]["localModel"]["annotation"]["alias"], "只有A的中文标注")
        self.assertEqual(rows["B/same.safetensors"]["localModel"]["annotation"], {})

    async def test_new_or_replaced_file_remains_selectable_without_stale_index_metadata(self):
        model = self.metadata()
        self.index.annotate(model["id"], 0, {"alias": "旧文件中文名称"})
        self.folders.file("diffusion_models", "plain.safetensors").write_bytes(b"replacement-bytes-different-size")
        self.folders.add("diffusion_models", "new.safetensors")
        rows = {r["nodeModelName"]: r for r in self.listing(refresh=True)["records"]}
        self.assertEqual(len(rows), 2)
        self.assertIsNone(rows["plain.safetensors"]["localModel"])
        self.assertIsNone(rows["new.safetensors"]["localModel"])


if __name__ == "__main__":
    unittest.main()
