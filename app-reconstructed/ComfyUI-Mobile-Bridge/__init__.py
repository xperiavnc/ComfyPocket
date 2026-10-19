"""
ComfyUI-Mobile-Bridge
----------------------
Standalone lightweight mobile bridge for ComfyUI.
Provides high-performance, mobile-optimized model library API compatible with
ComfyController Android app (RunningHub-aligned resource API format).

Features:
- Native Civitai download queue and server-only credentials with a ComfyUI settings GUI.
- Aggregates and trims bloated model metadata into lightweight (~5KB) mobile responses.
- Dynamically resolves preview image URLs to relative paths (port forwarding & tunnel friendly).
- Reads registered ComfyUI model directories directly; no model-manager dependency.
"""

from __future__ import annotations

import os
import sys
import re
import html
import json
import logging
import hashlib
import urllib.parse
from pathlib import Path
from typing import Any, Dict, List, Optional
import asyncio
from aiohttp import web, ClientSession

from .civitai_client import CivitaiError
from .civitai_downloads import model_folder_keys
from .civitai_service import CivitaiService
from .system_monitor import handle_system_monitor

logger = logging.getLogger("ComfyUI-Mobile-Bridge")

# ComfyUI imports
try:
    import folder_paths
    from server import PromptServer
except ImportError:
    PromptServer = None
    folder_paths = None


def _get_server_port() -> int:
    """Detect the active port ComfyUI is running on."""
    if PromptServer is not None:
        if hasattr(PromptServer.instance, "port") and PromptServer.instance.port:
            return PromptServer.instance.port
    # Fallback to ComfyUI cli_args if available
    try:
        import comfy.cli_args
        return comfy.cli_args.args.port
    except Exception:
        return 8188


def _normalize_relative_preview_url(url: Optional[str]) -> Optional[str]:
    """Ensure preview URLs are clean relative paths so mobile can prepend active baseUrl."""
    if not url:
        return None
    url_str = str(url).strip()
    if not url_str:
        return None
    # If it's already a full external URL (like civitai or imgur), keep it
    if url_str.startswith("http://") or url_str.startswith("https://"):
        # If it points to localhost/127.0.0.1, convert to relative path
        for prefix in ("http://127.0.0.1:", "http://localhost:", "https://127.0.0.1:", "https://localhost:"):
            if url_str.startswith(prefix):
                # e.g. http://127.0.0.1:8188/api/mobile/... -> /api/mobile/...
                parts = url_str.split("/", 3)
                if len(parts) >= 4:
                    return "/" + parts[3]
        return url_str
    # Already relative, ensure leading slash
    return url_str if url_str.startswith("/") else "/" + url_str


def _resolve_comfy_node_model_name(
    folder_type: str,
    file_path: Optional[str] = None,
    file_name: Optional[str] = None,
    folder: Optional[str] = None,
    model_name: Optional[str] = None
) -> str:
    """Resolve the exact relative model string required by ComfyUI nodes (respecting subfolders and extensions)."""
    if folder_paths is not None:
        try:
            valid_names = folder_paths.get_filename_list(folder_type) or []
        except Exception:
            valid_names = []

        if valid_names:
            # 1. Try matching by full path against folder_paths roots
            if file_path:
                norm_fp = os.path.normpath(file_path).lower()
                try:
                    roots = folder_paths.get_folder_paths(folder_type) or []
                except Exception:
                    roots = []
                for r in roots:
                    norm_r = os.path.normpath(r).lower()
                    if norm_fp.startswith(norm_r):
                        rel = os.path.relpath(file_path, r)
                        norm_rel = os.path.normpath(rel).lower()
                        rel_slash = rel.replace("\\", "/").lower()
                        for v in valid_names:
                            if os.path.normpath(v).lower() == norm_rel or v.replace("\\", "/").lower() == rel_slash:
                                return v

            # 2. Try matching by folder + file_name
            candidates_to_match = []
            if folder and file_name:
                candidates_to_match.append(os.path.join(folder, file_name))
                candidates_to_match.append(f"{folder}/{file_name}")
            if file_name:
                candidates_to_match.append(file_name)
            if model_name:
                candidates_to_match.append(model_name)

            for cand in candidates_to_match:
                cand_norm = os.path.normpath(cand).lower()
                cand_slash = cand.replace("\\", "/").lower()
                cand_stem = os.path.splitext(cand_slash)[0]
                for v in valid_names:
                    v_norm = os.path.normpath(v).lower()
                    v_slash = v.replace("\\", "/").lower()
                    v_stem = os.path.splitext(v_slash)[0]
                    if v_norm == cand_norm or v_slash == cand_slash or v_stem == cand_stem:
                        return v
                    if v_slash.endswith("/" + cand_slash) or v_stem.endswith("/" + cand_stem):
                        return v
                    if os.path.basename(v_slash) == cand_slash or os.path.splitext(os.path.basename(v_slash))[0] == cand_stem:
                        return v

    # Fallback if folder_paths or match not found
    res = file_name or model_name or ""
    if folder and folder.strip():
        f = folder.strip().replace("\\", "/").strip("/")
        res = f"{f}/{res}"
    if not res.endswith((".safetensors", ".ckpt", ".pt", ".bin", ".gguf", ".sft")):
        res += ".safetensors"
    return res


