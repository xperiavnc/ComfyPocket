"""Explicit server-side model file operations, separate from annotation editing."""
from __future__ import annotations

from collections import Counter
import errno
import json
import os
from pathlib import Path
import shutil
import stat
import uuid

from .civitai_client import CivitaiError
from .credentials import atomic_json
from .model_index import FORMATS, PREVIEW_SUFFIXES, identity, stamp

SIDECARS = PREVIEW_SUFFIXES + (
    ".json", ".txt", ".yaml", ".yml", ".info", ".civitai.info",
    ".metadata.json", ".cm-info.json", ".html",
)


def component(value):
    if (not isinstance(value, str) or not value or len(value) > 240 or
            value in (".", "..") or value[-1] in " ." or
            any(ord(c) < 32 or c in '<>:"/\\|?*' for c in value) or
            value.split(".")[0].upper() in
            {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)),
             *(f"LPT{i}" for i in range(1, 10))}):
        raise CivitaiError("名称应为普通文件名，不含路径或特殊字符")
    return value


def relative_directory(value):
    if not isinstance(value, str) or len(value) > 2048:
        raise CivitaiError("文件夹路径无效")
    value = value.replace("\\", "/")
    if value == "":
        return ""
    return "/".join(component(part) for part in value.split("/"))


def contained(root, relative):
    """Check the original path too: resolving first would hide a symlink."""
    root = Path(root).resolve()
    candidate = root / relative
    current = candidate
    while current != root:
        if current.is_symlink():
            raise CivitaiError("目录包含链接，请选择实际模型目录", 409)
        if current.parent == current:
            raise CivitaiError("目录已变化，请重新选择", 409)
        current = current.parent
    if not candidate.resolve().is_relative_to(root):
        raise CivitaiError("目录已变化，请重新选择", 409)
    return candidate


def move_no_replace(source, target):
    """Same-volume rename/link; cross-volume copy is published without overwrite."""
    source, target = Path(source), Path(target)
    try:
        if os.name == "nt":
            os.rename(source, target)  # Windows rename never overwrites an existing file.
        else:
            os.link(source, target)
            try:
                source.unlink()
            except BaseException:
                target.unlink()
                raise
        return
    except OSError as error:
        if error.errno != errno.EXDEV and getattr(error, "winerror", None) != 17:
            raise
    temporary = target.with_name(".mobile-move-" + uuid.uuid4().hex + ".part")
    published = False
    try:
        shutil.copy2(source, temporary)
        with temporary.open("r+b") as stream:
            os.fsync(stream.fileno())
        if os.name == "nt":
            os.rename(temporary, target)
        else:
            os.link(temporary, target)
            temporary.unlink()
        published = True
        source.unlink()
    except BaseException:
        if published:
            target.unlink()
        raise
    finally:
        temporary.unlink(missing_ok=True)


