"""Model-center v1 storage. All public methods run on worker threads, not aiohttp.

Locations describe registered files, SHA256 describes content, and annotations
are deliberately separate from provider data. No model file is ever modified.
"""
from __future__ import annotations

from contextlib import contextmanager
import hashlib
import json
import math
import os
from pathlib import Path
import sqlite3
import threading
import time
import uuid

from .civitai_client import CivitaiError
from .credentials import atomic_json

SCHEMA = 1
CATEGORIES = {
    "LORA": ("loras",),
    "CHECKPOINT": ("checkpoints",),
    "UNET": ("diffusion_models", "unet", "diffusion_models_gguf", "unet_gguf"),
    "VAE": ("vae",),
    "TEXT_ENCODER": ("text_encoders", "clip"),
}
FORMATS = {".safetensors", ".ckpt", ".pt", ".pth", ".bin", ".gguf", ".sft", ".onnx"}
USE_CASES = ("character", "style", "clothing", "pose", "scene", "lighting", "other")


def stamp():
    return int(time.time() * 1000)


def identity(*parts):
    return hashlib.sha256("\0".join(parts).encode("utf-8")).hexdigest()


def annotation_key(row):
    return "sha:" + row["sha256"] if row["sha256"] else "location:" + row["id"]


class ModelIndex:
    def __init__(self, folders, directory):
        self.folders = folders
        self.directory = Path(directory) / "model-center"
        self.lock = threading.RLock()
        self.ready = False
        self._server_id = None

    @contextmanager
    def db(self):
        with self.lock:
            self.directory.mkdir(parents=True, exist_ok=True)
            con = sqlite3.connect(self.directory / "index.sqlite", timeout=30)
            con.row_factory = sqlite3.Row
            try:
                if not self.ready:
                    version = con.execute("PRAGMA user_version").fetchone()[0]
                    if version not in (0, SCHEMA):
                        raise CivitaiError("模型资料库版本较新，请升级 Bridge；原资料保留", 409)
                    con.executescript("""
                        CREATE TABLE IF NOT EXISTS info(key TEXT PRIMARY KEY, value TEXT NOT NULL);
                        CREATE TABLE IF NOT EXISTS roots(
                          id TEXT PRIMARY KEY, category TEXT, path TEXT, keys_json TEXT, status TEXT);
                        CREATE TABLE IF NOT EXISTS locations(
                          id TEXT PRIMARY KEY, root_id TEXT, category TEXT, relative_path TEXT,
                          name TEXT, format TEXT, size INTEGER, mtime_ns INTEGER,
                          sha256 TEXT, present INTEGER, seen TEXT, node_name TEXT,
                          load_status TEXT, last_used INTEGER);
                        CREATE INDEX IF NOT EXISTS content_hash ON locations(sha256);
                        CREATE TABLE IF NOT EXISTS providers(
                          sha256 TEXT PRIMARY KEY, data TEXT NOT NULL);
                        CREATE TABLE IF NOT EXISTS annotations(
                          key TEXT PRIMARY KEY, revision INTEGER NOT NULL, data TEXT NOT NULL);
                        CREATE TABLE IF NOT EXISTS annotation_history(
                          key TEXT, revision INTEGER, data TEXT, PRIMARY KEY(key,revision));
                        CREATE TABLE IF NOT EXISTS enrichment(
                          id TEXT PRIMARY KEY, location_id TEXT, sha256 TEXT, status TEXT,
                          message TEXT, code INTEGER, retry_at INTEGER, updated INTEGER,
                          refresh INTEGER NOT NULL DEFAULT 0);
                    """)
                    if "refresh" not in {r[1] for r in con.execute("PRAGMA table_info(enrichment)")}:
                        con.execute("ALTER TABLE enrichment ADD COLUMN refresh INTEGER NOT NULL DEFAULT 0")
                    con.execute("INSERT OR IGNORE INTO info VALUES('serverId',?)", (uuid.uuid4().hex,))
                    con.execute(f"PRAGMA user_version={SCHEMA}")
                    con.commit()
                    self._server_id = con.execute("SELECT value FROM info WHERE key='serverId'").fetchone()[0]
                    self.ready = True
                yield con
                con.commit()
            finally:
                con.close()

    def info(self):
        with self.db() as db:
            values = dict(db.execute("SELECT key,value FROM info").fetchall())
            roots = [dict(row) for row in db.execute("SELECT id,category,path,status FROM roots ORDER BY category,path")]
            return {"schemaVersion": SCHEMA, "serverId": values["serverId"],
                    "capabilities": ["model-index", "model-annotations", "hash-enrichment",
                                     "download-index", "model-previews"],
                    "categories": sorted({r["category"] for r in roots}),
                    "useCases": list(USE_CASES), "roots": roots,
                    "storage": "ComfyUI user directory/mobile_bridge/model-center",
                    "lastSync": int(values.get("lastSync", "0")),
                    "scanComplete": values.get("scanComplete") == "true"}

    def registered_roots(self):
        result = {}
        for category, keys in CATEGORIES.items():
            for key in keys:
                try:
                    paths = self.folders.get_folder_paths(key)
                except Exception:
                    paths = []
                for value in paths or []:
                    path = Path(value).resolve()
                    root_id = identity(category, os.path.normcase(str(path)))
                    entry = result.setdefault(root_id, {
                        "id": root_id, "category": category, "path": str(path), "keys": []})
                    entry["keys"].append(key)
        return list(result.values())

    def put_location(self, root, path, seen, options=None):
        path = Path(path)
        stat = path.stat()
        relative = path.relative_to(Path(root["path"])).as_posix()
        location_id = identity(root["id"], root["category"], relative)
        options = options or {}
        node_name = options.get(path.resolve())
        with self.db() as db:
            old = db.execute("SELECT * FROM locations WHERE id=?", (location_id,)).fetchone()
            unchanged = old and old["size"] == stat.st_size and old["mtime_ns"] == stat.st_mtime_ns
            sha = old["sha256"] if unchanged else None
            db.execute("""INSERT INTO locations VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET size=excluded.size,mtime_ns=excluded.mtime_ns,
                sha256=excluded.sha256,present=1,seen=excluded.seen,
                node_name=excluded.node_name,load_status=excluded.load_status""",
                (location_id, root["id"], root["category"], relative, path.name,
                 path.suffix[1:].upper(), stat.st_size, stat.st_mtime_ns, sha, 1, seen,
                 node_name, "selectable" if node_name else "unresolved", None))
        return location_id

    def scan(self, stop, progress):
        """Incremental traversal publishes rows before any hashing. Unreadable roots
        are unknown, not missing. Pause/cancel never publishes an incomplete sweep."""
        token = uuid.uuid4().hex
        roots = self.registered_roots()
        complete = True
        with self.db() as db:
            db.execute("INSERT OR REPLACE INTO info VALUES('scanComplete','false')")
            for root in roots:
                db.execute("INSERT OR REPLACE INTO roots VALUES(?,?,?,?,?)",
                           (root["id"], root["category"], root["path"], json.dumps(root["keys"]), "scanning"))
        for root in roots:
            if stop.is_set():
                return False
            path = Path(root["path"])
            options = {}
            # Only exact names returned by the registered category API qualify.
            # get_full_path confirms which physical root a node option resolves to.
            for key in root["keys"]:
                try:
                    names = self.folders.get_filename_list(key)
                    for name in names:
                        actual = self.folders.get_full_path(key, name)
                        if actual:
                            options[Path(actual).resolve()] = str(name)
                except Exception:
                    pass
            errors = []
            if not path.is_dir():
                errors.append(True)
            def walk_error(error):
                errors.append(True)
            for directory, subdirs, files in os.walk(path, onerror=walk_error, followlinks=False):
                subdirs[:] = [s for s in subdirs if not (Path(directory) / s).is_symlink()]
                if stop.is_set():
                    return False
                for filename in files:
                    if stop.is_set():
                        return False
                    file = Path(directory) / filename
                    if file.suffix.lower() not in FORMATS or file.is_symlink():
                        continue
                    try:
                        self.put_location(root, file, token, options)
                        progress["discovered"] += 1
                    except OSError:
                        errors.append(True)
            with self.db() as db:
                db.execute("UPDATE roots SET status=? WHERE id=?",
                           ("unreadable" if errors else "ready", root["id"]))
                if not errors:
                    db.execute("UPDATE locations SET present=0,load_status='missing' WHERE root_id=? AND seen!=?",
                               (root["id"], token))
                else:
                    complete = False
                    db.execute("UPDATE locations SET load_status='unconfirmed' WHERE root_id=? AND seen!=?",
                               (root["id"], token))
        with self.db() as db:
            active_ids = {r["id"] for r in roots}
            for row in db.execute("SELECT id FROM roots").fetchall():
                if row["id"] not in active_ids:
                    db.execute("UPDATE roots SET status='unregistered' WHERE id=?", (row["id"],))
                    db.execute("UPDATE locations SET present=0,load_status='unregistered' WHERE root_id=?", (row["id"],))
            # Same registered relative name in several roots is ambiguous, even if
            # folder_paths currently picks the first one. Never invent a suffix path.
            db.execute("""UPDATE locations SET node_name=NULL,load_status='ambiguous'
                WHERE present=1 AND seen=? AND (category,relative_path) IN
                (SELECT category,relative_path FROM locations WHERE present=1
                 GROUP BY category,relative_path HAVING COUNT(*)>1)""", (token,))
            db.execute("INSERT OR REPLACE INTO info VALUES('lastSync',?)", (str(stamp()),))
            db.execute("INSERT OR REPLACE INTO info VALUES('scanComplete',?)", ("true" if complete else "false",))
        return True

    def hash_candidates(self):
        with self.db() as db:
            return [r[0] for r in db.execute("SELECT id FROM locations WHERE present=1 AND sha256 IS NULL")]

    def resolve_file(self, location_id):
        with self.db() as db:
            row = db.execute("""SELECT l.*,r.path FROM locations l JOIN roots r ON r.id=l.root_id
                                WHERE l.id=?""", (location_id,)).fetchone()
            if not row:
                raise CivitaiError("模型位置不存在", 404)
            root = next((r for r in self.registered_roots() if r["id"] == row["root_id"]), None)
            if not root or not row["present"]:
                raise CivitaiError("模型目录未注册或文件已缺失，请重新同步", 409)
            path = (Path(root["path"]) / row["relative_path"]).resolve()
            if not path.is_relative_to(Path(root["path"])) or path.is_symlink():
                raise CivitaiError("模型位置已变化，请重新同步", 409)
            return dict(row), path

    def hash_location(self, location_id, stop):
        row, path = self.resolve_file(location_id)
        before = path.stat()
        if row["sha256"] and (before.st_size, before.st_mtime_ns) == (row["size"], row["mtime_ns"]):
            return row["sha256"]
        checksum = hashlib.sha256()
        with path.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                if stop.is_set():
                    return None
                checksum.update(chunk)
        after = path.stat()
        if stop.is_set():
            return None
        if (before.st_size, before.st_mtime_ns, before.st_ino) != (
                after.st_size, after.st_mtime_ns, after.st_ino):
            raise CivitaiError("文件在计算指纹期间发生变化，请重新同步", 409)
        digest = checksum.hexdigest()
        self.attach_hash(location_id, digest, after)
        return digest

    def attach_hash(self, location_id, digest, stat):
        with self.db() as db:
            row = db.execute("SELECT * FROM locations WHERE id=?", (location_id,)).fetchone()
            if not row:
                raise CivitaiError("模型位置不存在", 404)
            old_key = annotation_key(row)
            # Replacing content at an existing location does not move the old
            # content's annotations to a different model.
            db.execute("UPDATE locations SET sha256=?,size=?,mtime_ns=? WHERE id=?",
                       (digest, stat.st_size, stat.st_mtime_ns, location_id))
            if not row["sha256"]:
                old_annotation = db.execute("SELECT * FROM annotations WHERE key=?", (old_key,)).fetchone()
                shared = db.execute("SELECT * FROM annotations WHERE key=?", ("sha:" + digest,)).fetchone()
                if old_annotation and not shared:
                    db.execute("INSERT INTO annotations VALUES(?,?,?)",
                               ("sha:" + digest, old_annotation["revision"], old_annotation["data"]))
                    db.execute("""INSERT OR IGNORE INTO annotation_history SELECT ?,revision,data
                                  FROM annotation_history WHERE key=?""", ("sha:" + digest, old_key))
                    db.execute("DELETE FROM annotations WHERE key=?", (old_key,))
                # If both locations already had different manual records, keep a
                # location override rather than silently dropping either person's edits.

    def provider(self, digest):
        with self.db() as db:
            row = db.execute("SELECT data FROM providers WHERE sha256=?", (digest,)).fetchone()
            return json.loads(row["data"]) if row else None

    def save_provider(self, digest, version):
        files = [f for f in (version.get("files") or []) if isinstance(f, dict)]
        matched = next((f for f in files if str((f.get("hashes") or {}).get("SHA256", "")).lower() == digest), None)
        if (not matched or not isinstance(version.get("id"), int) or version["id"] <= 0 or
                not isinstance(version.get("modelId"), int) or version["modelId"] <= 0 or
                not isinstance(matched.get("id"), int) or matched["id"] <= 0):
            raise CivitaiError("来源版本未包含匹配 SHA-256 的文件，未建立关联", 409)
        model = version.get("model") or {}
        images = []
        for image in (version.get("images") or [])[:12]:
            if isinstance(image, dict) and str(image.get("type", "image")) == "image":
                url = str(image.get("url") or "")
                if url.startswith("https://") and len(url) <= 2048:
                    images.append({"url": url})
        words = version.get("trainedWords")
        words = words if isinstance(words, list) else []
        data = {"provider": "civitai", "matchedHash": digest,
                "modelId": version.get("modelId"), "versionId": version["id"], "fileId": matched["id"],
                "name": str(model.get("name") or "")[:1000],
                "version": str(version.get("name") or "")[:1000],
                "author": str((version.get("creator") or {}).get("username") or "")[:500],
                "baseModel": str(version.get("baseModel") or "")[:200],
                "trainedWords": [x[:2000] for x in words
                                 if isinstance(x, str)][:100],
                "description": str(version.get("description") or "")[:50000],
                "images": images, "fetchedAt": stamp(),
                "source": f"https://civitai.com/models/{version.get('modelId')}?modelVersionId={version['id']}"}
        # Publish text independently from optional previews.
        atomic_json(self.directory / "metadata" / digest / "provider.json", data)
        with self.db() as db:
            db.execute("INSERT OR REPLACE INTO providers VALUES(?,?)",
                       (digest, json.dumps(data, ensure_ascii=False)))
        return data

    @staticmethod
    def validate_annotation(body):
        text_fields = {"alias": 200, "notes": 8000, "character": 500, "outfit": 500,
                       "baseModel": 200, "classificationSource": 40, "classificationStatus": 40}
        array_fields = {"aliases", "tags", "useCases", "trainedWords", "fieldLocks"}
        allowed = set(text_fields) | array_fields | {"favorite", "preferredStrength"}
        if set(body) - allowed:
            raise CivitaiError("标注包含不支持的字段")
        result = {}
        for key, value in body.items():
            if key in text_fields:
                if not isinstance(value, str) or len(value) > text_fields[key]:
                    raise CivitaiError(f"{key} 字段长度或类型不正确")
            elif key in array_fields:
                if (not isinstance(value, list) or len(value) > 100 or
                        any(not isinstance(v, str) or len(v) > 2000 for v in value)):
                    raise CivitaiError(f"{key} 应为有界字符串列表")
                if key == "useCases" and any(v not in USE_CASES for v in value):
                    raise CivitaiError("用途分类无效")
                if key == "fieldLocks" and any(v not in allowed for v in value):
                    raise CivitaiError("锁定字段无效")
            elif key == "favorite" and not isinstance(value, bool):
                raise CivitaiError("favorite 应为布尔值")
            elif key == "preferredStrength" and value is not None:
                if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or not -10 <= value <= 10:
                    raise CivitaiError("常用权重应在 -10 到 10 之间")
            result[key] = value
        if result.get("classificationSource", "user") not in ("user", "ai-suggestion"):
            raise CivitaiError("分类来源无效")
        if result.get("classificationStatus", "confirmed") not in ("confirmed", "suggested", "unclassified"):
            raise CivitaiError("分类状态无效")
        return result

    def annotate(self, location_id, expected_revision, body, undo_revision=None):
        with self.db() as db:
            row = db.execute("SELECT * FROM locations WHERE id=?", (location_id,)).fetchone()
            if not row:
                raise CivitaiError("模型位置不存在", 404)
            local_key = "location:" + row["id"]
            key = local_key if db.execute("SELECT 1 FROM annotations WHERE key=?", (local_key,)).fetchone() else annotation_key(row)
            old = db.execute("SELECT * FROM annotations WHERE key=?", (key,)).fetchone()
            revision = old["revision"] if old else 0
            if expected_revision != revision:
                raise CivitaiError("标注已被其他页面修改，请刷新后再保存", 409)
            previous = old["data"] if old else "{}"
            if undo_revision is not None:
                restore = db.execute("SELECT data FROM annotation_history WHERE key=? AND revision=?",
                                     (key, undo_revision)).fetchone()
                if not restore:
                    raise CivitaiError("此修订的撤销记录不存在", 404)
                data = json.loads(restore["data"])
            else:
                data = self.validate_annotation(body)
            db.execute("INSERT OR REPLACE INTO annotation_history VALUES(?,?,?)", (key, revision + 1, previous))
            db.execute("INSERT OR REPLACE INTO annotations VALUES(?,?,?)",
                       (key, revision + 1, json.dumps(data, ensure_ascii=False)))
        return self.detail(location_id)

    def _item(self, row, annotations, providers):
        item = dict(row)
        item["serverId"] = self.server_id
        for old, new in (("root_id", "rootId"), ("relative_path", "relativePath"),
                         ("mtime_ns", "mtimeNs"), ("node_name", "nodeModelName"),
                         ("load_status", "loadStatus"), ("last_used", "lastUsed")):
            item[new] = item.pop(old)
        item.pop("seen", None)
        item["present"] = bool(item["present"])
        item["mtime"] = item["mtimeNs"] // 1000000 if item["mtimeNs"] else None
        annotation = annotations.get("location:" + row["id"]) or annotations.get(annotation_key(row))
        item["annotation"] = json.loads(annotation["data"]) if annotation else {}
        item["revision"] = annotation["revision"] if annotation else 0
        item["provider"] = providers.get(row["sha256"])
        return item

    @property
    def server_id(self):
        if self._server_id:
            return self._server_id
        with self.db() as db:
            return db.execute("SELECT value FROM info WHERE key='serverId'").fetchone()[0]

    def _rows(self, db):
        # JSON search is Unicode casefolded in Python (SQLite LOWER is ASCII-only).
        # It stays off the event loop and only the requested page crosses the wire.
        annotations = {r["key"]: dict(r) for r in db.execute("SELECT * FROM annotations")}
        providers = {r["sha256"]: json.loads(r["data"]) for r in db.execute("SELECT * FROM providers")}
        return [self._item(r, annotations, providers) for r in db.execute(
            """SELECT l.*,r.path AS rootPath FROM locations l JOIN roots r ON r.id=l.root_id""")]

    def detail(self, location_id):
        with self.db() as db:
            row = db.execute("""SELECT l.*,r.path AS rootPath FROM locations l
                                JOIN roots r ON r.id=l.root_id WHERE l.id=?""", (location_id,)).fetchone()
            if not row:
                raise CivitaiError("模型位置不存在", 404)
            annotations = {r["key"]: dict(r) for r in db.execute("SELECT * FROM annotations WHERE key IN (?,?)",
                           (annotation_key(row), "location:" + row["id"]))}
            providers = {row["sha256"]: self.provider(row["sha256"])} if row["sha256"] else {}
            return self._item(row, annotations, providers)

    def query(self, query):
        try:
            page, limit = int(query.get("page", 1)), int(query.get("pageSize", 40))
            if page < 1 or not 1 <= limit <= 100:
                raise ValueError()
        except (TypeError, ValueError):
            raise CivitaiError("分页参数无效")
        with self.db() as db:
            items = self._rows(db)
            present = [x for x in items if x["present"]]
            stats = {"locations": len(present), "missing": len(items) - len(present),
                     "bytes": sum({str(Path(x["rootPath"]) / x["relativePath"]): x["size"] or 0
                                   for x in present}.values()),
                     "hashed": sum(bool(x["sha256"]) for x in present),
                     "uniqueContents": len({x["sha256"] for x in present if x["sha256"]})}
            needle = str(query.get("q") or "").strip().casefold()[:500]
            filtered = []
            for item in items:
                ann, provider = item["annotation"], item["provider"] or {}
                if query.get("category") and item["category"] != query["category"]:
                    continue
                if query.get("rootId") and item["rootId"] != query["rootId"]:
                    continue
                if query.get("favorite") == "true" and not ann.get("favorite"):
                    continue
                uses = ann.get("useCases") or []
                if query.get("useCase") == "unclassified" and uses:
                    continue
                if query.get("useCase") and query["useCase"] != "unclassified" and query["useCase"] not in uses:
                    continue
                if query.get("baseModel") and (ann.get("baseModel") or provider.get("baseModel")) != query["baseModel"]:
                    continue
                source = query.get("source", "")
                if source == "matched" and not provider:
                    continue
                if source == "unknown" and provider:
                    continue
                if source == "missing" and item["present"]:
                    continue
                words = ann.get("trainedWords") if "trainedWords" in (ann.get("fieldLocks") or []) else (
                    ann.get("trainedWords") or provider.get("trainedWords"))
                if source == "complete" and not ((ann.get("baseModel") or provider.get("baseModel")) and words):
                    continue
                haystack = [item["name"], item["relativePath"], item["rootPath"], ann, provider.get("name"),
                            provider.get("version"), provider.get("baseModel"), provider.get("trainedWords")]
                if needle and needle not in json.dumps(haystack, ensure_ascii=False).casefold():
                    continue
                item["matchBasis"] = ("用户标注与文件资料" if needle else "当前筛选")
                filtered.append(item)
            sort = query.get("sort", "name")
            if sort not in ("name", "size", "mtime", "recent"):
                raise CivitaiError("排序方式无效")
            key = {"name": lambda x: ((x["annotation"].get("alias") or x["name"]).casefold(), x["id"]),
                   "size": lambda x: (-(x["size"] or 0), x["id"]),
                   "mtime": lambda x: (-(x["mtime"] or 0), x["id"]),
                   "recent": lambda x: (-(x["lastUsed"] or 0), x["id"])}[sort]
            filtered.sort(key=key)
            info = self.info()
            return {"schemaVersion": SCHEMA, "serverId": info["serverId"],
                    "items": filtered[(page-1)*limit:page*limit], "page": page,
                    "total": len(filtered), "hasMore": page*limit < len(filtered),
                    "stats": stats, "lastSync": info["lastSync"],
                    "scanComplete": info["scanComplete"]}

    def installation(self, model_id, version_id, file_id):
        with self.db() as db:
            candidates = self._rows(db)
        exact, other = [], []
        for item in candidates:
            provider = item["provider"] or {}
            if not item["present"] or provider.get("modelId") != model_id or not item["sha256"]:
                continue
            try:
                _, path = self.resolve_file(item["id"])
                stat = path.stat()
                if (stat.st_size, stat.st_mtime_ns) != (item["size"], item["mtimeNs"]):
                    continue
            except (OSError, CivitaiError):
                continue
            if provider.get("versionId") == version_id and provider.get("fileId") == file_id:
                exact.append(item["id"])
            else:
                other.append(item["id"])
        return {"serverId": self.server_id, "status": "exact-file" if exact else
                "other-version" if other else "not-indexed", "locations": exact or other}

    def job_put(self, job):
        with self.db() as db:
            db.execute("""INSERT INTO enrichment
                        (id,location_id,sha256,status,message,code,retry_at,updated,refresh)
                        VALUES(?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(id) DO UPDATE SET sha256=excluded.sha256,status=excluded.status,
                        message=excluded.message,code=excluded.code,retry_at=excluded.retry_at,
                        updated=excluded.updated,refresh=excluded.refresh
                        WHERE excluded.updated >= enrichment.updated""",
                       (job["id"], job["locationId"], job.get("sha256"), job["status"],
                        job.get("message", ""), job.get("code", 0), job.get("retryAt", 0), job["updated"],
                        int(bool(job.get("refresh")))))
            db.execute("""DELETE FROM enrichment WHERE status NOT IN ('queued','hashing','matching','paused')
                          AND id NOT IN (SELECT id FROM enrichment WHERE status NOT IN
                          ('queued','hashing','matching','paused') ORDER BY updated DESC LIMIT 100)""")

    def jobs(self):
        with self.db() as db:
            return [{"id": r["id"], "locationId": r["location_id"], "sha256": r["sha256"],
                     "status": r["status"], "message": r["message"], "code": r["code"],
                     "retryAt": r["retry_at"], "updated": r["updated"], "refresh": bool(r["refresh"])}
                    for r in db.execute("SELECT * FROM enrichment ORDER BY updated DESC LIMIT 200")]