def _expand_base_model_aliases(tag: Optional[str]) -> List[str]:
    """Expand model ecosystem tag into all case variations and known cross-platform aliases."""
    t = str(tag or "").strip()
    if not t or t.lower() in ("all", "any", "全部"):
        return []
    tl = t.lower()
    if tl in ("anima", "animagine"):
        return [
            "anima", "Anima", "ANIMA", "Animagine", "animagine", "ANIMAGINE",
            "Animagine XL", "animagine xl", "Animagine-XL", "anima pencil", "AnimaPencil"
        ]
    elif tl in ("illustrious", "il-xl", "il"):
        return [
            "IL-XL", "NoobAI-XL", "Illustrious", "illustrious", "ILLUSTRIOUS",
            "Illustrious-XL", "illustrious-xl", "Illustrious XL", "illustrious xl",
            "NoobAI", "noobai", "IL", "il"
        ]
    elif tl in ("pony", "pony-xl", "pdxl"):
        return [
            "Pony-XL", "Pony", "pony", "PONY", "Pony V7", "pony v7",
            "PDXL", "pdxl", "Pony XL", "pony xl", "Pony Diffusion V6 XL"
        ]
    elif tl in ("sdxl", "sdxl 1.0"):
        return [
            "SDXL 1.0", "SDXL", "sdxl", "SDXL Turbo", "SDXL Lighting", "SDXL Lightning",
            "SDXL 1.0 LCM", "SDXL Hyper", "SDXL 0.9", "sdxl turbo", "sdxl lightning", "sdxl 1.0"
        ]
    elif tl in ("sd 1.5", "sd1.5", "1.5"):
        return [
            "SD 1.5", "sd 1.5", "SD1.5", "sd1.5", "SD 1.5 LCM", "SD 1.4", "v1-5", "1.5", "SD 1.x", "sd 1.x"
        ]
    elif tl in ("flux", "flux.1"):
        return [
            "F1基础 D", "F1基础 S", "F1基础-Kontext", "F1基础-Krea", "Flux2-Klein-4B", "Flux2-Klein-9B",
            "F2基础", "Flux.1 D", "Flux.1 S", "Flux.1 Krea", "Flux.1 Kontext", "Flux.2 D",
            "Flux.2 Klein 9B", "Flux.2 Klein 9B-base", "Flux.2 Klein 4B", "Flux.2 Klein 4B-base",
            "Flux", "flux", "FLUX.1", "flux.1", "FLUX"
        ]
    elif tl in ("wan", "wan2.1", "wan 2.1"):
        return [
            "WAN2.1", "WAN2.2", "Wan Video", "Wan", "wan", "WAN", "Wan2.1", "wan2.1", "Wan 2.1",
            "wan 2.1", "WanVideo", "wanvideo", "Wan Video 14B", "Wan Video 1.3B"
        ]
    elif tl in ("qwen", "qwen-image"):
        return [
            "Qwen-image", "qwen-image-2.1", "Qwen-Image-2512", "Qwen-Edit-2509", "Qwen-Edit-2511",
            "Qwen", "qwen", "QWEN", "Qwen 2.1", "qwen 2.1", "qwen-image"
        ]
    elif tl in ("hunyuanvideo", "hunyuan video"):
        return ["HunyuanVideo1.5", "HunyuanVideo", "hunyuanvideo", "Hunyuan Video", "hunyuan video"]
    elif tl in ("hunyuan", "hunyuan 1"):
        return ["HunyuanImage", "Hunyuan 1", "Hunyuan", "hunyuan"]
    elif tl in ("ltx", "ltxv"):
        return ["LTX2.3", "Lightricks/LTX-2", "LTXV", "LTXV2", "LTXV 2.3", "LTXV 2.5", "LTX-Video", "ltx-video", "ltx"]
    elif tl in ("minimax", "minimax-h3"):
        return ["minimax-h3", "MiniMax H3", "minimax", "MiniMax", "minimax-music3"]
    elif tl in ("z-image", "zimage"):
        return ["Z-image-turbo", "Z-image-base", "ZImageTurbo", "ZImageBase", "z-image", "Z-Image"]
    elif tl in ("noobai", "noobai-xl"):
        return ["NoobAI-XL", "NoobAI", "noobai", "noobai-xl", "NoobAI-XL (NAI-XL)"]
    elif tl in ("sd 3.5", "sd3.5", "sd 3.0"):
        return ["SD3.5", "SD 3.5", "SD 3.0", "sd3.5", "sd 3.5", "SD 3.5 Large", "SD 3.5 Medium"]
    elif tl in ("kolors",):
        return ["Kolors", "kolors"]
    elif tl in ("svd", "svd xt"):
        return ["SVD XT", "SVD", "svd", "svd xt"]
    elif tl in ("kling",):
        return ["kling", "Kling"]
    elif tl in ("vidu",):
        return ["vidu", "Vidu", "Vidu Q1"]
    elif tl in ("hidream",):
        return ["HiDream", "hidream"]
    elif tl in ("stable cascade",):
        return ["Stable Cascade", "stable cascade"]
    return [t, t.lower(), t.upper(), t.capitalize()]


def _clean_html_text(raw_text: Optional[str]) -> str:
    """Strip HTML tags and unescape entities into clean readable text."""
    if not raw_text:
        return ""
    text = str(raw_text)
    # Convert <br>, <br/>, </p> into newlines
    text = re.sub(r"(?i)<br\s*/?>", "\n", text)
    text = re.sub(r"(?i)</p>", "\n\n", text)
    # Remove all other HTML tags
    text = re.sub(r"<[^>]+>", "", text)
    # Unescape HTML entities (e.g. &amp;, &lt;, &gt;, &#39;, &quot;)
    text = html.unescape(text)
    # Normalize multiple blank lines
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def _format_usage_tips(raw_tips: str) -> str:
    """Convert usage_tips JSON string into clean human-readable text."""
    if not raw_tips:
        return ""
    stripped = raw_tips.strip()
    if not stripped:
        return ""
    # Try parsing JSON like {"strength": 0.8, "clip_strength": 0.8, "clip_skip": 2}
    if stripped.startswith("{") and stripped.endswith("}"):
        try:
            data = json.loads(stripped)
            if isinstance(data, dict):
                tip_parts = []
                if "strength" in data and data["strength"] is not None:
                    tip_parts.append(f"推荐权重: {data['strength']}")
                elif "strength_range" in data and data["strength_range"]:
                    tip_parts.append(f"权重范围: {data['strength_range']}")
                elif "strength_min" in data and "strength_max" in data:
                    tip_parts.append(f"权重范围: {data['strength_min']} ~ {data['strength_max']}")
                
                if "clip_strength" in data and data["clip_strength"] is not None:
                    tip_parts.append(f"CLIP: {data['clip_strength']}")
                if "clip_skip" in data and data["clip_skip"]:
                    tip_parts.append(f"CLIP Skip: {data['clip_skip']}")
                if tip_parts:
                    return " | ".join(tip_parts)
                return ""
        except Exception:
            pass
    return stripped


