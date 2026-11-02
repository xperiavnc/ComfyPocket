# ComfyPocket

ComfyPocket 是一个面向 Android 的 ComfyUI 移动客户端。它让用户可以在手机上连接自己的 ComfyUI 服务，管理工作流、模型和生成任务，并通过统一的界面查看生成结果。

## 项目功能

- 连接本地或局域网中的 ComfyUI，也支持 RunningHub 等远程服务。
- 浏览、编辑和提交 ComfyUI 工作流，配置节点参数并查看任务状态。
- 使用 Agent 对话辅助整理绘图需求、生成正向和反向提示词，并根据工作流提交任务。
- 管理本地模型、LoRA 和模型元数据，查看预览图、简介、触发词及标注。
- 访问 Civitai 模型市场，支持模型搜索、详情查看、代理和 API Key 配置。
- 浏览 Danbooru 图库，查看图片、视频和标签。
- 查看生成历史、图片和视频，并进行全屏预览、下载和分享。
- 可选安装本地标签词库，用于 Agent 查询中文释义、角色标签和绘画标签。
- 提供 CPU、内存和连接状态等基础运行状态信息。

## 使用条件

- Android 13（API 33）或更高版本。
- 一台可以从手机访问的 ComfyUI 服务，默认端口通常为 `8188`。
- 如果需要从手机管理模型或通过 Bridge 下载模型，请在 ComfyUI 中安装
  `app-reconstructed/ComfyUI-Mobile-Bridge/` 目录中的插件。
- 使用 Agent、Civitai 或 Danbooru 的在线服务时，需要在应用设置中配置相应的服务地址、账号、Key 或 HTTP 代理。

## 基本使用

1. 安装 APK 并打开应用。
2. 在服务器设置中填写 ComfyUI 的访问地址，例如：
   `http://<电脑局域网地址>:8188/`。
3. 测试连接成功后，选择工作流并配置模型、提示词及其他参数。
4. 提交任务后，可以在任务或画廊页面查看生成结果。
5. 如需使用 Agent，在 Agent 设置中配置模型 API、模型名称和访问 Key。

手机上的 `localhost` 和 `127.0.0.1` 指向手机本身，不是电脑上的 ComfyUI。手机与电脑需要处于可互通的网络环境，并允许 ComfyUI 接收局域网连接。

## ComfyUI-Mobile-Bridge

Bridge 插件用于让手机与 ComfyUI 交换模型信息、下载任务和文件状态。安装时，将
`app-reconstructed/ComfyUI-Mobile-Bridge/` 目录复制到：

```text
ComfyUI/custom_nodes/ComfyUI-Mobile-Bridge/
```

然后重启 ComfyUI。Bridge 不依赖 Lora Manager；具体可用功能以当前 ComfyUI 和插件环境为准。

## 本地词库

本地词库是可选组件，不随默认 APK 强制安装。安装词库后，Agent 可以查询标签的中文释义、角色信息和触发词。词库文件应通过应用的 Agent 设置页面导入或管理。

## 从源码构建

项目 Android 源码位于 `app-reconstructed/`。构建环境需要：

- JDK 17
- Android SDK Platform 37
- Android Gradle Plugin 所需的 Android SDK 构建工具

Windows：

```bat
cd app-reconstructed
gradlew.bat :app:assembleRelease
```

Linux 或 macOS：

```sh
cd app-reconstructed
./gradlew :app:assembleRelease
```

发行版 APK 会输出到：

```text
app-reconstructed/app/build/outputs/ComfyPocket/ComfyPocket-<versionName>.apk
```

Debug 构建仅用于开发验证；对外分发请使用 `assembleRelease` 生成的 APK。

## 技术栈

- Kotlin
- Jetpack Compose、Material 3、Miuix
- AndroidX Lifecycle、ViewModel、Navigation
- Kotlin Coroutines
- OkHttp、Retrofit、Gson
- Coil、AndroidX Media3
- Room
- Gradle Kotlin DSL

## 许可与第三方资源

项目使用的第三方库遵循各自的开源许可证。随仓库提供的第三方词库许可证文件位于：

```text
app-reconstructed/tag-library/ffdkj-LICENSE.txt
```

如需反馈问题，请在 GitHub 仓库提交 Issue，并附上应用版本、设备系统版本、复现步骤和必要的日志信息。请勿提交 API Key、密码、签名密钥或个人数据。