class ModelFiles:
    def __init__(self, index):
        self.index = index

    def _root(self, root_id, category):
        root = next((r for r in self.index.registered_roots()
                     if r["id"] == root_id and r["category"] == category), None)
        if not root:
            raise CivitaiError("请选择同类型的已注册模型目录", 409)
        if not Path(root["path"]).is_dir():
            raise CivitaiError("目标模型根目录尚未创建或当前离线", 409)
        return root

    def directories(self, location_id, root_id, directory):
        with self.index.lock:
            item = self.index.detail(location_id)
            root = self._root(root_id or item["rootId"], item["category"])
            relative = relative_directory(directory)
            folder = contained(root["path"], relative)
            if not folder.is_dir():
                raise CivitaiError("此服务器文件夹尚不存在，可在移动时创建", 404)
            names = sorted((p.name for p in folder.iterdir()
                            if p.is_dir() and not p.is_symlink() and not p.name.startswith(".mobile-")),
                           key=str.casefold)
            return {"serverId": self.index.server_id, "rootId": root["id"], "path": relative,
                    "directories": names[:500], "truncated": len(names) > 500}

    def _companions(self, model):
        pairs, warnings = [], []
        shared = any(p != model and p.suffix.lower() in FORMATS and
                     p.stem.casefold() == model.stem.casefold() for p in model.parent.iterdir())
        for stem in (model.with_suffix(""), model):
            for suffix in SIDECARS:
                file = Path(str(stem) + suffix)
                if file.is_file() and not file.is_symlink():
                    if stem != model and shared:
                        warnings.append("同名不同格式模型共用的附属文件已保留")
                    elif file not in [p[0] for p in pairs]:
                        pairs.append((file, str(file)[len(str(stem)):], stem == model))
        return pairs, list(dict.fromkeys(warnings))

    def _invalidate(self, root):
        cache = getattr(self.index.folders, "filename_list_cache", None)
        if isinstance(cache, dict):
            for key in root["keys"]:
                cache.pop(key, None)
        helper = getattr(self.index.folders, "cache_helper", None)
        if isinstance(getattr(helper, "cache", None), dict):
            for key in root["keys"]:
                helper.cache.pop(key, None)

    def _publish(self, old, root, target, action):
        self._invalidate(root)
        source_root = self._root(old["root_id"], old["category"])
        self._invalidate(source_root)
        new_id = None if action == "delete" else identity(
            root["id"], root["category"], target.relative_to(root["path"]).as_posix())
        with self.index.db() as db:
            if new_id is not None:
                relative = target.relative_to(root["path"]).as_posix()
                db.execute("INSERT OR REPLACE INTO roots VALUES(?,?,?,?,?)",
                           (root["id"], root["category"], root["path"], json.dumps(root["keys"]), "ready"))
                db.execute("DELETE FROM locations WHERE id=?", (new_id,))
                db.execute("INSERT INTO locations VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                           (new_id, root["id"], root["category"], relative, target.name,
                            target.suffix[1:].upper(), old["size"], old["mtime_ns"], old["sha256"],
                            1, "file-operation", None, "unresolved", old["last_used"]))
                old_key, new_key = "location:" + old["id"], "location:" + new_id
                db.execute("UPDATE annotations SET key=? WHERE key=?", (new_key, old_key))
                db.execute("UPDATE annotation_history SET key=? WHERE key=?", (new_key, old_key))
                db.execute("UPDATE enrichment SET location_id=? WHERE location_id=?", (new_id, old["id"]))
            else:
                db.execute("UPDATE enrichment SET status='cancelled',message='模型文件已删除',updated=? "
                           "WHERE location_id=?", (stamp(), old["id"]))
            db.execute("DELETE FROM locations WHERE id=?", (old["id"],))
            # Refresh exact ComfyUI node options, including formerly ambiguous siblings.
            options = {}
            for registered in self.index.registered_roots():
                if registered["category"] != old["category"]:
                    continue
                self._invalidate(registered)
                for key in registered["keys"]:
                    try:
                        for name in self.index.folders.get_filename_list(key):
                            actual = self.index.folders.get_full_path(key, name)
                            if actual:
                                options[Path(actual).resolve()] = str(name)
                    except Exception:
                        pass
            rows = db.execute("SELECT l.*,r.path FROM locations l JOIN roots r ON r.id=l.root_id "
                              "WHERE l.category=? AND l.present=1", (old["category"],)).fetchall()
            counts = Counter(row["relative_path"] for row in rows)
            for row in rows:
                ambiguous = counts[row["relative_path"]] > 1
                node = None if ambiguous else options.get((Path(row["path"]) / row["relative_path"]).resolve())
                db.execute("UPDATE locations SET node_name=?,load_status=? WHERE id=?",
                           (node, "ambiguous" if ambiguous else "selectable" if node else "unresolved", row["id"]))
            db.execute("INSERT OR REPLACE INTO info VALUES('lastSync',?)", (str(stamp()),))
        return new_id

    def mutate(self, location_id, value):
        with self.index.lock:
            action = value.get("action")
            if action not in ("rename", "move", "delete"):
                raise CivitaiError("文件操作无效")
            old, model = self.index.resolve_file(location_id)
            root = self._root(old["root_id"], old["category"])
            model = contained(root["path"], old["relative_path"])
            current = model.stat()
            expected = (value.get("expectedPath"), value.get("expectedSize"), value.get("expectedMtimeNs"))
            if (any(isinstance(v, bool) for v in expected) or
                    expected != (old["relative_path"], current.st_size, current.st_mtime_ns) or
                    (current.st_size, current.st_mtime_ns) != (old["size"], old["mtime_ns"]) or
                    not stat.S_ISREG(current.st_mode)):
                raise CivitaiError("服务器文件已变化，请刷新详情后重试", 409)
            if action == "delete" and value.get("confirmName") != model.name:
                raise CivitaiError("请确认要删除的服务器文件名", 409)
            companions, warnings = self._companions(model)
            folder = model.parent
            if action == "rename":
                name = component(value.get("name"))
                if Path(name).suffix.lower() != model.suffix.lower():
                    raise CivitaiError("重命名请保留原模型扩展名")
                target = folder / name
            elif action == "move":
                root = self._root(value.get("rootId"), old["category"])
                relative = relative_directory(value.get("directory", ""))
                folder = contained(root["path"], relative)
                target = folder / model.name
            else:
                # Keep staged names outside ComfyUI's supported weight extensions,
                # even if a platform delays removal because another process holds it.
                folder = model.parent
                target = folder / (".mobile-delete-" + uuid.uuid4().hex + ".tmp")
            if os.path.normcase(str(target)) == os.path.normcase(str(model)):
                raise CivitaiError("文件名或目录未变化", 409)
            pairs = [(model, target)]
            for source, suffix, full_name in companions:
                destination = Path(str(target if full_name else target.with_suffix("")) + suffix)
                pairs.append((source, destination))
            for _, destination in pairs:
                if destination.exists() or destination.is_symlink():
                    raise CivitaiError("目标已存在同名文件或附属文件，未覆盖任何文件", 409)
            new_id = identity(root["id"], root["category"], target.relative_to(root["path"]).as_posix())
            with self.index.db() as db:
                if action != "delete" and db.execute("SELECT 1 FROM annotations WHERE key=?",
                                                    ("location:" + new_id,)).fetchone():
                    raise CivitaiError("目标位置保留了其他模型的人工标注，请选择其他名称或目录", 409)
            journal = self.index.directory / "file-operations" / (uuid.uuid4().hex + ".json")
            record = {"action": action, "oldId": location_id, "created": stamp(), "phase": "prepared",
                      "files": [{"source": str(a), "target": str(b)} for a, b in pairs]}
            atomic_json(journal, record)
            moved, created = [], []
            try:
                missing = folder
                while not missing.exists():
                    created.append(missing)
                    missing = missing.parent
                folder.mkdir(parents=True, exist_ok=True)
                for source, destination in pairs:
                    move_no_replace(source, destination)
                    moved.append((source, destination))
                new_id = self._publish(old, root, target, action)
            except BaseException:
                for source, destination in reversed(moved):
                    move_no_replace(destination, source)
                for directory in created:
                    directory.rmdir()
                record["phase"] = "rolled-back"
                atomic_json(journal, record)
                self._invalidate(root)
                raise
            record.update(phase="committed", newId=new_id)
            try:
                atomic_json(journal, record)
            except OSError:
                warnings.append("文件已完成操作，操作记录写入失败")
            if action == "delete":
                # Only files staged by this operation are removed. A cleanup failure
                # remains explicitly journalled and is reported, never called freed space.
                for _, destination in reversed(pairs):
                    try:
                        destination.unlink()
                    except OSError:
                        warnings.append("文件已移出模型库，但暂存文件清理未完成：" + str(destination))
            record.update(phase="completed", warnings=warnings)
            try:
                atomic_json(journal, record)
            except OSError:
                if "文件已完成操作，操作记录写入失败" not in warnings:
                    warnings.append("文件已完成操作，操作记录写入失败")
            return {"serverId": self.index.server_id, "action": action, "previousId": location_id,
                    "item": self.index.detail(new_id) if new_id else None,
                    "relatedFiles": len(companions), "warnings": warnings,
                    "message": {"rename": "服务器文件已重命名", "move": "模型已移动到服务器目标文件夹",
                                "delete": "服务器模型文件已删除"}[action]}