def _extract_model_description(item: Dict[str, Any], file_path: Optional[str] = None) -> str:
    """Cascade extraction of model descriptions, strictly prioritizing modelDescription from metadata."""
    notes = str(item.get("notes") or "").strip()
    raw_tips = str(item.get("usage_tips") or "").strip()
    direct_desc = str(item.get("modelDescription") or item.get("description") or "").strip()

    civitai = item.get("civitai") or {}
    civitai_ver_desc = str(civitai.get("description") or "").strip()
    civitai_model = civitai.get("model") or {}
    civitai_model_desc = str(civitai_model.get("description") or "").strip()

    main_desc = direct_desc or civitai_ver_desc or civitai_model_desc

    # If main description is not yet found, check companion .metadata.json on disk!
    if not main_desc and file_path:
        candidates = [file_path]
        if not os.path.isabs(file_path) and folder_paths is not None:
            for cat in ("loras", "checkpoints", "diffusion_models", "unet"):
                try:
                    for r in (folder_paths.get_folder_paths(cat) or []):
                        candidates.append(os.path.join(r, file_path))
                except Exception:
                    pass

        for fp in candidates:
            base_no_ext = os.path.splitext(fp)[0]
            # 1. Read an existing metadata sidecar: <model>.metadata.json
            for meta_file in (f"{base_no_ext}.metadata.json", f"{fp}.metadata.json"):
                if os.path.isfile(meta_file):
                    try:
                        with open(meta_file, "r", encoding="utf-8", errors="ignore") as f:
                            meta = json.load(f)
                            if isinstance(meta, dict):
                                if not notes and meta.get("notes"):
                                    notes = str(meta.get("notes")).strip()
                                if not raw_tips and meta.get("usage_tips"):
                                    raw_tips = str(meta.get("usage_tips")).strip()
                                
                                m_desc = str(meta.get("modelDescription") or meta.get("description") or "").strip()
                                m_civ = meta.get("civitai") or {}
                                m_civ_ver = str(m_civ.get("description") or "").strip()
                                m_civ_model = str((m_civ.get("model") or {}).get("description") or "").strip()

                                main_desc = m_desc or m_civ_ver or m_civ_model
                                if main_desc:
                                    break
                    except Exception as err:
                        logger.debug(f"Failed to read {meta_file}: {err}")
            if main_desc:
                break

            # 2. Check other companion files (.txt, .civitai.info, .info)
            for ext in (".txt", ".civitai.info", ".info"):
                p = f"{base_no_ext}{ext}"
                if os.path.isfile(p):
                    try:
                        with open(p, "r", encoding="utf-8", errors="ignore") as f:
                            content = f.read().strip()
                            if content:
                                if ext.endswith(".info"):
                                    try:
                                        info_json = json.loads(content)
                                        desc_val = info_json.get("description") or info_json.get("notes")
                                        if desc_val:
                                            main_desc = str(desc_val).strip()
                                            break
                                    except Exception:
                                        pass
                                else:
                                    main_desc = content
                                    break
                    except Exception:
                        pass
            if main_desc:
                break

    # Assemble sections: Model Description (main, preserving HTML structure) > Notes > Usage Tips
    sections: List[str] = []

    desc_str = main_desc.strip()
    if desc_str:
        sections.append(desc_str)

    if notes and notes != desc_str:
        sections.append(f"<p>【个人备注】<br>{notes}</p>")

    formatted_tips = _format_usage_tips(raw_tips)
    if formatted_tips and formatted_tips not in desc_str:
        sections.append(f"<p>【参数建议】<br>{formatted_tips}</p>")

    return "\n\n".join(sections).strip()


def _find_native_preview(model_path: str) -> Optional[str]:
    """Find a preview image adjacent to the model file in native ComfyUI directory."""
    p = Path(model_path)
    base_stem = p.stem
    parent = p.parent
    extensions = [".preview.png", ".png", ".preview.jpg", ".jpg", ".preview.jpeg", ".jpeg", ".preview.webp", ".webp"]
    for ext in extensions:
        cand = parent / f"{base_stem}{ext}"
        if cand.is_file():
            # Serve via mobile preview route
            return f"/api/mobile/preview?file={cand.as_posix()}"
    return None


