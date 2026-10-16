"""Server-only Civitai credentials. Never put keys in ComfyUI's public settings."""
from __future__ import annotations

import base64
import ctypes
import json
import os
import re
import tempfile
from pathlib import Path


def validate_key(value: object) -> str:
    if not isinstance(value, str):
        raise ValueError("Key 应为文本")
    key = value.strip()
    if key and not re.fullmatch(r"[A-Za-z0-9._~+/\-]{1,512}={0,2}", key):
        raise ValueError("请输入原始 Civitai API Key，不含 Bearer、空格或换行")
    return key


def atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, name = tempfile.mkstemp(prefix=".mobile-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


class _Blob(ctypes.Structure):
    _fields_ = [("size", ctypes.c_uint32), ("data", ctypes.POINTER(ctypes.c_ubyte))]


def _dpapi(value: bytes, encrypt: bool) -> bytes:
    """Use the Windows account's DPAPI; no dependency or shared encryption key."""
    buffer = (ctypes.c_ubyte * len(value)).from_buffer_copy(value)
    source = _Blob(len(value), buffer)
    target = _Blob()
    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    function = crypt.CryptProtectData if encrypt else crypt.CryptUnprotectData
    function.argtypes = [
        ctypes.POINTER(_Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
        ctypes.c_void_p, ctypes.c_uint32, ctypes.POINTER(_Blob),
    ]
    function.restype = ctypes.c_int
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise OSError("Windows 凭据存储操作失败")
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        kernel.LocalFree(target.data)


class CredentialStore:
    def __init__(self, directory: Path):
        self.path = Path(directory) / "civitai-settings.json"
        self._saved = ""
        self._unreadable = False
        if self.path.exists():
            try:
                document = json.loads(self.path.read_text(encoding="utf-8"))
                if document.get("storage") == "windows-dpapi":
                    value = _dpapi(base64.b64decode(document["key"]), False).decode("utf-8")
                elif document.get("storage") == "owner-only":
                    value = document["key"]
                else:
                    raise ValueError("Unknown credential storage")
                self._saved = validate_key(value)
            except Exception:
                # Preserve a damaged / other Windows account's file; don't silently overwrite it.
                self._unreadable = True

    def key(self) -> str:
        return self._saved or validate_key(os.environ.get("CIVITAI_API_KEY", ""))

    def status(self) -> dict:
        key = self.key()
        return {
            "configured": bool(key),
            "source": "settings" if self._saved else ("environment" if key else "none"),
            "storage": "windows-dpapi" if os.name == "nt" else "owner-only",
            "unreadable": self._unreadable,
        }

    def save(self, value: object) -> None:
        key = validate_key(value)
        if not key:
            raise ValueError("Key 为空；保留当前设置。清除请点击“清除已保存 Key”")
        document = {"version": 1}
        if os.name == "nt":
            document.update(storage="windows-dpapi", key=base64.b64encode(
                _dpapi(key.encode("utf-8"), True)).decode("ascii"))
        else:
            document.update(storage="owner-only", key=key)
        atomic_json(self.path, document)
        self._saved = key
        self._unreadable = False

    def clear(self) -> None:
        self.path.unlink(missing_ok=True)
        self._saved = ""
        self._unreadable = False
