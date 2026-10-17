"""Small, on-demand host telemetry. Sampling runs off the aiohttp event loop."""
from __future__ import annotations

import asyncio
from aiohttp import web

try:
    import psutil
except ImportError:
    psutil = None


def read_system_monitor() -> dict:
    if psutil is None:
        return {"cpu_percent": None, "ram_total": None, "ram_used": None}
    # An explicit sampling interval works on the very first request and across executor threads.
    cpu = psutil.cpu_percent(interval=0.1)
    memory = psutil.virtual_memory()
    return {
        "cpu_percent": float(cpu),
        "ram_total": int(memory.total),
        # Match ComfyUI's available/free definition rather than counting reclaimable caches as busy.
        "ram_used": max(0, int(memory.total) - int(memory.available)),
    }


async def handle_system_monitor(request: web.Request) -> web.Response:
    try:
        data = await asyncio.to_thread(read_system_monitor)
        return web.json_response({"code": 0, "msg": "success", "data": data})
    except (OSError, RuntimeError):
        return web.json_response({"code": 503, "msg": "系统资源采样暂不可用", "data": None}, status=503)
