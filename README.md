<p align="center">
  <img src="app-reconstructed/app/src/main/res/drawable/app_logo.png" width="112" alt="ComfyPocket 图标">
</p>

<h1 align="center">ComfyPocket</h1>

<p align="center"><strong>把 ComfyUI 装进口袋，让手机成为你的创作控制台。</strong></p>
<p align="center">Android 13+ · 工作流控制 · AI Agent · 模型管理 · 原生画廊</p>

<p align="center">
  <a href="#主要功能">主要功能</a> ·
  <a href="#安装与运行">安装与运行</a> ·
  <a href="#使用指南">使用指南</a> ·
  <a href="#从源码构建">从源码构建</a> ·
  <a href="#文档导航">详细文档</a>
</p>

## 软件简介

**ComfyPocket** 是面向 Android 的 ComfyUI 控制客户端。你可以在手机上连接自己的
ComfyUI 服务或 RunningHub，编辑工作流参数、提交生成任务、查看生成结果，也可以用
Agent 通过对话编写提示词并调用已配置的工作流。

除了生成控制，应用还整合了本地模型管理、Civitai 在线集市、可选中英标签词库及
Danbooru 原生画廊，尽量让找模型、调参数、出图与浏览结果在同一处完成。

> **ComfyPocket 是控制客户端，不是在手机上运行绘图模型。**
> 生成任务由你连接的 ComfyUI 或 RunningHub 执行；Agent 使用你自行配置的模型 API。
> 离线可读取已保存的部分资料和缓存，实际生成仍需要相应服务。

## 主要功能

| 功能 | 说明 |
| --- | --- |
| **ComfyUI / RunningHub** | 支持本地 ComfyUI 与 RunningHub 后端，按当前服务配置提交任务、查看状态和结果。 |
| **工作流与节点控制** | 加载 JSON 工作流，编辑可用节点参数，管理常用配置；Agent 可分别绑定正向与反向文本字段。 |
| **AI Agent** | 支持对话、提示词编写与自动出图；可配置模型服务、思考强度、上下文预算、单次输出上限、人设和分镜规则。 |
| **角色档案与服装预设** | 保存角色名称、别名、外貌参考、固定绘画标签和服装预设，为对话绘图复用角色资料。 |
| **本地模型中心** | 浏览服务器模型、预览图、简介、触发词与中文标注；支持同步服务器文件的重命名、移动和删除。 |
| **Civitai 在线集市** | 手机直连浏览，支持搜索、作者查询、分类、排序、模型链接直达和详情查看，并可下发电脑下载。 |
| **画廊与媒体查看** | 浏览生成结果和 Danbooru 内容，支持图片全屏缩放、标签查看与复制，以及视频查看和下载。 |
| **可选中英词库** | 按需导入标签与角色词库，支持离线中英查询；默认 APK 不携带词库，不安装也可正常使用 Agent。 |
| **状态与外观** | 主页展示可用的 CPU / 内存等状态信息；支持主题配色及标准底栏、经典悬浮两种底栏风格。 |

## 界面概览

| 界面 | 主要用途 |
| --- | --- |
| **主页** | 查看连接和系统状态，进入本地模型、在线集市等功能。 |
| **控制** | 选择工作流、调整节点参数、提交任务并查看队列与结果。 |
| **应用** | 使用和管理常用工作流应用及其参数。 |
| **Agent** | 配置模型服务，用对话编写正反向提示词并调用工作流。 |
| **画廊** | 浏览图片与视频，查看 Danbooru 图片、排行榜和标签。 |
| **模型中心** | 在本地模型、在线集市和任务页面间切换，查看资料及下载进度。 |
| **设置** | 管理连接、主题、底栏外观及各功能配置。 |

## 安装与运行

### 环境要求

| 项目 | 要求 |
| --- | --- |
| 手机系统 | **Android 13（API 33）及以上**。 |
| 绘图后端 | 可从手机访问的 ComfyUI 服务，或你自己的 RunningHub 服务配置。 |
| 配套插件 | 完整使用模型中心、服务器文件管理和下载队列时，安装仓库内的 **ComfyUI-Mobile-Bridge 2.2.6**。 |
| Agent | 自行配置模型 API 地址、模型名称和所需 Key；支持的参数以实际服务商为准。 |
| Civitai / Danbooru | 按需配置各自账号、Key 和手机 HTTP 代理。 |

### 1. 安装 APK

在 Android 设备上安装项目提供的 **`ComfyPocket-<versionName>.apk`**，也可以按下文从源码构建。
文件名由 Gradle 读取版本配置自动生成，不使用固定的无版本文件名。

当前工程的调试构建使用 **debug 签名**，正式发布签名需要自行配置。
源码中的 Android namespace 为 `com.comfyui.mobile`，安装包名为
`com.comfyui.mobile.rebuilt`。

### 2. 连接绘图服务

