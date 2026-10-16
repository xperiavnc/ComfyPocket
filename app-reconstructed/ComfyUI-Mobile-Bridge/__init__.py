"""
ComfyUI-Mobile-Bridge
----------------------
Standalone lightweight mobile bridge for ComfyUI.
Provides high-performance, mobile-optimized model library API compatible with
ComfyController Android app (RunningHub-aligned resource API format).

Features:
- Completely decoupled from ComfyUI-Lora-Manager: safe from any upstream updates.
- Aggregates and trims bloated model metadata into lightweight (~5KB) mobile responses.
- Dynamically resolves preview image URLs to relative paths (port forwarding & tunnel friendly).
- Seamless fallback to native ComfyUI model directories if Lora-Manager is not installed.
"""

from __future__ import annotations

import os
import sys
import re
import html
import json
import logging
import hashlib
import platform
import shutil
import urllib.parse
from pathlib import Path
from typing import Any, Dict, List, Optional
import asyncio
from aiohttp import web, ClientSession, ClientTimeout

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
                # e.g. http://127.0.0.1:8188/api/lm/... -> /api/lm/...
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
            # 1. First check Lora-Manager sidecar: <model>.metadata.json
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


async def _fetch_from_lora_manager(
    category: str,
    keyword: str,
    page: int,
    page_size: int,
    base_model: Optional[str] = None
) -> Optional[Dict[str, Any]]:
    """Query ComfyUI-Lora-Manager internal API via localhost."""
    port = _get_server_port()
    # Route mapping
    cat_lower = category.lower()
    if cat_lower in ("lora", "loras"):
        endpoint = f"http://127.0.0.1:{port}/api/lm/loras/list"
    elif cat_lower in ("checkpoint", "checkpoints"):
        endpoint = f"http://127.0.0.1:{port}/api/lm/checkpoints/list"
    elif cat_lower in ("unet", "gguf", "other", "others"):
        endpoint = f"http://127.0.0.1:{port}/api/lm/others/list"
    else:
        endpoint = f"http://127.0.0.1:{port}/api/lm/loras/list"

    # Multi-value parameters list for aiohttp: supports multiple base_model parameters
    query_params: List[tuple[str, str]] = [
        ("page", str(page)),
        ("page_size", str(page_size)),
    ]
    if keyword:
        query_params.append(("search", keyword))
        query_params.append(("query", keyword))
        query_params.append(("search_tags", "true"))
        query_params.append(("search_modelname", "true"))
        query_params.append(("search_filename", "true"))

    if base_model and base_model.lower() not in ("any", "all", "全部"):
        aliases = _expand_base_model_aliases(base_model)
        for a in aliases:
            query_params.append(("base_model", a))

    if cat_lower == "unet":
        query_params.append(("sub_type", "unet"))
    elif cat_lower == "gguf":
        query_params.append(("sub_type", "gguf"))

    try:
        async with ClientSession() as session:
            async with session.get(endpoint, params=query_params, timeout=3.0) as resp:
                if resp.status == 200:
                    return await resp.json()
    except Exception as e:
        logger.debug(f"Could not fetch from Lora-Manager ({endpoint}): {e}")
        return None
    return None


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
    base_model: Optional[str] = None
) -> Dict[str, Any]:
    """Native ComfyUI fallback when ComfyUI-Lora-Manager is not installed."""
    if folder_paths is None:
        return {"records": [], "total": 0, "current": page, "size": page_size, "pages": 0, "hasNext": False}

    cat_map = {
        "LORA": "loras",
        "CHECKPOINT": "checkpoints",
        "UNET": "diffusion_models",
        "GGUF": "unet",
    }
    folder_type = cat_map.get(resource_type.upper(), "loras")
    
    try:
        all_files = folder_paths.get_filename_list(folder_type) or []
    except Exception:
        all_files = []

    # Filter keyword (case-insensitive)
    if keyword:
        kw = keyword.lower()
        all_files = [f for f in all_files if kw in f.lower()]

    # Filter base model (case-insensitive match against all aliases)
    if base_model and base_model.lower() not in ("any", "all", "全部"):
        aliases = [a.lower() for a in _expand_base_model_aliases(base_model)]
        all_files = [f for f in all_files if any(a in f.lower() for a in aliases)]

    total = len(all_files)
    total_pages = (total + page_size - 1) // page_size if page_size > 0 else 1
    start = (page - 1) * page_size
    end = start + page_size
    page_files = all_files[start:end]

    records = []
    for f in page_files:
        clean_name = os.path.splitext(os.path.basename(f))[0]
        full_path = folder_paths.get_full_path(folder_type, f)
        preview_url = _find_native_preview(full_path) if full_path else None
        native_desc = _extract_model_description({}, full_path) if full_path else ""
        
        records.append({
            "id": f,
            "resourceName": clean_name,
            "resourceType": resource_type.upper(),
            "nodeModelName": f,
            "thumbnailUrl": preview_url,
            "posterUrl": preview_url,
            "desc": native_desc or f"本地模型: {f}",
            "versions": [{
                "baseModel": "",
                "triggerWords": "",
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
    """Health & feature detection endpoint for mobile app."""
    port = _get_server_port()
    # Test if lora manager is reachable
    lm_installed = False
    try:
        async with ClientSession() as session:
            async with session.get(f"http://127.0.0.1:{port}/api/lm/loras/list", params={"page_size": 1}, timeout=1.0) as resp:
                lm_installed = (resp.status == 200)
    except Exception:
        lm_installed = False

    return web.json_response({
        "code": 0,
        "msg": "success",
        "data": {
            "version": "1.0.0",
            "name": "ComfyUI-Mobile-Bridge",
            "loraManagerInstalled": lm_installed,
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

    cat_map = {
        "LORA": "loras",
        "CHECKPOINT": "checkpoints",
        "UNET": "diffusion_models",
        "GGUF": "unet",
    }
    folder_type = cat_map.get(resource_type.upper(), "loras")

    # 1. Try querying ComfyUI-Lora-Manager first
    lm_result = await _fetch_from_lora_manager(resource_type, keyword, page, page_size, base_model)
    if lm_result and isinstance(lm_result, dict) and "items" in lm_result:
        items = lm_result.get("items") or []
        records = []
        for it in items:
            civitai = it.get("civitai") or {}
            trained_words = civitai.get("trainedWords") or []
            if isinstance(trained_words, list):
                tw_str = ", ".join(trained_words)
            else:
                tw_str = str(trained_words or "")

            preview = _normalize_relative_preview_url(it.get("preview_url"))
            model_name = it.get("model_name") or it.get("file_name") or ""
            file_name = it.get("file_name") or model_name
            folder = it.get("folder") or ""
            file_path = it.get("file_path") or ""

            node_model_name = _resolve_comfy_node_model_name(
                folder_type=folder_type,
                file_path=file_path,
                file_name=file_name,
                folder=folder,
                model_name=model_name
            )

            records.append({
                "id": file_path or it.get("sha256") or node_model_name,
                "resourceName": model_name,
                "resourceType": resource_type,
                "nodeModelName": node_model_name,
                "thumbnailUrl": preview,
                "posterUrl": preview,
                "desc": _extract_model_description(it, file_path),
                "versions": [{
                    "baseModel": it.get("base_model") or "",
                    "triggerWords": tw_str,
                    "versionResourceName": node_model_name
                }]
            })

        total = int(lm_result.get("total") or len(records))
        total_pages = int(lm_result.get("total_pages") or ((total + page_size - 1) // page_size if page_size else 1))

        return web.json_response({
            "code": 0,
            "msg": "success",
            "data": {
                "records": records,
                "total": total,
                "current": page,
                "size": page_size,
                "pages": total_pages,
                "hasNext": page < total_pages
            }
        })

    # 2. Fallback to native ComfyUI folders
    native_data = _list_native_models(resource_type, keyword, page, page_size, base_model)
    return web.json_response({
        "code": 0,
        "msg": "success",
        "data": native_data
    })


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

    # Check Lora-Manager installation
    lm_installed = False
    try:
        async with ClientSession() as session:
            async with session.get(f"http://127.0.0.1:{port}/api/lm/loras/list", params={"page_size": 1}, timeout=1.0) as resp:
                lm_installed = (resp.status == 200)
    except Exception:
        lm_installed = False

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
            "loraManagerInstalled": lm_installed,
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
        if not any(parsed.netloc.endswith(h) for h in allowed_hosts):
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
            async with session.get(target_url, headers=headers, timeout=15.0) as resp:
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
        logger.warning(f"Failed to proxy image {target_url}: {e}")
        return web.Response(status=502, text=str(e))


async def handle_civitai_search(request: web.Request) -> web.Response:
    """Proxy Civitai models search with structured, lightweight response."""
    query = request.query.get("query", "").strip()
    m_type = request.query.get("type", "").strip()
    base_model = request.query.get("baseModel", "").strip()
    page = request.query.get("page", "1")
    cursor = request.query.get("cursor", "").strip()
    limit = request.query.get("limit", "20")
    sort = request.query.get("sort", "Highest Rated")

    civitai_url = "https://civitai.com/api/v1/models"
    params: List[tuple[str, str]] = [
        ("limit", limit),
        ("nsfw", "false"),
    ]

    # Civitai forbids "page" and "sort" when "query" is present (uses cursor-based search)
    if query:
        params.append(("query", query))
        if cursor:
            params.append(("cursor", cursor))
    else:
        if cursor:
            params.append(("cursor", cursor))
        elif page:
            params.append(("page", page))
        if sort:
            params.append(("sort", sort))

    # Type mapping aligned with model browser (LORA, CHECKPOINT, UNET, GGUF)
    type_upper = m_type.upper()
    if type_upper in ("LORA", "LORAS"):
        params.append(("types", "LORA"))
    elif type_upper in ("CHECKPOINT", "CHECKPOINTS", "UNET", "GGUF"):
        params.append(("types", "Checkpoint"))
    elif m_type and type_upper not in ("ALL", "全部"):
        params.append(("types", m_type))

    if base_model and base_model.upper() not in ("ALL", "全部"):
        aliases = _expand_base_model_aliases(base_model)
        for a in aliases:
            params.append(("baseModels", a))

    try:
        # Respect system proxy if configured
        trust_env = True
        async with ClientSession(trust_env=trust_env) as session:
            headers = {"User-Agent": "ComfyController/1.0.0"}
            async with session.get(civitai_url, params=params, headers=headers, timeout=15.0) as resp:
                if resp.status != 200:
                    err_txt = await resp.text()
                    logger.warning(f"[ComfyUI-Mobile-Bridge] Civitai error HTTP {resp.status}: {err_txt}")
                    return web.json_response({
                        "code": resp.status,
                        "msg": f"Civitai 请求失败 (HTTP {resp.status}): {err_txt[:120]}",
                        "data": None
                    }, status=502)

                raw_data = await resp.json()
                items = raw_data.get("items") or []
                metadata = raw_data.get("metadata") or {}

                formatted_items = []
                for it in items:
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
                        images = v.get("images") or []
                        v_img = images[0].get("url") if images else None
                        v_thumb = _optimize_civitai_image_url(v_img, width=450)
                        v_detail = _optimize_civitai_image_url(v_img, width=800)
                        if not preview_url and v_thumb:
                            preview_url = v_thumb

                        formatted_versions.append({
                            "id": v_id,
                            "name": v_name,
                            "baseModel": v_base,
                            "trainedWords": trained_words,
                            "description": v_desc,
                            "downloadUrl": download_url,
                            "sizeKB": size_kb,
                            "previewUrl": v_detail,
                            "files": formatted_files,
                        })

                    formatted_items.append({
                        "id": m_id,
                        "name": name,
                        "type": model_type,
                        "creator": creator,
                        "description": m_desc,
                        "downloadCount": stats.get("downloadCount") or 0,
                        "rating": stats.get("rating") or 0.0,
                        "thumbsUpCount": stats.get("thumbsUpCount") or 0,
                        "tags": tags[:5],
                        "previewUrl": preview_url,
                        "versions": formatted_versions
                    })

                next_cursor = metadata.get("nextCursor")
                return web.json_response({
                    "code": 0,
                    "msg": "success",
                    "data": {
                        "items": formatted_items,
                        "nextCursor": str(next_cursor) if next_cursor is not None else None,
                        "totalItems": metadata.get("totalItems") or len(formatted_items),
                        "currentPage": metadata.get("currentPage") or int(page if not query else 1),
                        "pageSize": metadata.get("pageSize") or int(limit),
                        "totalPages": metadata.get("totalPages") or (2 if next_cursor else 1)
                    }
                })
    except Exception as e:
        logger.error(f"[ComfyUI-Mobile-Bridge] Civitai search error: {e}", exc_info=True)
        return web.json_response({
            "code": 500,
            "msg": f"搜索请求异常: {str(e)}",
            "data": None
        }, status=500)


async def handle_model_folders(request: web.Request) -> web.Response:
    """Return available folders and subfolders on the server for a model type."""
    model_type = (request.query.get("type") or "LORA").upper()
    cat_keys = ["loras"]
    if "CHECKPOINT" in model_type:
        cat_keys = ["checkpoints"]
    elif "UNET" in model_type or "DIFFUSION" in model_type or "GGUF" in model_type:
        cat_keys = ["diffusion_models", "unet", "checkpoints"]

    results = []
    seen = set()

    for cat in cat_keys:
        roots = []
        if folder_paths is not None:
            try:
                roots = folder_paths.get_folder_paths(cat) or []
            except Exception:
                roots = []
        if not roots:
            # Fallback relative directory check
            rel_path = os.path.join(".", "models", cat)
            if os.path.isdir(rel_path):
                roots = [os.path.abspath(rel_path)]

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

    if not results:
        default_cat = "loras" if "LORA" in model_type else ("checkpoints" if "CHECKPOINT" in model_type else "unet")
        results.append({
            "name": default_cat,
            "root": "",
            "subfolder": ""
        })

    return web.json_response({
        "code": 0,
        "msg": "success",
        "data": {
            "folders": results
        }
    })


async def _do_lm_download(port: int, lm_payload: dict) -> dict:
    lm_download_url = f"http://127.0.0.1:{port}/api/lm/download-model"
    timeout_client = ClientTimeout(total=7200) # Allow up to 2 hours for large downloads
    try:
        async with ClientSession(timeout=timeout_client) as session:
            async with session.post(lm_download_url, json=lm_payload) as resp:
                if resp.status == 200:
                    res_data = await resp.json()
                    return {"code": 0, "msg": "下载任务已添加至电脑后台队列", "data": res_data}
                elif resp.status == 404:
                    return {"code": 404, "msg": "未检测到 ComfyUI-Lora-Manager 下载服务，请确认已安装此扩展插件"}
                else:
                    err_body = await resp.text()
                    clean_msg = err_body
                    try:
                        err_json = json.loads(err_body)
                        if isinstance(err_json, dict):
                            clean_msg = err_json.get("error") or err_json.get("message") or err_body
                    except Exception:
                        pass
                    # Translate common Lora-Manager messages to friendly Chinese
                    if "already exists in lora library" in clean_msg:
                        clean_msg = "该模型已存在于本地 LoRA 库中，无需重复下载"
                    elif "already exists in checkpoint library" in clean_msg:
                        clean_msg = "该模型已存在于本地大模型库中，无需重复下载"
                    elif "requires purchase" in clean_msg or "Early Access" in clean_msg:
                        clean_msg = "该模型在 Civitai 需要购买或抢先体验权限，无法直接下载"
                    elif "Failed to fetch model metadata" in clean_msg:
                        clean_msg = "无法从 Civitai 获取模型信息，请检查网络或配置 Civitai API Key"

                    logger.warning(f"[ComfyUI-Mobile-Bridge] Lora-Manager download response ({resp.status}): {clean_msg}")
                    return {"code": resp.status, "msg": f"下载提交失败: {clean_msg}"}
    except Exception as e:
        logger.error(f"[ComfyUI-Mobile-Bridge] Download forward error: {e}")
        return {"code": 500, "msg": f"无法连接到本地 Lora-Manager 下载服务: {str(e)}"}



def _normalize_civitai_file_type(file_type: str) -> str:
    return str(file_type or "").strip().lower().replace("_", " ").replace("-", " ")


def _is_component_file_type(file_type: str) -> bool:
    t = _normalize_civitai_file_type(file_type)
    return t in ("vae", "text encoder", "textencoder") or t.endswith(" text encoder")


def _resolve_component_root(file_type: str, file_role: str = "") -> str:
    """Map a Civitai component file type to a ComfyUI model folder (first root)."""
    t = _normalize_civitai_file_type(file_type)
    role = (file_role or "").strip().lower()
    if t == "vae":
        keys = ["vae"]
    elif t in ("text encoder", "textencoder") or "text encoder" in t:
        # Newer ComfyUI: text_encoders; older: clip
        keys = ["text_encoders", "clip"]
    elif role == "component":
        # role alone cannot choose vae vs TE — caller should pass fileType
        return ""
    else:
        return ""

    if folder_paths is not None:
        for key in keys:
            try:
                roots = folder_paths.get_folder_paths(key) or []
            except Exception:
                roots = []
            for r in roots:
                if r:
                    try:
                        os.makedirs(r, exist_ok=True)
                    except Exception:
                        pass
                    logger.info(f"[ComfyUI-Mobile-Bridge] component root via folder_paths[{key}] -> {r}")
                    return os.path.normpath(r)

    # Fallback: <ComfyUI>/models/<key>
    try:
        base = None
        if folder_paths is not None:
            base = getattr(folder_paths, "models_dir", None)
        if not base:
            # Prefer bridge's ComfyUI root (custom_nodes/../..) over process cwd
            here = os.path.dirname(os.path.abspath(__file__))
            guess = os.path.abspath(os.path.join(here, "..", "..", "models"))
            base = guess if os.path.isdir(os.path.dirname(guess)) else os.path.join(os.getcwd(), "models")
        target = os.path.join(base, keys[0])
        os.makedirs(target, exist_ok=True)
        logger.info(f"[ComfyUI-Mobile-Bridge] component root fallback -> {target}")
        return os.path.normpath(target)
    except Exception as e:
        logger.warning(f"[ComfyUI-Mobile-Bridge] failed to resolve component root: {e}")
        return ""


async def handle_civitai_download(request: web.Request) -> web.Response:
    """Trigger background model download via ComfyUI-Lora-Manager."""
    port = _get_server_port()
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"code": 400, "msg": "无效的 JSON 请求体"}, status=400)

    model_id = body.get("modelId") or body.get("model_id")
    model_version_id = body.get("modelVersionId") or body.get("model_version_id")
    model_type = (body.get("modelType") or body.get("model_type") or "LORA").upper()
    model_root = body.get("modelRoot") or body.get("model_root") or ""
    subfolder = body.get("subfolder") or body.get("relative_path") or ""
    # Optional Civitai file id for multi-file / quantization selection (LM file_params.id)
    file_id = body.get("fileId") or body.get("file_id")
    file_download_url = body.get("downloadUrl") or body.get("download_url") or ""
    file_type = str(body.get("fileType") or body.get("file_type") or "").strip()
    file_role = str(body.get("role") or "").strip().lower()

    if not model_id or not model_version_id:
        return web.json_response({"code": 400, "msg": "缺少必要参数 modelId 或 modelVersionId"}, status=400)

    # If model_root is not provided, resolve fallback root from folder_paths
    if not model_root and folder_paths is not None:
        cat = "loras"
        if "CHECKPOINT" in model_type:
            cat = "checkpoints"
        elif "UNET" in model_type or "DIFFUSION" in model_type or "GGUF" in model_type:
            cat = "unet"
        try:
            roots = folder_paths.get_folder_paths(cat) or []
            if not roots and cat == "unet":
                roots = folder_paths.get_folder_paths("diffusion_models") or folder_paths.get_folder_paths("checkpoints") or []
            if roots:
                model_root = roots[0]
        except Exception as e:
            logger.warning(f"Failed to resolve folder_paths: {e}")

    # Required components: auto-resolve ONLY when client did not pass modelRoot.
    # Explicit modelRoot (user-picked folder) must be respected.
    want_component = _is_component_file_type(file_type) or file_role == "component"
    if want_component and not str(model_root or "").strip():
        component_root = _resolve_component_root(file_type, file_role)
        if not component_root:
            return web.json_response(
                {
                    "code": 500,
                    "msg": f"无法解析必需组件保存目录（fileType={file_type or '空'}）。请确认 ComfyUI 已注册 vae / text_encoders（或 clip）目录后重试。",
                },
                status=500,
            )
        model_root = component_root
        subfolder = ""
        logger.info(
            f"[ComfyUI-Mobile-Bridge] component default root={model_root} type={file_type} role={file_role}"
        )
    elif want_component and str(model_root or "").strip():
        logger.info(
            f"[ComfyUI-Mobile-Bridge] component using client model_root={model_root} type={file_type}"
        )

    # Forward to Lora-Manager download API with explicit folder targets.
    # LM selects a concrete file via file_params.id (Civitai file id).
    lm_payload = {
        "model_id": int(model_id),
        "model_version_id": int(model_version_id),
        "use_default_paths": False
    }
    if model_root:
        lm_payload["model_root"] = str(model_root)
        lm_payload["use_save_dir_as_root"] = True
    if subfolder:
        lm_payload["relative_path"] = str(subfolder)
    if file_id is not None and str(file_id).strip() != "":
        try:
            lm_payload["file_params"] = {"id": int(file_id)}
        except (TypeError, ValueError):
            lm_payload["file_params"] = {"id": file_id}
        # Also pass Civitai file type so LM file matching prefers VAE/TE when id alone is ambiguous
        if file_type:
            lm_payload["file_params"]["type"] = str(file_type)
        # Keep download URL as diagnostic hint (LM resolves by id; URL unused by LM)
        if file_download_url:
            lm_payload["file_params"]["downloadUrl"] = str(file_download_url)

    # Launch download in background task that stays alive
    bg_task = asyncio.create_task(_do_lm_download(port, lm_payload))

    # Wait up to 2.0s to check for immediate validation errors (e.g. model already exists)
    done, _ = await asyncio.wait([bg_task], timeout=2.0)

    if bg_task in done:
        result = bg_task.result()
        return web.json_response(result, status=result.get("code") if result.get("code", 0) != 0 else 200)
    else:
        # Download is running in background successfully
        return web.json_response({
            "code": 0,
            "msg": "下载任务已添加至电脑后台队列并正在下载"
        })


async def handle_civitai_tasks(request: web.Request) -> web.Response:
    """Fetch current download queue and stats from ComfyUI-Lora-Manager."""
    port = _get_server_port()
    queue_url = f"http://127.0.0.1:{port}/api/lm/downloads/queue"
    stats_url = f"http://127.0.0.1:{port}/api/lm/downloads/stats"

    queue_data = []
    stats_data = {}

    try:
        async with ClientSession() as session:
            async with session.get(queue_url, timeout=2.0) as q_resp:
                if q_resp.status == 200:
                    queue_data = await q_resp.json()
            async with session.get(stats_url, timeout=2.0) as s_resp:
                if s_resp.status == 200:
                    stats_data = await s_resp.json()
    except Exception as e:
        logger.debug(f"Could not fetch download queue/stats: {e}")

    return web.json_response({
        "code": 0,
        "msg": "success",
        "data": {
            "queue": queue_data if isinstance(queue_data, list) else (queue_data.get("items") or []),
            "stats": stats_data
        }
    })


async def handle_civitai_task_action(request: web.Request) -> web.Response:
    """Control download tasks (pause / resume / cancel) in ComfyUI-Lora-Manager."""
    port = _get_server_port()
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"code": 400, "msg": "无效请求"}, status=400)

    action = str(body.get("action") or "").lower()
    download_id = str(body.get("downloadId") or "")
    if not download_id:
        return web.json_response({"code": 400, "msg": "缺少 downloadId 参数"}, status=400)

    action_map = {
        "pause": f"http://127.0.0.1:{port}/api/lm/pause-download?download_id={download_id}",
        "resume": f"http://127.0.0.1:{port}/api/lm/resume-download?download_id={download_id}",
        "cancel": f"http://127.0.0.1:{port}/api/lm/cancel-download-get?download_id={download_id}"
    }

    target_url = action_map.get(action)
    if not target_url:
        return web.json_response({"code": 400, "msg": f"不支持的操作: {action}"}, status=400)

    try:
        async with ClientSession() as session:
            async with session.get(target_url, timeout=3.0) as resp:
                return web.json_response({
                    "code": 0 if resp.status == 200 else resp.status,
                    "msg": "操作执行成功" if resp.status == 200 else "操作执行失败"
                })
    except Exception as e:
        return web.json_response({"code": 500, "msg": str(e)}, status=500)


# Register routes with ComfyUI server
if PromptServer is not None:
    try:
        app = PromptServer.instance.app
        app.router.add_get("/api/mobile/status", handle_mobile_status)
        app.router.add_get("/api/mobile/preview", handle_mobile_preview)
        app.router.add_get("/api/mobile/resource/list", handle_resource_list)
        app.router.add_post("/api/mobile/resource/list", handle_resource_list)
        app.router.add_get("/api/mobile/system/assets_summary", handle_assets_summary)
        app.router.add_get("/api/mobile/civitai/search", handle_civitai_search)
        app.router.add_get("/api/mobile/civitai/image-proxy", handle_civitai_image_proxy)
        app.router.add_get("/api/mobile/model-folders", handle_model_folders)
        app.router.add_post("/api/mobile/civitai/download", handle_civitai_download)
        app.router.add_get("/api/mobile/civitai/tasks", handle_civitai_tasks)
        app.router.add_post("/api/mobile/civitai/task-action", handle_civitai_task_action)
        logger.info("[ComfyUI-Mobile-Bridge] Mobile endpoints registered successfully at /api/mobile/*")
    except Exception as err:
        logger.warning(f"[ComfyUI-Mobile-Bridge] Failed to register mobile endpoints: {err}")

NODE_CLASS_MAPPINGS = {}
NODE_DISPLAY_NAME_MAPPINGS = {}
