import assert from "node:assert/strict";
import { test } from "node:test";
import { renderCivitaiSettings } from "../web/settings_panel.mjs";

class Element {
    constructor(tag) {
        this.tagName = tag;
        this.children = [];
        this.style = {};
        this.events = {};
        this.attributes = {};
        this.value = "";
        this.disabled = false;
        this.textContent = "";
    }
    setAttribute(key, value) { this.attributes[key] = value; }
    append(...elements) { this.children.push(...elements); }
    addEventListener(event, handler) { this.events[event] = handler; }
    async click() { if (!this.disabled) await this.events.click(); }
}

const document = { createElement: tag => new Element(tag) };
const statusData = (configured = false) => ({
    configured, source: configured ? "settings" : "none", unreadable: false,
});
const json = (data, msg = "success", code = 0) =>
    new Response(JSON.stringify({ code, msg, data }), { status: code || 200 });
const tick = () => new Promise(resolve => setImmediate(resolve));

async function fixture(handler) {
    const calls = [];
    const api = {
        async fetchApi(url, options) {
            calls.push({ url, ...options });
            return handler ? handler(url, options, calls) : json(statusData());
        },
    };
    const panel = renderCivitaiSettings(api, document);
    await tick();
    const [status, input, controls] = panel.children;
    return { panel, status, input, buttons: controls.children, calls };
}

test("uses a write-only password control; status contains no stored secret", async () => {
    const f = await fixture(() => json(statusData(true)));
    assert.equal(f.input.type, "password");
    assert.equal(f.input.autocomplete, "off");
    assert.equal(f.input.value, "");
    assert.match(f.status.textContent, /已配置/);
    assert.equal(f.calls[0].headers["X-Comfy-Mobile-Settings"], "1");
    assert.equal(f.calls[0].cache, "no-store");
});

test("explicit save posts key only to the server route and clears input", async () => {
    const f = await fixture((url, options) =>
        json(statusData(options.method === "POST"), "saved"));
    f.input.value = "FIXTURE_KEY";
    await f.buttons[0].click();
    assert.equal(f.calls.length, 2);
    assert.equal(f.calls[1].url, "/api/mobile/civitai/settings");
    assert.deepEqual(JSON.parse(f.calls[1].body), { action: "save", key: "FIXTURE_KEY" });
    assert.equal(f.input.value, "");
    assert.doesNotMatch(f.status.textContent, /FIXTURE_KEY/);
});

test("empty save preserves the server configuration without a POST", async () => {
    const f = await fixture();
    await f.buttons[0].click();
    assert.equal(f.calls.length, 1);
    assert.match(f.status.textContent, /保持不变/);
});

test("test with a draft does not save or clear the draft", async () => {
    const f = await fixture();
    f.input.value = "DRAFT_KEY";
    await f.buttons[1].click();
    assert.deepEqual(JSON.parse(f.calls[1].body), { action: "test", key: "DRAFT_KEY" });
    assert.equal(f.input.value, "DRAFT_KEY");
});

test("test with empty input uses existing server key without reading it", async () => {
    const f = await fixture(() => json(statusData(true)));
    await f.buttons[1].click();
    assert.deepEqual(JSON.parse(f.calls[1].body), { action: "test" });
    assert.equal(f.input.value, "");
});

test("clear is explicit and does not send an unsaved draft", async () => {
    const f = await fixture();
    f.input.value = "DISCARDED_DRAFT";
    await f.buttons[2].click();
    assert.deepEqual(JSON.parse(f.calls[1].body), { action: "clear" });
    assert.equal(f.input.value, "");
});

test("errors containing the submitted key are redacted", async () => {
    const f = await fixture((url, options) => options.method === "GET"
        ? json(statusData()) : json(null, "problem FIXTURE_KEY", 403));
    f.input.value = "FIXTURE_KEY";
    await f.buttons[0].click();
    assert.doesNotMatch(f.status.textContent, /FIXTURE_KEY/);
    assert.equal(f.input.value, "FIXTURE_KEY");
    assert.equal(f.buttons[0].disabled, false);
});

test("concurrent clicks are disabled during a pending request", async () => {
    let resolvePost;
    const f = await fixture((url, options) => options.method === "GET"
        ? json(statusData()) : new Promise(resolve => { resolvePost = resolve; }));
    f.input.value = "FIXTURE_KEY";
    const pending = f.buttons[0].click();
    await tick();
    assert.equal(f.input.disabled, true);
    assert.ok(f.buttons.every(button => button.disabled));
    await f.buttons[0].click();
    assert.equal(f.calls.length, 2);
    resolvePost(json(statusData(true)));
    await pending;
    assert.equal(f.input.disabled, false);
});

test("missing old-plugin endpoint gives a useful error and enables controls", async () => {
    const f = await fixture(() => new Response("<html>missing</html>", { status: 404 }));
    assert.match(f.status.textContent, /Bridge 已更新/);
    assert.ok(f.buttons.every(button => !button.disabled));
});

test("credentials never call a ComfyUI settings value setter", async () => {
    // The registration uses a zero-argument custom renderer and a permanently empty
    // public value; not a text setting with onChange or the ComfyUI persistence API.
    const fs = await import("node:fs/promises");
    const source = await fs.readFile(new URL("../web/settings.js", import.meta.url), "utf8");
    assert.match(source, /defaultValue: ""/);
    assert.match(source, /type: \(\) => renderCivitaiSettings\(api\)/);
    assert.doesNotMatch(source, /onChange:|setting\.set\(|setSettingValue\(/);
});
