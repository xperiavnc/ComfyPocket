"""Create the installable ComfyUI-Mobile-Bridge ZIP from the plugin source directory."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

# Runtime files only: the tests and byte-code caches are not part of an install.
SKIPPED_DIRS = {"__pycache__", "tests"}
SKIPPED_SUFFIXES = {".pyc", ".pyo"}
# Fixed timestamps keep the archive reproducible, so the same source always hashes the same.
FIXED_TIME = (2026, 10, 1, 0, 0, 0)
REQUIRED = ("__init__.py", "web/settings.js")


def sha256(file):
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def version_of(source):
    readme = source / "README.md"
    if readme.is_file():
        for line in readme.read_text(encoding="utf-8").splitlines():
            if line.startswith("# ComfyUI-Mobile-Bridge "):
                return line.split(" ", 2)[2].strip()
    return "unknown"


def payload(source):
    files = []
    for file in sorted(source.rglob("*")):
        if not file.is_file():
            continue
        relative = file.relative_to(source)
        if SKIPPED_DIRS & set(relative.parts) or file.suffix in SKIPPED_SUFFIXES:
            continue
        files.append(relative)
    return files


def pack(source, output):
    for required in REQUIRED:
        assert (source / required).is_file(), f"missing {required}"
    files = payload(source)
    version = version_of(source)
    # ComfyUI loads custom_nodes/<folder>/__init__.py, so the archive keeps one top-level folder.
    root = source.name
    if output.suffix.lower() != ".zip":
        output = output / f"{root}-{version}.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".zip.part")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for relative in files:
                info = zipfile.ZipInfo(f"{root}/{relative.as_posix()}", date_time=FIXED_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, (source / relative).read_bytes(), compresslevel=9)
        with zipfile.ZipFile(temporary) as archive:
            assert archive.testzip() is None
            names = archive.namelist()
            assert len(names) == len(files)
            assert all(name.startswith(f"{root}/") for name in names)
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    result = {"file": str(output.resolve()), "bytes": output.stat().st_size, "sha256": sha256(output),
              "plugin": root, "version": version, "entries": len(files)}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result


if __name__ == "__main__":
    project = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=project / "ComfyUI-Mobile-Bridge")
    parser.add_argument("--output", type=Path, default=project / "build/outputs/ComfyUI-Mobile-Bridge")
    args = parser.parse_args()
    pack(args.source.resolve(), args.output.resolve())
