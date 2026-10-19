"""Create the optional, reproducible ComfyPocket vocabulary ZIP without changing its data."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import zipfile


def sha256(file):
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def pack(source, output):
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    database = source / "catalogue-v1.db"
    assert database.stat().st_size == manifest["bytes"]
    assert sha256(database) == manifest["sha256"]
    with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True) as db:
        assert db.execute("PRAGMA quick_check").fetchone() == ("ok",)
        counts = dict(db.execute("SELECT kind,COUNT(*) FROM entries GROUP BY kind"))
        assert counts == {"tag": manifest["counts"]["tags"], "character": manifest["counts"]["characters"]}
    for item in manifest["sources"]:
        if item["path"].startswith("assets/"):
            raw = source / "toolbox" / item["path"].removeprefix("assets/")
            assert sha256(raw) == item["sha256"], str(raw)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".zip.part")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for file in sorted(source.rglob("*")):
                if file.is_file():
                    info = zipfile.ZipInfo(file.relative_to(source).as_posix(), date_time=(2026, 10, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = 0o100644 << 16
                    archive.writestr(info, file.read_bytes(), compresslevel=9)
        with zipfile.ZipFile(temporary) as archive:
            assert archive.testzip() is None
            assert len(archive.namelist()) == len([p for p in source.rglob("*") if p.is_file()])
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    result = {"file": str(output.resolve()), "bytes": output.stat().st_size, "sha256": sha256(output),
              "revision": manifest["revision"], "counts": manifest["counts"]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result


if __name__ == "__main__":
    project = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=project / "tag-library")
    parser.add_argument("--output", type=Path, default=project / "build/outputs/tag-library/ComfyPocket-tag-library.zip")
    args = parser.parse_args()
    pack(args.source.resolve(), args.output.resolve())