def _list_native_models(
    resource_type: str,
    keyword: str,
    page: int,
    page_size: int,
    base_model: Optional[str] = None,
    refresh: bool = False,
    model_index=None
) -> Dict[str, Any]:
    """Read exact node options from all relevant registered categories.

    `unet` is a legacy alias on recent ComfyUI versions, not a GGUF category.
    GGUF loaders register their own keys and may share the ordinary UNet roots.
    Keep the original option string (including Windows separators) for node input.
    """
    if folder_paths is None:
        return {"records": [], "total": 0, "current": page, "size": page_size, "pages": 0, "hasNext": False}

    cat_map = {
        "LORA": ("loras",),
        "CHECKPOINT": ("checkpoints",),
        "UNET": ("diffusion_models", "unet"),
        "GGUF": ("unet_gguf", "diffusion_models_gguf", "diffusion_models", "unet"),
    }
    resource_type = resource_type.upper()
    if resource_type not in cat_map:
        raise ValueError("不支持的模型类型")
    folder_types = cat_map[resource_type]
    if refresh and page == 1:
        # Invalidate only this picker category, not unrelated ComfyUI caches.
        for cache in (getattr(folder_paths, "filename_list_cache", None),
                      getattr(getattr(folder_paths, "cache_helper", None), "cache", None)):
            if isinstance(cache, dict):
                for key in folder_types:
                    cache.pop(key, None)

    all_files = []
    seen = set()
    registered = getattr(folder_paths, "folder_names_and_paths", None)
    for folder_type in folder_types:
        if isinstance(registered, dict) and folder_type not in registered:
            continue
        try:
            names = folder_paths.get_filename_list(folder_type) or []
        except KeyError:
            continue  # Optional/legacy category is not registered on this server.
        for name in names:
            name = str(name)
            is_gguf = name.lower().endswith(".gguf")
            if resource_type in ("UNET", "GGUF") and is_gguf != (resource_type == "GGUF"):
                continue
            # Alias keys can expose the same option twice. Do not flatten subfolders.
            identity = os.path.normcase(name.replace("\\", "/"))
            if identity in seen:
                continue
            full_path = folder_paths.get_full_path(folder_type, name)
            if not full_path or not os.path.isfile(full_path):
                continue  # A stale option whose file was removed is not selectable.
            seen.add(identity)
            all_files.append((name, full_path))
    all_files.sort(key=lambda item: (item[0].replace("\\", "/").casefold(), item[0]))
    metadata = model_index.picker_metadata("UNET" if resource_type == "GGUF" else resource_type) \
        if model_index is not None else {}

    def indexed(item):
        return metadata.get(os.path.normcase(os.path.realpath(item[1])))

    # Filter keyword (case-insensitive)
    if keyword:
        kw = keyword.casefold()
        def matches_keyword(item):
            model = indexed(item) or {}
            provider = model.get("provider") or {}
            values = [item[0], model.get("annotation"), provider.get("name"),
                      provider.get("version"), provider.get("trainedWords")]
            return kw in json.dumps(values, ensure_ascii=False).casefold()
        all_files = [item for item in all_files if matches_keyword(item)]

    # Filter base model (case-insensitive match against all aliases)
    if base_model and base_model.lower() not in ("any", "all", "全部"):
        aliases = [a.lower() for a in _expand_base_model_aliases(base_model)]
        def matches_base(item):
            model = indexed(item) or {}
            value = (model.get("annotation") or {}).get("baseModel") or \
                (model.get("provider") or {}).get("baseModel")
            haystack = str(value or item[0]).lower()
            return any(a in haystack for a in aliases)
        all_files = [item for item in all_files if matches_base(item)]

    total = len(all_files)
    total_pages = (total + page_size - 1) // page_size if page_size > 0 else 1
    start = (page - 1) * page_size
    end = start + page_size
    page_files = all_files[start:end]

    records = []
    for f, full_path in page_files:
        clean_name = os.path.splitext(f.replace("\\", "/").rsplit("/", 1)[-1])[0]
        preview_url = _find_native_preview(full_path) if full_path else None
        native_desc = _extract_model_description({}, full_path) if full_path else ""
        
        model = indexed((f, full_path))
        if model is not None:
            model = model_index.with_preview(model)
        annotation = (model or {}).get("annotation") or {}
        provider = (model or {}).get("provider") or {}
        words = annotation.get("trainedWords") if "trainedWords" in (annotation.get("fieldLocks") or []) \
            else annotation.get("trainedWords") or provider.get("trainedWords") or []
        records.append({
            "id": f,
            "resourceName": clean_name,
            "resourceType": resource_type.upper(),
            "nodeModelName": f,
            "thumbnailUrl": preview_url,
            "posterUrl": preview_url,
            "desc": native_desc or f"本地模型: {f}",
            "localModel": model,
            "versions": [{
                "baseModel": annotation.get("baseModel") or provider.get("baseModel") or "",
                "triggerWords": ", ".join(words or []),
                "versionResourceName": f
            }]
        })

    return {
        "records": records,
        "total": total,
        "current": page,
        "size": page_size,
        "pages": total_pages,
        "hasNext": page < total_pages
    }


async def handle_mobile_status(request: web.Request) -> web.Response:
    return web.json_response({
        "code": 0, "msg": "success", "data": {
            "version": "2.2.6", "name": "ComfyUI-Mobile-Bridge",
            "loraManagerInstalled": False,
            "capabilities": ["native-civitai-downloads", "civitai-server-settings", "system-monitor",
                             "model-center-v1"],
            "supportedTypes": ["CHECKPOINT", "LORA", "UNET", "GGUF"]
        }
    })

def _get_allowed_preview_roots() -> List[Path]:
    """Collect all authorized root directories (models, extra_paths, outputs, etc.) from ComfyUI."""
    roots: List[Path] = []
    if folder_paths is not None:
        fnp = getattr(folder_paths, "folder_names_and_paths", None)
        if isinstance(fnp, dict):
            for entry in fnp.values():
                if isinstance(entry, (list, tuple)) and len(entry) > 0:
                    dirs = entry[0]
                    if isinstance(dirs, (list, tuple)):
                        for d in dirs:
                            try:
                                roots.append(Path(d).resolve())
                            except Exception:
                                pass
                    elif isinstance(dirs, (str, Path)):
                        try:
                            roots.append(Path(dirs).resolve())
                        except Exception:
                            pass

        for attr in ("models_dir", "base_path", "output_directory", "input_directory", "temp_directory"):
            val = getattr(folder_paths, attr, None)
            if callable(val):
                try:
                    val = val()
                except Exception:
                    val = None
            if val:
                try:
                    roots.append(Path(val).resolve())
                except Exception:
                    pass

    unique_roots: List[Path] = []
    seen = set()
    for r in roots:
        norm = str(r).lower() if sys.platform == "win32" else str(r)
        if norm not in seen:
            seen.add(norm)
            unique_roots.append(r)
    return unique_roots


