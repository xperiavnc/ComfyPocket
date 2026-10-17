# ComfyUI-Mobile-Bridge 2.1

独立的 ComfyPocket 服务端插件。直接读取 ComfyUI 模型目录，并提供 Civitai
桥接浏览、原生模型下载和 ComfyUI 设置页。**不需要 Lora Manager。**
ComfyUI-Manager 仅用于可选的已装插件列表查询，不参与模型下载。

## 安装 / 从单文件版升级

1. 停止 ComfyUI，备份原 `custom_nodes/ComfyUI-Mobile-Bridge` 目录。
2. 将此处的**整个目录**放到 `ComfyUI/custom_nodes/ComfyUI-Mobile-Bridge/`。
   保持 `__init__.py`、配套 Python 模块及 `web/` 的相对位置；不再是只覆盖
   `__init__.py` 即可。避免双层目录，也不要同时保留启用状态的旧插件副本。
3. 启动 ComfyUI，刷新网页（必要时强制刷新），打开：
   **设置 → Mobile Bridge → Civitai → Civitai Key（电脑端下载）**。
4. 粘贴原始 API Key，点击“保存 Key”，再点击“测试 Key / 电脑网络”。
   无需填写 `Bearer`。保存后新任务立即生效，不必再次重启。
5. 使用配套的新版 ComfyPocket 下发下载。在集市右上角打开“电脑下载队列”，
   可查看进度、暂停、继续或取消。

运行依赖是 ComfyUI 已有的 `aiohttp`；CPU/内存采样使用 ComfyUI 已有的 `psutil`，不新增模型管理器依赖。
缺少 psutil 时监控接口返回缺失值，不妨碍模型管理与下载。

## 两端 Key 和网络的区别

- **手机直连**：手机集市中的 Key、HTTP 代理只控制手机访问。
- **电脑桥接 / 远程下载**：使用本插件设置页保存的电脑 Key，以及电脑网络。
  手机 Key 不通过 ComfyUI 连接自动上传。
- Key 只代表所属账号已有的权限；账号、内容级别、地区策略、购买和抢先体验
  权限仍由 Civitai 决定。配置 Key 不等于获得所有模型的下载权限。
- 保存失败不清空输入，空输入不覆盖已保存 Key。“测试”可测试当前输入但不保存；
  留空测试电脑上已保存的 Key。清除按钮只清除设置页保存的 Key。
- 优先级：**设置页保存的 Key > `CIVITAI_API_KEY` 环境变量 > 匿名访问**。
  如果环境变量存在，清除保存值后会退回环境变量，界面会显示当前来源。

电脑有代理时，可在启动 ComfyUI 的环境中设置 `CIVITAI_HTTP_PROXY`，
例如 `http://127.0.0.1:7890`；未设置时使用标准 `HTTPS_PROXY` / `HTTP_PROXY`
环境配置。更改环境变量后需要重启 ComfyUI。手机代理地址中的 `127.0.0.1`
指手机自身，不是电脑。

## 凭据存储和访问控制

- 存储位置：`folder_paths.get_user_directory()/mobile_bridge/civitai-settings.json`。
  不在插件静态资源目录、工作流或 ComfyUI 通用设置中。
- Windows 使用当前运行 ComfyUI 的 Windows 账号 DPAPI 加密；更换账号/机器后
  请重新保存 Key。其他系统使用权限 `0600` 的服务端文件，目录新建权限 `0700`，
  并不宣称具有跨平台系统密钥库加密。
- 浏览器仅获得“是否已配置 / 来源”，不会读回完整 Key 或其片段；成功保存后清空
  密码框。Key 不写入任务状态、下载 URL、日志或手机响应。
- Bearer 只发送到 `https://civitai.com` 的官方 API；逐跳处理重定向，
  不携带到下载 CDN，正常校验 TLS。
- 设置写接口检查自定义请求头与同源 Host，阻止跨站表单/请求。
  **这不替代 ComfyUI 登录或反向代理鉴权**：所有有权访问该 ComfyUI 服务的人
  均可能控制电脑下载或更新共享 Key。不要直接暴露未鉴权的 ComfyUI。
- HTTP 会明文传输新输入的 Key，限可信网络操作；跨网访问建议配置 HTTPS。

