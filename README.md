# ComfyPocket

Android 端 ComfyUI 控制客户端，包含本地 ComfyUI 与 RunningHub 后端、
工作流参数编辑、任务记录、Agent、图库和模型管理。

## 目录

- `app-reconstructed/`：可构建的 Android 工程。
- `app-reconstructed/ComfyUI-Mobile-Bridge/`：独立的 Python 服务端桥接插件，不打入 APK。
- `app-reconstructed/BUILD.md`：重建工程的构建版本与说明。
- `app-reconstructed/REFACTOR.md`：2026-10-17 的 ViewModel 职责拆分说明。
- `app-reconstructed/MARKET.md`：手机直连集市、代理、Key、缓存和下载队列说明。
- `app-reconstructed/AGENT.md`：Agent 第一阶段执行内核与思考强度档位说明。
- `app-reconstructed/TAG_LIBRARY.md`：内置词库来源、查询边界与可复现构建方式。
- `app-reconstructed/ComfyUI-Mobile-Bridge/README.md`：配套插件安装和 ComfyUI 设置页配置。

## Civitai 集市

手机可独立直连 Civitai，支持自定义 HTTP 代理和 Key，查看模型简介、触发词、
示例图正反提示词。模型安装仍下发给 ComfyUI 电脑。

配套 Bridge 2.0 已去除 Lora Manager 依赖，使用原生校验下载队列。
电脑 Key 在 **ComfyUI 设置 → Mobile Bridge → Civitai** 配置，支持保存、测试和清除，
不从手机偷偷转发凭据。从旧单文件插件升级请替换整个目录并重启 ComfyUI。

## 构建

需要 JDK 17、Android SDK 和已接受的 SDK 许可；本地 Android/Compose 页面测试
另需安装 JDK 21（测试任务使用独立 toolchain）。设置 `JAVA_HOME`、
`ANDROID_HOME`。工程使用 Gradle Wrapper；Android 平台为 API 37.0，
应用最低支持 Android 13（API 33）。

Windows 命令提示符：

```bat
cd app-reconstructed
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain
```

Linux / macOS：

```sh
cd app-reconstructed
./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain
```

调试 APK 输出到 `app-reconstructed/app/build/outputs/apk/debug/app-debug.apk`。
安装包名为 `com.comfyui.mobile.rebuilt`，源码 namespace 为 `com.comfyui.mobile`。
服务地址和 API 密钥在应用中配置，不应提交到仓库。

## 当前验证状态

- `0.0.7-agent-bilingual-tags`（versionCode 9）：内置词库扩充为 140,871 个标签与 99,980 个角色，
  共 240,851 项，均有中文对应。合并 Danbooru 社区中英对照固定快照，支持中文整句 / 别名查英文标签，
  不把纯英文回显计入中文覆盖。含机器翻译、画师和作品数据，来源、许可与过滤范围见 `app-reconstructed/TAG_LIBRARY.md`。
  删除聊天里的词库命中展开提示，保留离线检索与设置页查词；正反向流程不变。
- `0.0.6-agent-local-tags`（versionCode 8）：撤掉 Danbooru 联网查词，内置 13,358 个普通词、
  34,122 个角色条目，支持中英文/别名搜索。绘图前离线取候选，不增加模型修订轮次。
  Agent 主动编写正向和反向；工作流可分别选择精确文本字段；“出图提示词”同时显示、
  分别复制和成对保存两组词，明确实际写入状态。用户自定义人设与分镜规则保留。
- 保留此前的 CPU 进度条同色、提示词职责分离、Tags / Anima 混合 / 自然语言格式、
  反向缺失保留原值和显式空字符串清空行为；更新本版仅需 APK，不必更新 Bridge。

- `0.0.3-agent-media-monitor`（versionCode 5）修复生成通知静默、Agent 设置保存反馈、
  分享和画廊的原图缓存复用；主页新增 CPU / 内存预览，配套 Bridge 2.1，详见 `app-reconstructed/AGENT.md`。
- `0.0.2-agent-core`（versionCode 4）落地 Agent 实例级会话、分步执行和工作流工具；
  思考强度包括极高 `xhigh` 和 `max`，随模型配置保存，详见 `app-reconstructed/AGENT.md`。
- 当前 Android 构建成功，323 项 JVM/Android 页面测试和 3 项双语词库导入测试通过（本批基线 320 项）。
  上批另有 3 项 Bridge CPU/内存采样测试。Bridge Python、
  设置 GUI 与本地浏览器的独立测试说明见插件文档，本批未改动插件。
- 主 ViewModel 首轮从 3,867 行拆至 3,161 行，抽出工作流解析、编辑状态、
  参数持久化、日志和缓存管理五个组件。
- 本地补丁回放和回滚演练通过；APK v2 签名校验通过。
- 使用调试签名，尚未做真机 UI 或真实生成服务端到端测试。
- `0.0.1-market-return-fix`（versionCode 3）修复诊断定位到的集市退出时
  主线程关闭 TLS 连接崩溃，并处理保存代理/Key 时的同类清理路径。
  暖 HTTPS 连接、页面销毁后清理和客户端轮换已有回归；仍待用户手机复测。
  保留“设置 → 导出返回/退出诊断”，流程和验证边界见 `app-reconstructed/MARKET.md`。
- 原有常规 ComfyUI 连接的证书策略未改；Civitai 直连使用独立正常 TLS 客户端。
  Bridge 原生下载重新验证文件与注册目录，图片代理使用精确域名边界。

本仓库基于提供的源码快照重建构建配置并做职责拆分，不代表已恢复原始发布工程。
本机 SDK 路径、缓存、执行记录、原始备份和 APK 交付文件均排除在 Git 之外。