def _is_safe_preview_path(file_path: str) -> tuple[bool, Optional[Path]]:
    """Verify that the target path exists, is an image, and resides inside permitted ComfyUI roots."""
    if not file_path or not str(file_path).strip():
        return False, None
    try:
        p = Path(file_path).resolve()
    except Exception:
        return False, None

    if not p.is_file():
        return False, None

    ext = p.suffix.lower()
    if ext not in {".png", ".jpg", ".jpeg", ".webp", ".gif"}:
        return False, None

    allowed_roots = _get_allowed_preview_roots()
    if not allowed_roots:
        return False, None

    norm_target = str(p).lower() if sys.platform == "win32" else str(p)
    for root in allowed_roots:
        norm_root = str(root).lower() if sys.platform == "win32" else str(root)
        try:
            p.relative_to(root)
            return True, p
        except ValueError:
            if sys.platform == "win32":
                clean_root = norm_root.rstrip("\\/")
                if norm_target == clean_root or norm_target.startswith(clean_root + "\\") or norm_target.startswith(clean_root + "/"):
                    return True, p

    return False, None


async def handle_mobile_preview(request: web.Request) -> web.StreamResponse:
    """Serve native model preview images safely within ComfyUI directories."""
    file_path = request.query.get("file", "")
    if not file_path:
        return web.Response(status=400, text="Missing file parameter")

    # Security check: must reside inside ComfyUI allowed directories (models, extra_paths, etc.)
    safe, target_path = _is_safe_preview_path(file_path)
    if not safe or target_path is None:
        if os.path.exists(file_path):
            logger.warning(f"[ComfyUI-Mobile-Bridge] Blocked preview access outside allowed directories: {file_path}")
            return web.Response(status=403, text="Access denied: file outside permitted directories")
        return web.Response(status=404, text="Preview file not found")

    ext = target_path.suffix.lower()
    mime_types = {
        ".png": "image/png",
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
        ".webp": "image/webp",
        ".gif": "image/gif"
    }
    content_type = mime_types.get(ext, "image/png")

    try:
        return web.FileResponse(str(target_path), headers={"Content-Type": content_type, "Cache-Control": "max-age=86400"})
    except Exception as e:
        return web.Response(status=500, text=str(e))


async def handle_resource_list(request: web.Request) -> web.Response:
    """Unified RunningHub-compatible resource list endpoint for mobile app."""
    # Support both GET query and POST JSON body
    params: Dict[str, Any] = {}
    if request.method == "POST":
        try:
            params = await request.json()
        except Exception:
            params = {}
    
    # Merge GET query params if present
    for k, v in request.query.items():
        params[k] = v

    resource_type = str(params.get("resourceType") or params.get("type") or "LORA").upper()
    keyword = str(params.get("keyword") or params.get("resourceName") or "").strip()
    
    try:
        page = max(1, int(params.get("page") or params.get("current") or 1))
    except (ValueError, TypeError):
        page = 1
        
    try:
        page_size = max(1, min(100, int(params.get("pageSize") or params.get("size") or 24)))
    except (ValueError, TypeError):
        page_size = 24

    base_model = params.get("baseModel") or params.get("base_model")

    refresh = str(params.get("refresh") or "").strip().lower() in ("true", "1", "yes")
    try:
        # Directory scans and sidecar reads must not block ComfyUI's event loop.
        native_data = await asyncio.to_thread(
            _list_native_models, resource_type, keyword, page, page_size, base_model, refresh,
            _get_civitai_service().model_center.index)
    except ValueError:
        return web.json_response({"code": 400, "msg": "不支持的模型类型", "data": None},
                                 status=400, headers={"Cache-Control": "no-store"})
    except Exception:
        logger.exception("Failed to read native model picker catalog")
        return web.json_response({"code": 502, "msg": "读取模型目录失败，请检查服务器目录后重试", "data": None},
                                 status=502, headers={"Cache-Control": "no-store"})
    return web.json_response({
        "code": 0,
        "msg": "success",
        "data": native_data
    }, headers={"Cache-Control": "no-store"})


async def handle_assets_summary(request: web.Request) -> web.Response:
    """Return local asset counts (models and installed custom nodes / plugins)."""
    port = _get_server_port()

    # 1. Count local models
    cp_count = 0
    lora_count = 0
    unet_count = 0
    if folder_paths is not None:
        try:
            cp_count = len(folder_paths.get_filename_list("checkpoints") or [])
        except Exception:
            pass
        try:
            lora_count = len(folder_paths.get_filename_list("loras") or [])
        except Exception:
            pass
        try:
            u_files = folder_paths.get_filename_list("diffusion_models") or []
            g_files = folder_paths.get_filename_list("unet") or []
            unet_count = len(set(u_files + g_files))
        except Exception:
            pass

    total_models = cp_count + lora_count + unet_count

    # 2. Check ComfyUI-Manager & installed custom nodes
    plugins_list: List[Dict[str, Any]] = []
    cm_installed = False
    try:
        async with ClientSession() as session:
            async with session.get(f"http://127.0.0.1:{port}/customnode/getinstalled", timeout=1.5) as resp:
                if resp.status == 200:
                    cm_installed = True
                    mgr_data = await resp.json()
                    nodes = mgr_data.get("custom_nodes") if isinstance(mgr_data, dict) else mgr_data
                    if isinstance(nodes, list):
                        for n in nodes:
                            if isinstance(n, dict):
                                plugins_list.append({
                                    "name": n.get("title") or n.get("author") or n.get("name") or "未知插件",
                                    "id": n.get("name") or n.get("id") or "",
                                    "description": n.get("description") or "",
                                    "enabled": not n.get("disabled", False)
                                })
                            elif isinstance(n, str):
                                plugins_list.append({"name": n, "id": n, "enabled": True})
    except Exception:
        cm_installed = False

    # Fallback scanning custom_nodes directory if ComfyUI-Manager did not return nodes
    if not plugins_list and folder_paths is not None:
        try:
            cn_dirs = folder_paths.get_folder_paths("custom_nodes") or []
            for d in cn_dirs:
                if os.path.isdir(d):
                    for entry in os.listdir(d):
                        if entry.startswith((".", "__")) or entry == ".git":
                            continue
                        subpath = os.path.join(d, entry)
                        if os.path.isdir(subpath):
                            plugins_list.append({
                                "name": entry,
                                "id": entry,
                                "enabled": not entry.endswith(".disabled")
                            })
        except Exception as e:
            logger.debug(f"Failed scanning custom_nodes: {e}")

    return web.json_response({
        "code": 0,
        "msg": "success",
        "data": {
            "totalModels": total_models,
            "checkpointsCount": cp_count,
            "lorasCount": lora_count,
            "unetCount": unet_count,
            "totalPlugins": len(plugins_list),
            "plugins": plugins_list[:50],  # Keep response compact
            "loraManagerInstalled": False,
            "comfyManagerInstalled": cm_installed
        }
    })