1. 在电脑上启动 ComfyUI，确保手机与服务之间网络可达。
2. 先用手机浏览器打开服务地址，例如 `http://<电脑局域网 IP>:8188/`，确认可以访问。
3. 在应用的连接配置中填写同一个服务地址并连接；使用 RunningHub 时填写对应服务配置。
4. 加载工作流，检查模型文件和节点参数，再提交生成任务。

手机中的 `localhost` 或 `127.0.0.1` 指向手机自身，不代表你的电脑。
跨网络使用时，请同时做好 ComfyUI 服务的访问控制。

### 3. 安装配套 Bridge

需要模型中心完整联动或从手机下载模型到电脑时：

1. 停止 ComfyUI，将仓库内的 **整个** `app-reconstructed/ComfyUI-Mobile-Bridge/`
   目录复制到 `ComfyUI/custom_nodes/ComfyUI-Mobile-Bridge/`。
2. 保留 `__init__.py`、其余 Python 模块及 `web/` 的相对位置，避免双层目录或同时启用旧副本。
3. 启动 ComfyUI，刷新网页；需要受账号权限限制的模型时，在
   **ComfyUI 设置 → Mobile Bridge → Civitai** 中保存并测试电脑端 Key。
4. 返回手机，进入模型中心同步模型或下发下载，查看任务状态。

Bridge **不依赖 Lora Manager**，也不打包进 APK。更新时替换完整插件目录并重启 ComfyUI，
保留原有 `user/mobile_bridge/` 用户数据；只更新 APK 不会自动部署电脑插件。

详细安装、代理与升级说明见 [Bridge 文档](app-reconstructed/ComfyUI-Mobile-Bridge/README.md)。

## 使用指南

### Agent：从对话到出图

1. 打开 **Agent 设置 → 模型 API 配置**，配置服务地址、模型名称与 Key，保存并应用。
2. 按需设置思考强度、上下文预算和单次输出长度。这些设置不扩大服务商模型的实际能力。
3. 在 **选择工作流 → 设置节点** 中分别配置正向和反向提示词字段；反向也可选择保留工作流原值。
4. 按需登记角色资料和服装预设；对话未明确服装且有多个候选时，Agent 会先给出可点选项，不会混用或猜选。角色绘图常驻在 **画风与辅助参数** 中设置。只需要提示词时，可明确要求不提交生成。
5. 在“出图提示词”中查看正向、反向及实际写入状态，分别复制或成对保存。

角色识别名称、对话别名和绘画标签应分开填写。角色资料提供提示词参考，
**不等于已自动加载 LoRA**；实际模型和 LoRA 仍由工作流节点配置。
反向提示词支持空值，也会按配置保留原值，不强制拼接一套固定反向模板。

更多配置、角色规则与执行边界见 [Agent 文档](app-reconstructed/AGENT.md)。

#### Agent 工作区开发进度

`0.0.42-agent-workspace-storage` 完成 P1.1 存储基础：Room、旧数据迁移器、
执行 / 附件归属及可移植备份接口。**本版尚未启用自动迁移，也没有新增项目 / 多会话页面**；
现有 Agent 保持旧读写和原界面。下一批 P1.2 将统一接入生产读写、任务回调及 `.cui.bak`，
阶段状态与后续验收见 [工作区计划](app-reconstructed/AGENT_WORKSPACE_PLAN.md)。

### 模型中心与在线集市

- **本地模型**：同步 ComfyUI 注册目录，查看封面、模型简介、版本说明、触发词及人工中文标注。
  本地模型选择器复用同一份资料，确认时仍使用服务器原始完整文件路径。
- **文件管理**：重命名、移动和删除会实际修改电脑上的模型文件，并同步相关记录。
  删除为永久删除；文件名或位置改变后，已有工作流中的旧路径需要重新选择。
- **在线集市**：默认手机直连 Civitai，支持作者查询、分类和排序，以及 `.com / .red` 模型链接或编号直达。
  手机可配置独立 HTTP 代理和 Key，查看模型与版本简介、触发词、预览及可用的示例提示词。
- **下载任务**：模型文件由电脑上的 Bridge 下载；手机负责选择版本、目标目录及查看进度，
  支持暂停、继续和取消。

> **手机浏览与电脑下载是两套网络和凭据配置。**
> 手机 HTTP 代理及 Civitai Key 不会自动转发给电脑。手机能打开详情，不代表电脑已经具备下载网络和账号权限。
> 第三方 API 的检索范围与网页可能不同，排序和分页以接口实际返回为准。

详见 [模型中心](app-reconstructed/MODEL_CENTER.md) 与 [集市说明](app-reconstructed/MARKET.md)。

### 画廊与可选词库

- **Danbooru 画廊**：独立配置账号和手机代理，支持搜索、评级、热度等排序及日 / 周 / 月排行榜；
  点击图片查看标签或进入全屏，图片可缩放，支持的视频可查看及下载。
