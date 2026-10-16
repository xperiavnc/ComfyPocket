import { app } from "../../scripts/app.js";
import { api } from "../../scripts/api.js";
import { renderCivitaiSettings } from "./settings_panel.mjs";

// The custom renderer deliberately never calls ComfyUI's value setter.
// The only public setting value is an empty string, not a password or masked key.
app.registerExtension({
    name: "ComfyPocket.MobileBridge.Civitai",
    settings: [{
        id: "MobileBridge.Civitai.ServerCredentials",
        category: ["Mobile Bridge", "Civitai", "电脑下载凭据"],
        name: "Civitai Key（电脑端下载）",
        tooltip: "用于手机下发的模型下载任务。保存在电脑端，不发送回手机或浏览器。",
        type: () => renderCivitaiSettings(api),
        defaultValue: "",
        telemetry: { trackChanges: false },
    }],
});