_IMAGE_CACHE_DIR = os.path.join(os.path.dirname(__file__), ".image_cache")
try:
    os.makedirs(_IMAGE_CACHE_DIR, exist_ok=True)
except Exception:
    pass


def _optimize_civitai_image_url(raw_url: Optional[str], width: int = 450) -> Optional[str]:
    """Optimize Civitai image URLs with specific width for fast, bandwidth-friendly loading."""
    if not raw_url:
        return None
    url_str = str(raw_url).strip()
    if not url_str:
        return None
    # Replace /original=true/ with /width=xxx/ for compressed thumbnail
    if "/original=true/" in url_str:
        url_str = url_str.replace("/original=true/", f"/width={width}/")
    return url_str


async def handle_civitai_image_proxy(request: web.Request) -> web.StreamResponse:
    """Proxy and cache Civitai preview images to allow mobile devices to view them through PC's network/proxy."""
    target_url = request.query.get("url", "").strip()
    if not target_url:
        return web.Response(status=400, text="Missing url parameter")

    allowed_hosts = ("image.civitai.com", "blobs-b2.civitai.com", "civitai.com", "civitai.red")
    try:
        parsed = urllib.parse.urlparse(target_url)
        if (parsed.scheme != "https" or parsed.username or parsed.password
                or parsed.port not in (None, 443) or not parsed.hostname
                or not any(parsed.hostname == h or parsed.hostname.endswith("." + h) for h in allowed_hosts)):
            return web.Response(status=403, text="Forbidden image domain")
    except Exception:
        return web.Response(status=400, text="Invalid url")

    url_hash = hashlib.md5(target_url.encode("utf-8")).hexdigest()
    cache_file = os.path.join(_IMAGE_CACHE_DIR, f"{url_hash}.bin")
    meta_file = os.path.join(_IMAGE_CACHE_DIR, f"{url_hash}.meta")

    if os.path.isfile(cache_file) and os.path.isfile(meta_file):
        try:
            with open(meta_file, "r") as mf:
                content_type = mf.read().strip() or "image/jpeg"
            return web.FileResponse(cache_file, headers={
                "Content-Type": content_type,
                "Cache-Control": "public, max-age=604800"
            })
        except Exception:
            pass

    try:
        async with ClientSession(trust_env=True) as session:
            headers = {"User-Agent": "ComfyController/1.0.0"}
            async with session.get(target_url, headers=headers, timeout=15.0, allow_redirects=False) as resp:
                if resp.status == 200:
                    data = await resp.read()
                    content_type = resp.headers.get("Content-Type", "image/jpeg")
                    try:
                        with open(cache_file, "wb") as cf:
                            cf.write(data)
                        with open(meta_file, "w") as mf:
                            mf.write(content_type)
                    except Exception:
                        pass
                    return web.Response(
                        body=data,
                        content_type=content_type,
                        headers={"Cache-Control": "public, max-age=604800"}
                    )
                else:
                    return web.Response(status=resp.status, text=f"Upstream status {resp.status}")
    except Exception as e:
        logger.warning("Civitai image proxy request failed")
        return web.Response(status=502, text="Image proxy request failed")