- **本地词库**：默认不随 APK 安装。需要时获取 `ComfyPocket-tag-library.zip`，
  在 **Agent 设置 → 可选资源 → 本地词库 → 导入词库** 中安装，也可更新或移除。
  离线查词结果可长按选取，也能分别复制英文标签 / 角色身份与中文释义。
  词库检索离线执行，Agent 编写提示词仍使用所配置的模型服务。
- **主题外观**：在 **设置 → 主题与外观 → 底栏风格** 中切换标准底栏与经典悬浮风格。

详见 [画廊说明](app-reconstructed/DANBOORU_GALLERY_PLAN.md) 与 [词库说明](app-reconstructed/TAG_LIBRARY.md)。

## 从源码构建

Android 工程位于 **`app-reconstructed/`**，在 Android Studio 中打开这个目录，
而不是仓库根目录。

构建前准备 **JDK 17、Android SDK、SDK Platform 37.0**，并接受 SDK 许可；
本地 Android / Compose 测试另需 **JDK 21 toolchain**。
配置 `JAVA_HOME`、`ANDROID_HOME`，或通过本机 `local.properties` 指定 SDK。

**Windows（cmd）：**

```bat
cd app-reconstructed
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain
```

**Linux / macOS：**

```sh
cd app-reconstructed
./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain
```

APK 自动导出到：

```text
app-reconstructed/app/build/outputs/ComfyPocket/ComfyPocket-<versionName>.apk
```

具体工具链版本、环境配置和测试说明见 [构建文档](app-reconstructed/BUILD.md)。

## 技术栈

- **语言**：Kotlin；配套 Bridge 使用 Python，设置面板使用 JavaScript。
- **界面**：Jetpack Compose、Miuix、Material 3。
- **状态与异步**：AndroidX Lifecycle / ViewModel、Kotlin Coroutines。
- **网络与数据**：OkHttp、Retrofit、Gson。
- **图片与视频**：Coil、AndroidX Media3。
- **构建与测试**：Gradle Kotlin DSL、JUnit、Robolectric、Compose UI 测试。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [项目说明与验证记录](PROJECT_NOTES.md) | 原有完整项目说明、版本记录、测试结果及验证边界。 |
| [构建与运行](app-reconstructed/BUILD.md) | 工具链版本、SDK、构建命令和输出位置。 |
| [Agent](app-reconstructed/AGENT.md) | 模型配置、提示词、角色档案、上下文与执行逻辑。 |
| [模型中心](app-reconstructed/MODEL_CENTER.md) | 本地模型、资料缓存、手机直连与服务器文件管理。 |
| [Civitai 集市](app-reconstructed/MARKET.md) | 搜索、代理、Key、缓存及下载联动。 |
| [Bridge 插件](app-reconstructed/ComfyUI-Mobile-Bridge/README.md) | 电脑端插件安装、设置、下载接口与升级。 |
| [Danbooru 画廊](app-reconstructed/DANBOORU_GALLERY_PLAN.md) | 原生画廊、排序、媒体查看和实施记录。 |
| [可选词库](app-reconstructed/TAG_LIBRARY.md) | 数据来源、中英查询、独立词库包与许可。 |
| [界面与导航](app-reconstructed/MATERIAL.md) | 两种底栏风格、页面切换及界面改动记录。 |
| [架构拆分](app-reconstructed/REFACTOR.md) | ViewModel 职责拆分与工程结构说明。 |

## 贡献指南

欢迎通过 Issue 反馈问题，或 Fork 后提交 Pull Request。

- 反馈问题时附上应用版本、后端类型、复现步骤及必要的日志或截图，并先移除 Key 等敏感信息。
- 修改代码前阅读对应功能文档，保持现有 Kotlin / Compose 风格，尽量补充相关回归测试。
- 提交前运行对应构建和测试，并如实说明验证结果；不要将自动化测试结果等同于真机体验验证。
- 不提交本机 SDK 路径、构建缓存、凭据、签名密钥、用户配置或模型权重。

## 项目说明与致谢

本项目修改自 [HAIGC/ComfyUI-APP](https://github.com/HAIGC/ComfyUI-APP)。
当前仓库基于该项目的源码快照重建构建配置，并在此基础上持续修改与扩展；
不代表已恢复原始发布工程，也不表示与上游保持同步。
应用图标引用 Pixiv 作品 ID `135715296`：[查看原作品](https://www.pixiv.net/artworks/135715296)。
感谢 ComfyUI 及 Android、Compose、Miuix 等生态；词库所引用的 ai-tag-toolbox、
Danbooru 中英对照等数据来源及许可说明见 [词库文档](app-reconstructed/TAG_LIBRARY.md)。

当前仓库尚未提供统一的项目级 `LICENSE`。
第三方组件与数据各自保留其原有许可和署名说明。
