# 重建的 Android 工程

本项目修改自 [HAIGC/ComfyUI-APP](https://github.com/HAIGC/ComfyUI-APP)。
本目录由提供的模块源码快照重建，不是上游项目的完整构建配置。
原始 `src/` 放在 `app/src/`，Python 插件保持为独立目录。
初始重建保留 Kotlin、Python、Manifest 和资源内容；后续版本的功能改动与验证记录见根目录 README 及本目录功能说明。

## 选用的构建版本

| 项目 | 版本 / 值 |
| --- | --- |
| Java | APK 编译目标 17；本地 Android/Compose 集成测试 JVM 21 |
| Gradle Wrapper | 9.4.1，配置了官方分发包 SHA-256 |
| Android Gradle Plugin | 9.2.1 |
| Kotlin / Compose Compiler | 2.4.0，使用 AGP 内置 Kotlin |
| KSP | 2.3.12；生成 Agent 工作区 Room 实现 |
| Room Runtime / KTX / Compiler | 2.8.5；schema 保存在 `app/schemas/` |
| compileSdk | 37（SDK 包名 `platforms;android-37.0`） |
| targetSdk | 36 |
| minSdk | 33，即 Android 13 及以上 |
| Miuix UI / Preference / Icons / Blur | 0.9.3 |
| Compose Multiplatform 依赖线 | 1.11.1，Android 构建解析对应 AndroidX 变体 |
| Activity / Lifecycle | 1.13.0 / 2.10.0 |
| Navigation / NavigationEvent | 2.9.5 / 1.1.2 |
| Coil | 2.7.0 |
| Material 3 / Icons Core（独立样板） | 1.4.0 / 1.7.8 |
| OkHttp / Retrofit / Gson | 4.12.0 / 2.12.0 / 2.11.0 |
| Coroutines | 1.10.2 |

这些版本用于复现当前源码的接口，不声称与丢失的原始版本清单相同。
当前构建版本名为 `0.0.45-agent-led-questions`，版本号为 `47`。显示名 **ComfyPocket**；
模型中心的手机直连资料 / 代理预览与详情说明见 `MODEL_CENTER.md`，Agent 见 `AGENT.md`，
在线画廊见 `DANBOORU_GALLERY_PLAN.md`，底栏与动画说明见 `MATERIAL.md`。
安装包名（applicationId）改为 `com.comfyui.mobile.rebuilt`，与原版区分；
源码 namespace 和类名仍为 `com.comfyui.mobile`，FileProvider 标识随安装包名生成。

## Windows 构建

准备 JDK 17、Android SDK 和已接受的 SDK 许可，设置 `JAVA_HOME`、`ANDROID_HOME`。
SDK 需要 API 37.0 平台；Gradle 会按 Android 插件要求使用相应构建工具。
也可以在未纳入分发包的 `local.properties` 中填写本机 `sdk.dir`。
本地 Android/Compose 集成测试另需已安装或配置给 Gradle 的 JDK 21；
Miuix 的 Android 类以 Java 21 编译，在 JVM 17 测试进程中加载会报类版本错误。
`:app:assembleDebug` 的编译目标不变，测试任务单独使用 Java 21 toolchain。

```bat
rem 在工程根目录使用 cmd.exe：
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "ANDROID_HOME=C:\Android\Sdk"
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain
```

输出：

- 品牌命名调试 APK：`app/build/outputs/ComfyPocket/ComfyPocket-<versionName>.apk`（assembleDebug 自动导出）
- AGP 内部产物：`app/build/outputs/apk/debug/app-debug.apk`（不修改其元数据）
- 单元测试 HTML：`app/build/reports/tests/testDebugUnitTest/index.html`
- 单元测试 XML：`app/build/test-results/testDebugUnitTest/`

## 重要限制

- 这是调试构建，使用构建机器的 Android debug 签名，没有配置正式发布签名。
- 同包名但不同签名的应用在覆盖安装时可能遭到系统拦截；不要为安装而未经备份卸载原应用。
- 构建和 JVM 单元测试不等于真机端到端验证。本轮不连接实际生成服务、不自动安装 APK。
- Python 桥接插件不打入 APK；需作为服务端插件单独部署。
- `local.properties`、构建缓存、测试产物和 Python 缓存不纳入源码分发包。
- `0.0.43` 启用 Agent 单主会话的 Room 存储与按作用域迁移，保留迁移前旧键和原附件。
  成功后新记录只写 Room；旧 APK 只看到迁移前快照，降级安装不等于完整数据回退。
  `.cui.bak` 支持工作区和引用附件，导入替换需勾选确认并保留原快照；
  不打包生成媒体本体。项目 / 多会话页面留待 P2 / P3，详见 `AGENT_WORKSPACE_PLAN.md`。
- `0.0.44` 增加主动提问、等待回答与原任务续接，Chat 和绘图均支持单选、多选、填写题。
  提问不改工作流或提交；旧 `should_draw=false` 覆盖和普通问答沿用旧提示词的问题已修正。
- `0.0.45` 删除角色 / 服装 / 词库的本地强制提问与回复关键词判定，候选交给 AI 自主判断。
  只有模型的显式结构化提问进入等待，保留原任务续接；普通文字不自动生成表单或回收旧图。
- 本版 debug APK 构建及 118 套 / 856 项 JVM、Compose、真实 ViewModel / Room 集成测试通过；
  APK 版本与签名检查见本地验证记录。未进行手机安装、真机升级或真实服务出图验证。

默认 APK 不携带离线词库。运行 `python tools/pack_local_tag_library.py` 可生成独立 ZIP，
在 Agent 设置中导入；完整资源、中文对照与旧版已初始化数据保留。来源与操作见 `TAG_LIBRARY.md`。Agent 不依赖 Danbooru 联网查词；新增在线画廊为完全独立、用户主动访问的模块。
