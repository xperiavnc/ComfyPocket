# ComfyPocket

Android 端 ComfyUI 控制客户端，包含本地 ComfyUI 与 RunningHub 后端、
工作流参数编辑、任务记录、Agent、图库和模型管理。

## 目录

- `app-reconstructed/`：可构建的 Android 工程。
- `app-reconstructed/ComfyUI-Mobile-Bridge/`：独立的 Python 服务端桥接插件，不打入 APK。
- `app-reconstructed/BUILD.md`：重建工程的构建版本与说明。
- `app-reconstructed/REFACTOR.md`：2026-10-17 的 ViewModel 职责拆分说明。
- `app-reconstructed/MARKET.md`：手机直连集市、代理、Key、缓存和下载队列说明。
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

- 当前 Android 构建成功，178 项 JVM/Android 页面测试通过；Bridge Python、
  设置 GUI 与本地浏览器的独立测试说明见插件文档，本批未改动插件。
- 主 ViewModel 从 3,867 行拆至 3,161 行，抽出工作流解析、编辑状态、
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
