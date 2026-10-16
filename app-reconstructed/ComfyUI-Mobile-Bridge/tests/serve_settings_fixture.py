"""Local browser smoke-test fixture, not a ComfyUI replacement or live deployment.

python tests/serve_settings_fixture.py
Open http://127.0.0.1:18189, use a synthetic key, Ctrl+C when done.
"""
import os
from pathlib import Path
import sys
import tempfile

from aiohttp import web

sys.path.insert(0, str(Path(__file__).parent))
from test_bridge import FakeFolders, PLUGIN, service_module


class FixtureNetwork:
    async def test(self, key=None):
        return "本地模拟 Key 测试通过（未请求 Civitai）"


def main():
    os.environ.pop("CIVITAI_API_KEY", None)
    temporary = tempfile.TemporaryDirectory()
    directory = Path(temporary.name)
    service = service_module.CivitaiService(
        FakeFolders(directory), directory / "private", client=FixtureNetwork())
    app = web.Application()
    service.register(app)

    async def index(request):
        return web.Response(text="""<!doctype html><meta charset="utf-8">
        <title>Mobile Bridge · 设置 GUI 本地验证</title>
        <link rel="icon" href="data:,">
        <style>
        body{font:15px system-ui;background:#202124;color:#eee;margin:32px}
        main{max-width:900px;margin:auto} section{display:flex;align-items:flex-start;
        gap:30px;flex-wrap:wrap;padding:24px;border:1px solid #555;border-radius:12px}
        h1{font-size:22px} h2{font-size:17px} #settings{display:flex;align-items:flex-start;gap:20px}
        </style><main><h1>Mobile Bridge → Civitai</h1>
        <p>本地设置渲染与接口测试页；不是已部署的 ComfyUI 设置页。</p>
        <section id="settings"></section></main>
        <script type="module" src="/extensions/MobileBridge/settings.js"></script>""",
                            content_type="text/html")

    async def app_module(request):
        return web.Response(text="""export const app = {
            registerExtension(extension) {
                window.fixtureExtension = extension;
                for (const setting of extension.settings) {
                    const title = document.createElement("h2");
                    title.textContent = setting.name;
                    const section = document.querySelector("#settings");
                    section.append(title, setting.type());
                }
            }
        };""", content_type="text/javascript")

    async def api_module(request):
        return web.Response(text="export const api = { fetchApi: (...args) => fetch(...args) };",
                            content_type="text/javascript")

    app.router.add_get("/", index)
    app.router.add_get("/scripts/app.js", app_module)
    app.router.add_get("/scripts/api.js", api_module)
    app.router.add_static("/extensions/MobileBridge/", PLUGIN / "web")

    async def cleanup(app):
        temporary.cleanup()
    app.on_cleanup.append(cleanup)
    web.run_app(app, host="127.0.0.1", port=18189)


if __name__ == "__main__":
    main()
