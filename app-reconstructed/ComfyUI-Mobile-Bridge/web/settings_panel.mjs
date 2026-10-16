/** Standalone DOM renderer for ComfyUI's custom setting type. */
export function renderCivitaiSettings(api, doc = document) {
    const panel = doc.createElement("div");
    panel.style.cssText = "display:flex;flex-direction:column;gap:10px;width:min(440px,70vw);padding:6px";

    const status = doc.createElement("div");
    status.setAttribute("role", "status");
    status.setAttribute("aria-live", "polite");
    status.style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px";
    const input = doc.createElement("input");
    input.type = "password";
    input.autocomplete = "off";
    input.spellcheck = false;
    input.maxLength = 514;
    input.placeholder = "粘贴新 Key；留空不更改已保存的 Key";
    input.setAttribute("aria-label", "新的 Civitai API Key");
    input.style.cssText = "width:100%;box-sizing:border-box;padding:9px;border-radius:6px;" +
        "background:var(--comfy-input-bg,#222);color:var(--input-text,#ddd);border:1px solid var(--border-color,#555)";
    const controls = doc.createElement("div");
    controls.style.cssText = "display:flex;flex-wrap:wrap;gap:8px";
    const note = doc.createElement("small");
    note.style.cssText = "line-height:1.5;opacity:.8;white-space:pre-wrap";
    note.textContent = "手机浏览用手机 Key，电脑下载用这里的 Key；Key 只代表对应账号已有的访问权限。\n" +
        "保存后新任务立即生效；失败/暂停的任务可从手机队列点击继续。\n" +
        "局域网 HTTP 会明文传输设置，请仅在可信网络操作，远程访问建议使用 HTTPS。";
    panel.append(status, input, controls, note);

    let busy = false;
    const buttons = [];
    const describe = (data) => {
        const sources = { settings: "设置页", environment: "CIVITAI_API_KEY 环境变量", none: "匿名访问" };
        return `${data.configured ? "已配置" : "未配置"} · ${sources[data.source] || "未知来源"}` +
            (data.unreadable ? "\n原凭据读取失败，可重新保存或清除。" : "");
    };
    const request = async (body) => {
        const options = {
            method: body ? "POST" : "GET",
            headers: { "X-Comfy-Mobile-Settings": "1" },
            cache: "no-store",
            signal: AbortSignal.timeout(45000),
        };
        if (body) {
            options.headers["Content-Type"] = "application/json";
            options.body = JSON.stringify(body);
        }
        const result = await api.fetchApi("/api/mobile/civitai/settings", options);
        let data;
        try { data = await result.json(); } catch {
            throw new Error("插件设置接口不可用，请更新 Bridge 并重启 ComfyUI、刷新网页。");
        }
        if (!result.ok || data.code !== 0) {
            throw new Error(data.msg || "设置请求失败，请检查电脑网络。");
        }
        return data;
    };
    const operate = async (action) => {
        if (busy) return;
        const key = input.value.trim();
        if (action === "save" && !key) {
            status.textContent = "Key 为空，当前设置保持不变。";
            return;
        }
        busy = true;
        input.disabled = true;
        buttons.forEach(button => { button.disabled = true; });
        status.textContent = action === "test" ? "正在由电脑连接 Civitai…" : "正在保存…";
        try {
            const body = { action };
            if (action !== "clear" && key) body.key = key;
            const result = await request(body);
            if (action !== "test") input.value = "";
            status.textContent = result.msg + "\n" + describe(result.data);
        } catch (error) {
            // Browser/network errors may contain a URL; never display the submitted key.
            const text = error instanceof Error ? error.message : "";
            status.textContent = key && text.includes(key)
                ? "设置请求失败，请检查电脑网络。" : text || "设置请求失败，请检查电脑网络。";
        } finally {
            busy = false;
            input.disabled = false;
            buttons.forEach(button => { button.disabled = false; });
        }
    };
    for (const [label, action] of [
        ["保存 Key", "save"], ["测试 Key / 电脑网络", "test"], ["清除已保存 Key", "clear"],
    ]) {
        const button = doc.createElement("button");
        button.type = "button";
        button.textContent = label;
        button.style.cssText = "padding:7px 10px;border:1px solid var(--border-color,#555);" +
            "border-radius:6px;background:var(--comfy-input-bg,#333);color:var(--input-text,#ddd);cursor:pointer";
        button.addEventListener("click", () => operate(action));
        buttons.push(button);
        controls.append(button);
    }
    status.textContent = "正在读取电脑 Key 配置状态…";
    // Initial status must not race a save made immediately after mounting.
    buttons.forEach(button => { button.disabled = true; });
    request().then(result => { status.textContent = describe(result.data); })
        .catch(() => { status.textContent = "读取失败，请确认 Bridge 已更新并重启 ComfyUI。"; })
        .finally(() => buttons.forEach(button => { button.disabled = false; }));
    return panel;
}