async def handle_civitai_search(request: web.Request) -> web.Response:
    """Proxy Civitai models search with structured, lightweight response."""
    query = request.query.get("query", "").strip()
    m_type = request.query.get("type", "").strip()
    base_model = request.query.get("baseModel", "").strip()
    try:
        page = max(1, int(request.query.get("page", "1")))
        limit = max(1, min(50, int(request.query.get("limit", "20"))))
    except ValueError:
        return web.json_response({"code": 400, "msg": "分页参数无效"}, status=400)
    cursor = request.query.get("cursor", "").strip()
    sort = request.query.get("sort", "Highest Rated")
    username = request.query.get("username", "").strip().lstrip("@")
    period = request.query.get("period", "AllTime")
    nsfw = request.query.get("nsfw", "false").lower()
    ids = request.query.get("ids", "").strip()
    if sort not in {"Highest Rated", "Most Downloaded", "Most Liked", "Most Discussed",
                    "Most Collected", "Most Images", "Newest", "Oldest"} or \
            period not in {"AllTime", "Year", "Month", "Week", "Day"} or nsfw not in {"true", "false"}:
        return web.json_response({"code": 400, "msg": "排序、时间范围或内容参数无效"}, status=400)
    if ids and (len(ids) > 16 or not ids.isascii() or not ids.isdigit() or not 0 < int(ids) <= 2**53 - 1):
        return web.json_response({"code": 400, "msg": "模型编号无效"}, status=400)
    if ids:
        query = ""

    params: List[tuple[str, str]] = [
        ("limit", str(limit)),
        ("nsfw", nsfw),
        ("period", period),
    ]
    if username:
        params.append(("username", username))
    if ids:
        params.append(("ids", ids))

    # Text queries use opaque cursor paging; public results are relevance-ordered.
    if query:
        params.append(("query", query))
        if cursor:
            params.append(("cursor", cursor))
    else:
        if cursor:
            params.append(("cursor", cursor))
        elif page:
            params.append(("page", str(page)))
        if sort:
            params.append(("sort", sort))

    # Type mapping aligned with model browser (LORA, CHECKPOINT, UNET, GGUF)
    type_upper = m_type.upper()
    if type_upper in ("LORA", "LORAS"):
        params.extend([("types", "LORA"), ("types", "LoCon"), ("types", "DoRA")])
    elif type_upper in ("CHECKPOINT", "CHECKPOINTS"):
        params.append(("types", "Checkpoint"))
    elif type_upper == "UNET":
        params.append(("types", "UNet"))
    elif type_upper == "GGUF":
        params.extend([("types", "Checkpoint"), ("types", "UNet")])
        # REST requires an array; a single query value is parsed as a string.
        params.extend([("fileFormats", "GGUF"), ("fileFormats", "GGUF")])
    elif m_type and type_upper not in ("ALL", "全部"):
        params.append(("types", m_type))

    if base_model and base_model.upper() not in ("ALL", "全部"):
        aliases = _expand_base_model_aliases(base_model)
        for a in aliases:
            params.append(("baseModels", a))

    try:
        raw_data = await _get_civitai_service().client.json("models", params)
        items = raw_data.get("items") or []
        metadata = raw_data.get("metadata") or {}

        formatted_items = []
        for it in items:
            if not isinstance(it, dict):
                continue
            m_id = it.get("id")
            name = it.get("name") or "未知模型"
            model_type = it.get("type") or "LORA"
            creator = (it.get("creator") or {}).get("username") or ""
            stats = it.get("stats") or {}
            tags = it.get("tags") or []

            m_desc = _clean_html_text(it.get("description") or "")
            raw_versions = it.get("modelVersions") or []
            formatted_versions = []
            preview_url = None

            for v in raw_versions:
                if not isinstance(v, dict):
                    continue
                v_id = v.get("id")
                v_name = v.get("name") or ""
                v_base = v.get("baseModel") or ""
                trained_words = v.get("trainedWords") or []
                v_desc = _clean_html_text(v.get("description") or "")
                download_url = v.get("downloadUrl") or ""

                files = v.get("files") or []
                formatted_files = []
                for f in files:
                    if not isinstance(f, dict):
                        continue
                    f_meta = f.get("metadata") or {}
                    if not isinstance(f_meta, dict):
                        f_meta = {}
                    f_download = f.get("downloadUrl") or download_url or ""
                    formatted_files.append({
                        "id": f.get("id") or 0,
                        "name": (f.get("overrideName") or f.get("name") or ""),
                        "sizeKB": f.get("sizeKB") or 0,
                        "downloadUrl": f_download,
                        "format": f_meta.get("format") or f.get("format") or "",
                        "precision": f_meta.get("fp") or "",
                        "type": f.get("type") or "Model",
                        # Civitai files[].type: VAE / Text Encoder = required components (case-insensitive)
                        "role": (
                            "component"
                            if str(f.get("type") or "").strip().lower().replace("_", " ")
                            in ("vae", "text encoder", "textencoder")
                            else "model"
                        ),
                        "primary": bool(f.get("primary")),
                    })
                primary_file = next((f for f in formatted_files if f.get("primary")), formatted_files[0] if formatted_files else {})
                size_kb = primary_file.get("sizeKB") or 0
                # Prefer primary file download URL when version-level URL is empty
                if not download_url and primary_file.get("downloadUrl"):
                    download_url = primary_file.get("downloadUrl") or ""

                # Get preview image
                images = [image for image in (v.get("images") or []) if isinstance(image, dict)]
                v_img = images[0].get("url") if images else None
                v_thumb = _optimize_civitai_image_url(v_img, width=450)
                v_detail = _optimize_civitai_image_url(v_img, width=800)
                if not preview_url and v_thumb:
                    preview_url = v_thumb

                formatted_versions.append({
                    "id": v_id,
                    "name": v_name,
                    "baseModel": v_base,
                    "publishedAt": v.get("publishedAt") or v.get("createdAt") or "",
                    "trainedWords": trained_words,
                    "description": v_desc,
                    "downloadUrl": download_url,
                    "sizeKB": size_kb,
                    "previewUrl": v_detail,
                    "files": formatted_files,
                    "availability": v.get("availability") or "",
                    "canDownload": v.get("canDownload"),
                    # Only expose metadata provided by the author; never invent prompts.
                    "images": [
                        {
                            "id": image.get("id") or 0,
                            "url": _optimize_civitai_image_url(image.get("url"), width=800) or "",
                            "prompt": str((image.get("meta") or {}).get("prompt") or ""),
                            "negativePrompt": str((image.get("meta") or {}).get("negativePrompt") or ""),
                            "sampler": str((image.get("meta") or {}).get("sampler") or ""),
                            "steps": str((image.get("meta") or {}).get("steps") or ""),
                            "seed": str((image.get("meta") or {}).get("seed")) if (image.get("meta") or {}).get("seed") is not None else "",
                            "cfgScale": str((image.get("meta") or {}).get("cfgScale")) if (image.get("meta") or {}).get("cfgScale") is not None else "",
                            "nsfwLevel": image.get("nsfwLevel"),
                            "minor": image.get("minor"),
                        }
                        for image in images[:12]
                        if isinstance(image, dict)
                        and isinstance(image.get("meta") or {}, dict)
                        and image.get("type", "image") == "image"
                    ],
                })

            formatted_items.append({
                "id": m_id,
                "name": name,
                "type": model_type,
                "nsfw": it.get("nsfw"),
                "nsfwLevel": it.get("nsfwLevel"),
                "minor": it.get("minor"),
                "sfwOnly": it.get("sfwOnly"),
                "creator": creator,
                "description": m_desc,
                "downloadCount": stats.get("downloadCount") or 0,
                "publishedAt": max((v.get("publishedAt") or v.get("createdAt") or ""
                                    for v in raw_versions if isinstance(v, dict)), default=""),
                "rating": stats.get("rating") or 0.0,
                "thumbsUpCount": stats.get("thumbsUpCount") or 0,
                "tags": tags[:5],
                "previewUrl": preview_url,
                "versions": formatted_versions
            })


        next_cursor = metadata.get("nextCursor")
        next_page = None
        next_url = metadata.get("nextPage")
        if next_url:
            parsed = urllib.parse.urlsplit(str(next_url))
            if parsed.scheme == "https" and parsed.netloc == "civitai.com" and parsed.path == "/api/v1/models":
                query_values = urllib.parse.parse_qs(parsed.query)
                next_cursor = next_cursor or (query_values.get("cursor") or [None])[0]
                value = (query_values.get("page") or [None])[0]
                if value and value.isdecimal():
                    next_page = int(value)
        return web.json_response({"code": 0, "msg": "success", "data": {
            "items": formatted_items,
            "nextCursor": str(next_cursor) if next_cursor is not None else None,
            "nextPage": next_page,
            "totalItems": metadata.get("totalItems") or len(formatted_items),
            "currentPage": metadata.get("currentPage") or (page if not query else 1),
            "pageSize": metadata.get("pageSize") or limit,
            "totalPages": metadata.get("totalPages") or (2 if next_cursor else 1)
        }})
    except CivitaiError as error:
        return web.json_response({"code": error.status, "msg": str(error), "data": None}, status=error.status)
    except Exception:
        return web.json_response({"code": 502, "msg": "电脑访问 Civitai 失败，请检查电脑网络与代理", "data": None}, status=502)


