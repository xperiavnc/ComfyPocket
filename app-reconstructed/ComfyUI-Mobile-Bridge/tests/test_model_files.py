"""Real temporary model files + aiohttp routes; never modifies a live model library."""
import asyncio
import errno
from pathlib import Path
import threading
import unittest
from unittest.mock import patch

import test_model_center as fixtures
center_module = fixtures.center_module
files_module = __import__(center_module.__package__ + ".model_files", fromlist=["ModelFiles"])


class ModelFileTests(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = fixtures.ModelCenterTests.asyncSetUp
    asyncTearDown = fixtures.ModelCenterTests.asyncTearDown
    query = fixtures.ModelCenterTests.query
    enrich = fixtures.ModelCenterTests.enrich
    annotate = fixtures.ModelCenterTests.annotate

    def request(self, action, **extra):
        row = self.index.detail(self.item["id"])
        return {"action": action, "expectedPath": row["relativePath"],
                "expectedSize": row["size"], "expectedMtimeNs": row["mtimeNs"], **extra}

    async def operate(self, action, expected=200, **extra):
        reply = await self.http.post("/api/mobile/model-center/models/" + self.item["id"] + "/file-action",
                                     json=self.request(action, **extra))
        data = await reply.json()
        self.assertEqual(expected, reply.status, data)
        return data["data"] if expected == 200 else data

    async def test_rename_changes_actual_file_and_sidecars_preserves_metadata_and_node_option(self):
        await self.enrich()
        await self.annotate({"alias": "自定义", "notes": "保留人工内容"})
        self.file.with_suffix(".preview.png").write_bytes(b"\x89PNG\r\n\x1a\ncover")
        Path(str(self.file) + ".civitai.info").write_text("metadata")
        result = await self.operate("rename", name="改名.safetensors")
        renamed = self.file.with_name("改名.safetensors")
        self.assertFalse(self.file.exists())
        self.assertEqual(b"fixture original", renamed.read_bytes())
        self.assertTrue(renamed.with_suffix(".preview.png").is_file())
        self.assertTrue(Path(str(renamed) + ".civitai.info").is_file())
        item = result["item"]
        self.assertEqual(self.digest, item["sha256"])
        self.assertEqual("自定义", item["annotation"]["alias"])
        self.assertEqual(30, item["provider"]["fileId"])
        self.assertEqual("改名.safetensors", item["nodeModelName"])
        self.assertEqual(1, (await self.query())["total"])
        self.assertNotEqual(self.item["id"], item["id"])
        self.assertEqual(2, result["relatedFiles"])
        await self.http.post("/api/mobile/model-center/models/" + item["id"] + "/annotation",
                             json={"expectedRevision": 1, "annotation": {}, "undoRevision": 1})
        self.assertEqual({}, self.index.detail(item["id"])["annotation"])

    async def test_move_creates_server_subfolders_and_migrates_unhashed_location_annotation(self):
        await self.annotate({"alias": "指纹尚未计算的标注"})
        target_root = next(r for r in self.index.registered_roots()
                           if r["category"] == "LORA" and r["id"] != self.item["rootId"])
        result = await self.operate("move", rootId=target_root["id"], directory="角色/新文件夹")
        destination = Path(target_root["path"]) / "角色/新文件夹/original.safetensors"
        self.assertEqual(b"fixture original", destination.read_bytes())
        self.assertFalse(self.file.exists())
        self.assertEqual("指纹尚未计算的标注", result["item"]["annotation"]["alias"])
        self.assertEqual("角色/新文件夹/original.safetensors", result["item"]["relativePath"])
        self.assertEqual(result["item"]["relativePath"], result["item"]["nodeModelName"])

    async def test_delete_removes_real_weights_and_companions_not_other_models(self):
        await self.enrich()
        self.file.with_suffix(".txt").write_text("trigger")
        other = self.file.with_name("other.safetensors")
        other.write_bytes(b"keep other")
        result = await self.operate("delete", confirmName=self.file.name)
        self.assertIsNone(result["item"])
        self.assertFalse(self.file.exists())
        self.assertFalse(self.file.with_suffix(".txt").exists())
        self.assertEqual(b"keep other", other.read_bytes())
        self.assertEqual(0, (await self.query())["total"])
        self.assertIsNotNone(self.index.provider(self.digest))
        self.assertFalse(list(self.file.parent.glob(".mobile-delete-*")))
        self.assertTrue(all(job["status"] == "cancelled" for job in self.center.jobs.values()))

    async def test_same_stem_other_format_keeps_shared_cover(self):
        self.file.with_suffix(".ckpt").write_bytes(b"another format")
        cover = self.file.with_suffix(".png")
        cover.write_bytes(b"\x89PNG\r\n\x1a\nshared")
        result = await self.operate("delete", confirmName=self.file.name)
        self.assertTrue(cover.exists())
        self.assertTrue(self.file.with_suffix(".ckpt").exists())
        self.assertTrue(result["warnings"])

    async def test_conflicting_target_preserves_all_original_files(self):
        occupied = self.file.with_name("taken.safetensors")
        occupied.write_bytes(b"do not overwrite")
        await self.operate("rename", expected=409, name=occupied.name)
        self.assertEqual(b"fixture original", self.file.read_bytes())
        self.assertEqual(b"do not overwrite", occupied.read_bytes())
        self.assertTrue(self.index.detail(self.item["id"])["present"])

    async def test_companion_conflict_does_not_move_weight(self):
        self.file.with_suffix(".png").write_bytes(b"\x89PNG\r\n\x1a\ncover")
        conflict = self.file.with_name("new.png")
        conflict.write_bytes(b"do not overwrite")
        await self.operate("rename", expected=409, name="new.safetensors")
        self.assertTrue(self.file.exists())
        self.assertFalse(self.file.with_name("new.safetensors").exists())
        self.assertEqual(b"do not overwrite", conflict.read_bytes())

    async def test_stale_file_snapshot_is_not_renamed(self):
        request = self.request("rename", name="new.safetensors")
        self.file.write_bytes(b"changed on server")
        reply = await self.http.post("/api/mobile/model-center/models/" + self.item["id"] + "/file-action", json=request)
        self.assertEqual(409, reply.status)
        self.assertTrue(self.file.exists())
        self.assertFalse(self.file.with_name("new.safetensors").exists())

    async def test_database_failure_rolls_back_move_and_new_folder(self):
        files = files_module.ModelFiles(self.index)
        request = self.request("move", rootId=self.item["rootId"], directory="created/child")
        with patch.object(files, "_publish", side_effect=OSError("fixture DB failure")):
            with self.assertRaises(OSError):
                files.mutate(self.item["id"], request)
        self.assertEqual(b"fixture original", self.file.read_bytes())
        self.assertFalse(self.file.parent.joinpath("created").exists())
        self.assertEqual(1, (await self.query())["total"])

    async def test_second_file_failure_rolls_back_weight_and_sidecar(self):
        self.file.with_suffix(".txt").write_text("exact words")
        original = files_module.move_no_replace
        calls = []
        def fail_second(source, target):
            calls.append(str(source))
            if len(calls) == 2:
                raise PermissionError("fixture companion locked")
            return original(source, target)
        with patch.object(files_module, "move_no_replace", side_effect=fail_second):
            await self.operate("rename", expected=502, name="new.safetensors")
        self.assertTrue(self.file.exists())
        self.assertEqual("exact words", self.file.with_suffix(".txt").read_text())
        self.assertFalse(self.file.with_name("new.safetensors").exists())

    async def test_directories_browse_server_children_and_new_folder_destination(self):
        (self.file.parent / "角色").mkdir()
        reply = await self.http.get("/api/mobile/model-center/models/" + self.item["id"] + "/directories",
                                    params={"rootId": self.item["rootId"], "path": ""})
        self.assertEqual(200, reply.status)
        data = (await reply.json())["data"]
        self.assertEqual(["角色"], data["directories"])
        self.assertEqual(self.index.server_id, data["serverId"])
        await self.operate("move", rootId=self.item["rootId"], directory="角色/new")
        self.assertTrue((self.file.parent / "角色/new" / self.file.name).is_file())

    async def test_registered_category_and_extension_are_preserved(self):
        root = next(r for r in self.index.registered_roots() if r["category"] == "UNET")
        await self.operate("move", expected=409, rootId=root["id"], directory="")
        await self.operate("rename", expected=400, name="new.ckpt")
        self.assertTrue(self.file.is_file())

    async def test_cached_comfy_options_are_invalidated(self):
        self.folders.filename_list_cache = {"loras": ("stale option",), "unrelated": ("keep",)}
        await self.operate("rename", name="new.safetensors")
        self.assertNotIn("loras", self.folders.filename_list_cache)
        self.assertIn("unrelated", self.folders.filename_list_cache)

    async def test_cross_volume_fallback_preserves_bytes_and_mtime(self):
        before = self.file.stat()
        target = self.file.with_name("cross-volume.safetensors")
        original = files_module.os.rename if os_name_is_nt() else files_module.os.link
        count = 0
        def cross_once(*args, **kwargs):
            nonlocal count
            count += 1
            if count == 1:
                raise OSError(errno.EXDEV, "fixture separate volumes")
            return original(*args, **kwargs)
        with patch.object(files_module.os, "rename" if os_name_is_nt() else "link", side_effect=cross_once):
            files_module.move_no_replace(self.file, target)
        self.assertEqual(b"fixture original", target.read_bytes())
        self.assertEqual(before.st_mtime_ns, target.stat().st_mtime_ns)
        self.assertFalse(self.file.exists())
        self.assertFalse(list(target.parent.glob(".mobile-move-*")))

    async def test_file_management_capability_is_advertised(self):
        self.assertIn("model-file-management", self.index.info()["capabilities"])


def os_name_is_nt():
    return files_module.os.name == "nt"


if __name__ == "__main__":
    unittest.main()
