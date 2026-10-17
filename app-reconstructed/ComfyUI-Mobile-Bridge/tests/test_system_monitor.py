import asyncio
import importlib.util
from pathlib import Path
import threading
import types
import unittest
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

spec = importlib.util.spec_from_file_location(
    "mobile_system_monitor", Path(__file__).resolve().parents[1] / "system_monitor.py")
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)


class SystemMonitorTests(unittest.IsolatedAsyncioTestCase):
    async def test_endpoint_samples_cpu_off_event_loop_and_returns_memory_bytes(self):
        main_thread = threading.get_ident()
        threads = []

        def cpu_percent(interval):
            self.assertEqual(0.1, interval)
            threads.append(threading.get_ident())
            return 26.5

        fake = types.SimpleNamespace(
            cpu_percent=cpu_percent,
            virtual_memory=lambda: types.SimpleNamespace(total=32 * 1024**3, available=20 * 1024**3))
        app = web.Application()
        app.router.add_get("/api/mobile/system/monitor", monitor.handle_system_monitor)
        async with TestClient(TestServer(app)) as client:
            with patch.object(monitor, "psutil", fake):
                response = await client.get("/api/mobile/system/monitor")
                self.assertEqual(200, response.status)
                body = await response.json()
        self.assertEqual(26.5, body["data"]["cpu_percent"])
        self.assertEqual(12 * 1024**3, body["data"]["ram_used"])
        self.assertEqual(32 * 1024**3, body["data"]["ram_total"])
        self.assertNotEqual(main_thread, threads[0])

    async def test_missing_psutil_is_not_reported_as_zero_percent(self):
        with patch.object(monitor, "psutil", None):
            sample = await asyncio.to_thread(monitor.read_system_monitor)
        self.assertIsNone(sample["cpu_percent"])
        self.assertIsNone(sample["ram_total"])

    async def test_real_sample_has_cpu_percent_and_valid_byte_counts(self):
        if monitor.psutil is None:
            self.skipTest("ComfyUI's psutil is not installed in this test environment")
        sample = await asyncio.to_thread(monitor.read_system_monitor)
        self.assertGreaterEqual(sample["cpu_percent"], 0)
        self.assertLessEqual(sample["cpu_percent"], 100)
        self.assertGreater(sample["ram_total"], 0)
        self.assertGreaterEqual(sample["ram_used"], 0)
        self.assertLessEqual(sample["ram_used"], sample["ram_total"])


if __name__ == "__main__":
    unittest.main()