async def handle_model_folders(request: web.Request) -> web.Response:
    """Return available folders and subfolders on the server for a model type."""
    model_type = (request.query.get("type") or "LORA").upper()
    cat_keys = model_folder_keys(model_type)

    results = []
    seen = set()

    for cat in cat_keys:
        roots = []
        if folder_paths is not None:
            try:
                roots = folder_paths.get_folder_paths(cat) or []
            except Exception:
                roots = []

        for root in roots:
            if not root or not os.path.isdir(root):
                continue
            root_norm = os.path.normpath(root)
            base_display = os.path.basename(root_norm) or cat
            if root_norm not in seen:
                seen.add(root_norm)
                results.append({
                    "name": base_display,
                    "root": root_norm,
                    "subfolder": ""
                })
            # Scan subdirectories up to 2 levels
            try:
                for entry in os.scandir(root_norm):
                    if entry.is_dir() and not entry.name.startswith((".", "__")):
                        sub_rel = entry.name
                        sub_full = os.path.join(root_norm, sub_rel)
                        if sub_full not in seen:
                            seen.add(sub_full)
                            results.append({
                                "name": f"{base_display}/{sub_rel}",
                                "root": root_norm,
                                "subfolder": sub_rel
                            })
                        try:
                            for sub_entry in os.scandir(sub_full):
                                if sub_entry.is_dir() and not sub_entry.name.startswith((".", "__")):
                                    sub2_rel = os.path.join(sub_rel, sub_entry.name).replace("\\", "/")
                                    sub2_full = os.path.join(root_norm, sub2_rel)
                                    if sub2_full not in seen:
                                        seen.add(sub2_full)
                                        results.append({
                                            "name": f"{base_display}/{sub2_rel}",
                                            "root": root_norm,
                                            "subfolder": sub2_rel
                                        })
                        except Exception:
                            pass
            except Exception as e:
                logger.debug(f"Error scanning directories in {root_norm}: {e}")

    return web.json_response({
        "code": 0,
        "msg": "success",
        "data": {
            "folders": results
        }
    })


_civitai_service = None


def _get_civitai_service():
    global _civitai_service
    if _civitai_service is None:
        _civitai_service = CivitaiService(folder_paths)
    return _civitai_service


WEB_DIRECTORY = "./web"


# Register routes with ComfyUI server
if PromptServer is not None:
    try:
        app = PromptServer.instance.app
        app.router.add_get("/api/mobile/status", handle_mobile_status)
        app.router.add_get("/api/mobile/system/monitor", handle_system_monitor)
        app.router.add_get("/api/mobile/preview", handle_mobile_preview)
        app.router.add_get("/api/mobile/resource/list", handle_resource_list)
        app.router.add_post("/api/mobile/resource/list", handle_resource_list)
        app.router.add_get("/api/mobile/system/assets_summary", handle_assets_summary)
        app.router.add_get("/api/mobile/civitai/search", handle_civitai_search)
        app.router.add_get("/api/mobile/civitai/image-proxy", handle_civitai_image_proxy)
        app.router.add_get("/api/mobile/model-folders", handle_model_folders)
        _get_civitai_service().register(app)
        logger.info("[ComfyUI-Mobile-Bridge] Mobile endpoints registered successfully at /api/mobile/*")
    except Exception as err:
        logger.warning(f"[ComfyUI-Mobile-Bridge] Failed to register mobile endpoints: {err}")

NODE_CLASS_MAPPINGS = {}
NODE_DISPLAY_NAME_MAPPINGS = {}