## 原生下载行为

- 从官方版本接口重新验证模型 ID、版本 ID、文件 ID、文件名和 SHA-256；
  忽略手机传入的任意下载 URL。默认选择主模型文件。
- 默认下载到 ComfyUI 对应的已注册目录；VAE、Text Encoder 优先自己的组件目录。
  用户显式选择的已注册根目录会保留，子目录限制在根目录内部。
- 最多同时下载 2 个文件，最多保留 50 个未结束任务、100 条任务记录。
- 写入专属 `.mobile-<任务ID>.part`；完整长度和官方 SHA-256 通过后发布正式文件，
  不覆盖已有模型。官方尚未提供 SHA-256 时提示稍后重试。
- 暂停保留断点；继续时重新获取官方文件信息，校验 Range / Content-Range。
  服务器忽略 Range 时从头下载，不拼接错误数据。
- 取消仅删除此任务临时文件，保留其他模型和已完成文件。失败后可点击继续；
  SHA-256 不匹配会删除坏的临时文件再重试。
- 任务持久化到同目录 `download-tasks.json`，没有 Key 和带签名下载 URL。
  ComfyUI 重启后未完成任务为“暂停”，需手动继续。

## API 兼容

- `GET /api/mobile/status`：版本 `2.1.0`，提供 `native-civitai-downloads`、
  `civitai-server-settings` 和 `system-monitor` 能力字段。
- `GET /api/mobile/system/monitor`：CPU 利用率（0–100%）、内存总字节数与已用字节数。
- `GET/POST /api/mobile/civitai/settings`：只由设置 GUI 使用；状态不返回 Key。
- `POST /api/mobile/civitai/native-download`
- `GET /api/mobile/civitai/native-tasks`
- `POST /api/mobile/civitai/native-task-action`

原 `/download`、`/tasks`、`/task-action` 路径作为原生实现别名保留，方便先升级插件。
新版手机只调用 `native-*`，遇到旧插件时提示升级，不再回退到模型管理器。
状态响应保留 `loraManagerInstalled: false` 作为旧客户端兼容字段，不进行依赖探测。

## 验证 / 回退

在含 `aiohttp` 的 Python 环境中：

```sh
python -m unittest discover -s tests -v
node --test tests/settings_panel.test.mjs
```

测试只下载回环服务生成的小文件，使用模拟 Key，不接触真实账号或模型。
可选运行 `python tests/serve_settings_fixture.py`，打开打印的本地地址验证密码框、
保存、测试和清除。该页面明确标记为本地渲染测试，不代表真实 Civitai 认证成功。

插件回退：停止 ComfyUI，换回第 1 步备份的完整插件目录，再启动并强制刷新网页。
保留服务端 `user/mobile_bridge` 配置以便升级后恢复；删除 Key 应使用设置页的清除按钮。
旧手机/插件的下载行为与新版不同，回退时保持版本配套。

## 2.0.1 分类筛选修正

- Checkpoint 与 UNet 使用各自官方类型，不再把 UNet 请求转换为 Checkpoint。
- GGUF 在 Checkpoint/UNet 内使用文件格式数组筛选，同时保留基础模型与不透明分页游标。
- 不设置 earlyAccess=true，普通列表不会被限制为仅抢先体验。
- 升级时替换整个插件目录并重启 ComfyUI；已有电脑 Key 存储不变。手机直连分类修正只需要更新 APK。

## 2.1 CPU / 内存预览

配套手机首页“系统状态”可显示 CPU 利用率和内存占用。采样仅在接口被请求时运行，
100 ms CPU 采样放在工作线程，不阻塞 ComfyUI 的 aiohttp 事件循环。内存已用量使用
`total - available`，与原生 `/system_stats` 的可用内存口径一致。

更新时替换完整插件目录并重启 ComfyUI；不能只替换 `__init__.py`，新增的
`system_monitor.py` 必须一并部署。手机内存预览仅更新 APK 即可；没有 Bridge 2.1 或
Crystools 时 CPU 行显示“暂无数据”。不自动重启或中断现有的生成任务。

只运行本次监控回归：

```sh
python -m unittest discover -s tests -p test_system_monitor.py -v
```
