# 项目说明与验证记录

> 本文保留原 README 的完整项目说明与历史验证记录，其中版本状态按各条记录所述。
> 面向新用户的介绍、安装和使用入口请看 [项目首页](README.md)。

Android 端 ComfyUI 控制客户端，包含本地 ComfyUI 与 RunningHub 后端、
工作流参数编辑、任务记录、Agent、图库和模型管理。

## 目录

- `app-reconstructed/`：可构建的 Android 工程。
- `app-reconstructed/ComfyUI-Mobile-Bridge/`：独立的 Python 服务端桥接插件，不打入 APK。
- `app-reconstructed/BUILD.md`：重建工程的构建版本与说明。
- `app-reconstructed/REFACTOR.md`：2026-10-17 的 ViewModel 职责拆分说明。
- `app-reconstructed/MARKET.md`：手机直连集市、代理、Key、缓存和下载队列说明。
- `app-reconstructed/MODEL_CENTER.md`：P0–P1 模型中心、电脑索引 / 标注、离线缓存、哈希资料及版本契约。
- `app-reconstructed/DANBOORU_GALLERY_PLAN.md`：Danbooru 原生画廊的实施记录、账号/代理操作与验证边界。
- `app-reconstructed/MATERIAL.md`：两种底栏风格、老版页面动画、预览移除与历史迁移说明。
- `app-reconstructed/AGENT.md`：Agent 第一阶段执行内核与思考强度档位说明。
- `app-reconstructed/TAG_LIBRARY.md`：可选词库安装、来源、查询边界与可复现构建方式。
- `app-reconstructed/ComfyUI-Mobile-Bridge/README.md`：配套插件安装和 ComfyUI 设置页配置。

## Civitai 集市

手机可独立直连 Civitai，支持自定义 HTTP 代理和 Key，查看模型简介、触发词、
示例图正反提示词。模型安装仍下发给 ComfyUI 电脑。
开启成人内容后，普通内容与成人浏览范围分区查询、独立翻页并去重。
支持 `.com / .red` 模型链接或编号直达公开详情，保留模型分级和链接指定版本。
本地模型按完整 SHA-256 由手机自动获取模型简介、版本说明、作者与网络预览，
保存到手机资料缓存；电脑刷新不会抹掉这些资料或人工标注。
网络封面同样复用手机代理，即使电脑断开也可继续读取。详见 `app-reconstructed/MODEL_CENTER.md`。

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

调试 APK 按 **软件名＋版本号** 自动导出到
`app-reconstructed/app/build/outputs/ComfyPocket/ComfyPocket-<versionName>.apk`，
例如 `ComfyPocket-0.0.39-character-dialogue-routing.apk`。文件名中的版本自动读取 Gradle 配置，无需手动改名。
应用安装后的显示名也是 **ComfyPocket**；AGP 内部 `app-debug.apk` 仍保留以兼容构建元数据。
安装包名为 `com.comfyui.mobile.rebuilt`，源码 namespace 为 `com.comfyui.mobile`。
服务地址和 API 密钥在应用中配置，不应提交到仓库。

本地模型详情的“文件管理”支持实际修改服务器文件：重命名、移动到同类型注册目录 /
新建子文件夹、确认后永久删除。同名预览和附属资料一并处理，服务器成功后才刷新手机记录。
此功能需完整更新 **ComfyUI-Mobile-Bridge 2.2.5** 并重启 ComfyUI；“编辑标注”仍只负责别名等资料，
不与真实文件名混用。详见 [模型中心说明](app-reconstructed/MODEL_CENTER.md)。

Agent、控制页和应用页共用的**本地模型选择器**接通模型中心同一份资料：
卡片为模型文件名 + 人工标注的中文名称，点选查看最新简介、触发词和标注；
确认仍使用 ComfyUI 原始完整节点路径。触发词集中到确认页，列表只保留文件名与中文标注；
简介支持展开，版本说明 / 标注 / 文件信息按需展开，固定底部操作。共享手机资料缓存、HTTP 代理及 Civitai Key，
保留人工锁定的空触发词。新增独立刷新、全量分页和失败重试，修正 UNet / GGUF 分类。
完整联动需配套 **Bridge 2.2.6**；新发现的原生文件不必等待资料索引完成才能选择。
更新插件不会由手机自动执行，需替换完整目录并重启，保留 ComfyUI 用户目录的资料与凭据。

## 可选词库

默认 APK 不携带词库。需要离线标签和角色中英查询时，单独获取 `ComfyPocket-tag-library.zip`，
在 **Agent 设置 → 可选资源 → 本地词库 → 导入词库** 中选择即可。安装后自动启用，支持更新和移除；
不安装也可正常聊天和生图。升级保留已安装词库，并识别旧版已经初始化的当前完整数据库。
源码构建词库包使用 `python app-reconstructed/tools/pack_local_tag_library.py`，不需要重新下载数据。

## 当前验证状态

- `0.0.39-character-dialogue-routing`（versionCode 41）：角色名称 / 别名与绘画标签分离，提示词只接收已匹配角色的绘画档案；
  服装预设增加独立 AI 对话触发词，App 依据用户句子自动选择预设并提供对应绘画标签，不需用户手动挑选本轮服装；
  过滤误入正反向输出的角色识别词与服装对话词，旧预设名称匹配保留兼容，预设触发词歧义时在模型请求和工作流提交前报错。
  debug 构建与 741 项测试通过，覆盖自动服装路由、角色名称 / 别名过滤、预设歧义阻断、旧 JSON 兼容和窄屏大字体编辑。
  APK SHA-256：`40f7cc5cdc3dc06e6db18cc3c153ccee54a90838c8709d05143e83363423a174`。

- `0.0.38-character-outfits`（versionCode 40）：角色库由 App 本地确定名称 / 手填别名匹配，
  直接注入本轮档案，不依赖 AI 记得调用或猜别名。续改保留已确认角色 / 服装，
  支持可选常驻、重名反馈、多套服装与默认项、临时换装、单角色固定触发词漏词补齐。
  角色列表可搜索，编辑页保存 / 取消固定，服装草稿随角色一起保存；旧资料及备份兼容。
  已验证完整 debug 构建与 112 套 / 731 项 Android 测试（新增 48 项），以及同输入旧版失败 /
  新版通过、真实可选词库身份隔离、工作流写入、深浅色窄屏大字体原生渲染。
  不增加模型轮次、不自动加载 LoRA、不改底栏或原页面切换。验证边界见 Agent 说明。

- `0.0.37-model-confirm`（versionCode 39）：触发词从模型列表移到选中确认页，
  列表保留模型文件名和中文标注；确认页采用紧凑头部、触发词 / 简介优先、
  长文本展开和版本 / 标注 / 文件折叠，共用一个滚动区并固定底部取消 / 确定。
  保留精确节点路径、复制、加入角色库与显式写入文本节点；普通确认不自动追加触发词。
  完整 debug 构建及 109 套 / 683 项 Android 测试通过，包括同一测试的旧版失败 / 新版通过、
  复制 / 写入动作、云端兼容、深色窄屏及浅色 320dp / 大字体原生渲染与触摸滚动。
  本批只更新 APK，Bridge 维持 2.2.6；不改底栏或原页面切换动画，不自动安装或部署。

- `0.0.36-model-picker`（versionCode 38）：模型选择器共用模型中心的资料与中文标注，
  卡片文件名下显示中文名称，点选展示最新简介、触发词和标注，仍回填原始节点文件路径。
  共享手机来源缓存、Key / 代理，人工锁定空触发词保持为空；修正刷新、UNet / GGUF 分类、
  全量分页、查询草稿、失败重试及旧回包覆盖。完整功能需 Bridge 2.2.6。
  完整 debug 构建、108 套 / 672 项 Android 测试、124 项 Bridge Python 和 10 项设置面板测试通过；
  已检查 320dp 列表 / 详情原生渲染、长备注触摸滚动、基线 / 修改版同输入探针及打包插件重跑。
  未自动安装、部署、重启或修改真实权重；桌面渲染不等于手机性能实测。

- `0.0.34-optional-tags`（versionCode 36）：完整中英标签与角色词库改为可选 ZIP 安装包，
  默认 APK 不包含数据库或原始词表，83,723,800 → 58,834,055 bytes，减少约 29.73%。
  完整词库包 25,268,492 bytes，支持导入、校验、更新、移除、旧版已初始化数据迁移及无词库正常生图。
  Agent 设置整理为模型服务、提示词和可选资源；连接 / 生成参数与长说明折叠，提示词预览和编辑更紧凑，
  保留配置保存反馈、正反向流程、两种底栏及原有页面动画。debug APK 构建及 102 套 / 633 项 Android 测试、
  4 项词库构建规则测试通过；已检查深色与 320dp 浅色原生渲染图，不代表手机帧率实测。

- `0.0.33-toast-feedback`（versionCode 35）：全应用短操作提示恢复为 P2 之前的系统 Toast，
  移除前台 Material Snackbar；页面、弹窗和 ViewModel 共用主线程提示入口，新消息替换旧消息，
  空消息不展示，同文案的新操作仍各自确认。切页 / 重建 / 恢复不重放旧提示。
  截图中的 Agent 重置成功、实际写入失败和缺少工作流三种反馈已回归；
  debug APK 构建及 100 套 / 626 项 Android 测试通过。两种底栏与页面切换保持不变。

- `0.0.32-market-cleanup`（versionCode 34）：精简在线集市的常驻说明、重复筛选摘要和
  分区副标题；直达详情收进搜索行，作者输入提示缩短。保留清除筛选、错误与重试、
  两区标题及数量，查询、分页与预览逻辑不变。debug APK 构建及 11 项现有界面回归通过，
  已检查使用合成封面的原生渲染图。

- `0.0.31-market-channels`（versionCode 33）：在线集市普通内容 / 成人浏览范围分区查询，
  各自分页、缓存与重试，跨区按 ID 去重；开启成人内容不再撤掉普通内容查询。
  新增 `.com / .red` 链接或编号直达公开详情，保留指定版本、内容标记与分级预览。
  完整 debug APK 构建、98 套 / 617 项 Android 回归、Bridge Python 92 项及设置面板 Node 10 项通过。
  合成双区原生渲染与真实页面外壳触摸滚动通过，不代表真机帧率或真实账号权限联调。
  手机直连只需 APK；桥接列表需配套 2.2.4 以保留预览分级，详见模型中心文档。

- `0.0.27-model-center-direct`（versionCode 29）：本地模型资料与网络预览改为手机直连，
  复用集市 HTTP 代理和 Civitai Key；按文件哈希精确匹配并缓存模型总简介、版本说明、
  作者、底模和触发词。详情改为紧凑封面、简介与触发词优先、文件信息默认折叠。
  在线列表保留显式电脑桥接备用模式，但详情和网络预览不再随它绕电脑。
  保留上一版触摸滚动修复、两种底栏与原切换动画；Bridge 2.2.1 不变。
  完整 debug 构建及 86 套 / 543 项测试通过，0 失败 / 错误 / 跳过；
  包含手机代理取图、代理变更重试、简介缓存重启和六类触摸滚动回归。
  自动化验证结果和实际设备验收边界见 `app-reconstructed/MODEL_CENTER.md`。

- `0.0.26-model-center-scroll`（versionCode 28）：修复模型中心三个分页不能触摸滚动的回归。
  固定工具栏不再挂载旧折叠标题栏的 nested-scroll 拦截；该连接仅保留给原插件页。
  保留上一版 UI、封面、底栏和页面动画，本次只需更新 APK，无需重新更新 Bridge。
  在真实 `ModelHubScreen` 外壳中注入触摸上滑 / 下滑：旧版六项全部复现位置不动，
  修复后本地网格、紧凑列表、在线集市、下载队列、资料任务、详情六项均通过。
  完整 debug 构建及 82 套 / 517 项测试通过，0 失败 / 错误 / 跳过；手机实际手感待复测。

- `0.0.25-model-center-ui`（versionCode 27）：模型中心改为紧凑工具栏、下划线分页、
  自适应封面网格及带缩略图的紧凑列表；搜索、筛选、同步摘要和在线集市统一排版。
  下载任务改为独立进度卡片，模型下载与资料补全分开查看，完成任务不再显示零速率。
  本地列表及详情自动加载预览，Bridge 2.2.1 优先读取同目录同名封面，不依赖哈希匹配；
  无本地封面时复用已有来源资料，图片缓存按服务器和封面版本隔离。
  两种底栏、原页面切换、角色词库与正反向提示词流程保持不变。
  Android debug 构建及 81 套 / 511 项测试、Bridge 82 项和原设置面板 10 项全部通过。
  深色网格 / 紧凑列表、在线页、任务卡和 320dp 浅色窄屏已做合成数据布局渲染，
  不代表手机 GPU 或帧率实测。本地封面功能需更新完整 Bridge 2.2.1 并重启，
  保留 ComfyUI 用户目录中的 `mobile_bridge/`；本轮未自动部署或提交真实生图。
  升级与验证边界见 `app-reconstructed/MODEL_CENTER.md`。

- `0.0.24-model-center`（versionCode 26）：主页原资产区分流到本地模型 / 在线集市 /
  原插件列表，模型中心三个分页；新增服务器隔离缓存、注册目录索引、详情标注与修订撤销、
  精确 SHA-256 资料补全、独立资料队列和下载后资料留存，配套 Bridge 2.2。
  两种底栏、原页面动画、正反提示词和角色库保留。
  Android debug 构建及 506 项测试通过；Bridge 76 项、原设置面板 10 项通过。
  本批仅 P0–P1，Agent 接线 / 写词库仍为后续阶段；电脑新插件部署和真机联调尚待完成。
  验证边界与使用说明见 `app-reconstructed/MODEL_CENTER.md`。

- `0.0.23-navigation-motion`（versionCode 25）：底栏切页按入口顺序区分左右，修复
  「应用 → Agent」仍从右进入；两种底栏保持同一套老版距离、时长，RTL 镜像，二级页进入/返回不变。
  Danbooru、集市连接、Agent 提示词编辑/恢复默认、管理标签和分级选择先退场再卸载，
  补齐取消/关闭动画；退场中拦截重复操作，取消不保存。上一版全屏缩放与视频下载保留。
  完整 debug 构建及 489 项测试通过，0 失败 / 错误 / 跳过；逐帧布局与截图已检查，
  手机实际帧率和主观手感仍待复测。
- `0.0.22-danbooru-fullscreen`（versionCode 24）：Danbooru 详情支持视频下载、进度与取消，
  流式复用完整缓存并保存到 `Movies/ComfyPocket`；点图片进入全屏，支持双指缩放、拖动、
  双击放大/复位和原图。视频内嵌/全屏共用同一个播放器，保留播放进度与暂停状态。
  分级固定在排序行右侧，去掉排序按钮的双层按压底色。完整 debug 构建及 475 项测试通过；
  真实手机相册、CDN/代理、硬件解码与手势帧率仍待实测。未改来源行、底栏或页面切换。
- `0.0.21-danbooru-media`（versionCode 23）：修复 Danbooru 把视频地址当封面、预览失败不尝试
  备用图片的问题，读取 `media_asset` 图片/视频变体；新增点按后才启动的详情页视频播放，
  沿用画廊独立 HTTP 代理。排序/评级改为紧凑文字按钮，窄屏和大字体自动换行；
  详情页内容可从经典悬浮底栏后方滚过，仅在列表尾部避让。完整 debug 构建及 460 项测试通过；
  手机硬件解码、真实 CDN 与实际手机代理仍待设备验收，ZIP/SWF 保留封面与标签。

- `0.0.20-overlay-layer`（versionCode 22）：修复悬浮底栏盖住 Agent 画风/提示词和控制器节点管理
  弹窗底部按钮、触摸误切入口的问题。统一将当前页面的弹窗绘制在整个导航外壳之上，
  保留页面自己的返回/生命周期/主题；两种底栏外观与原版切换动画不变。
  旧版四项实点复现失败，修复后完整 debug 构建与 437 项回归全部通过。

- `0.0.19-navigation-styles`（versionCode 21）：在 **设置 → 主题与外观 → 底栏风格**
  提供「标准底栏 / 经典悬浮」两种样式，立即生效并记住选择，页面与返回栈共用。
  两种底栏统一复用老版 280ms 横向滑动与淡入淡出；Danbooru 点按查看标签、返回列表也加入同套动画。
  删除 Material 预览入口、独立 Activity、演示页面及资源。
  debug APK 构建成功，432 项回归全部通过（0 失败 / 错误 / 跳过）。
  P3–P5 保持回退状态，不改业务协议、词库或生成/媒体缓存逻辑。验证边界见 `app-reconstructed/MATERIAL.md`。

- `0.0.18-p2-restored`（versionCode 20）：通过新的回退提交取消 P3–P5，保留 P2 外壳与底栏对齐修复，
  源码恢复到 `2b29c4b`，仅提高 APK 发布版本号；构建及 448 项回归通过。

以下为历史版本记录；Material 预览在当前版本中已删除：

- `0.0.11-material-motion`（versionCode 13）：为 **Material 预览**补充可中断的 Tab 内容切换、
  详情／设置进入与返回、全屏图淡入淡出。底栏固定，重复点击不刷新，不改原 Miuix 外壳。
  保留退场内容快照，退场页不再处理点击与返回；系统动画倍率为 0 时直接结束过渡。
  验证边界和动效规格见 `app-reconstructed/MATERIAL.md`。

- `0.0.10-material-preview`（versionCode 12）：新增 **设置 → Material 预览**，原界面保留。
  提供 Material 3 主题、五入口导航演示、Danbooru 图片／Tag／全屏与账号代理表单样板。
  默认离线，可演示空／失败／追加状态，手动确认后可连接现有 Danbooru 配置。
  仅完成 P0／P1，未进入全应用迁移；使用和验证边界见 `app-reconstructed/MATERIAL.md`。

- `0.0.9`（versionCode 11）：Danbooru 增加热度、评分、收藏排序及日／周／月排行榜，可前后切换榜期。
  热度使用官方 `order:rank`，排行榜使用官方日历范围和 `order:score`；请求在服务端排序，排序列表使用数字分页。
  统一画廊、设置与 Tag 详情的布局，浏览网格只保留清晰图片，移除帖子编号底栏。
  改用 Miuix 原生下拉刷新动画；刷新期间保留旧图，失败保留当前窗口并允许重试。
  Android 构建及 394 项 JVM/Android/Compose 回归通过（基线 377 项，新增 17 项）；真机动画观感仍待实测。
  使用方式、日期与验证边界见画廊计划第 14 节。

- `0.0.8`（versionCode 10）：应用显示名及交付 APK 统一为 ComfyPocket；删除 Agent 的三种格式选项及对应偏好，保留自定义分镜、离线双语词库与正反向流程。
  画廊新增手机直连 Danbooru：独立代理和账号 Key、搜索/评级、游标与数字分页、原生详情、五类 Tag、多选复制、单图保存分享及独立缓存。
  原服务器/本地来源、包名、签名、底栏动画和 Bridge 保留。实施与实测边界详见画廊计划第 13 节。

- `0.0.7-agent-bilingual-tags`（versionCode 9）：内置词库扩充为 140,871 个标签与 99,980 个角色，
  共 240,851 项，均有中文对应。合并 Danbooru 社区中英对照固定快照，支持中文整句 / 别名查英文标签，
  不把纯英文回显计入中文覆盖。含机器翻译、画师和作品数据，来源、许可与过滤范围见 `app-reconstructed/TAG_LIBRARY.md`。
  删除聊天里的词库命中展开提示，保留离线检索与设置页查词；正反向流程不变。
- `0.0.6-agent-local-tags`（versionCode 8）：撤掉 Danbooru 联网查词，内置 13,358 个普通词、
  34,122 个角色条目，支持中英文/别名搜索。绘图前离线取候选，不增加模型修订轮次。
  Agent 主动编写正向和反向；工作流可分别选择精确文本字段；“出图提示词”同时显示、
  分别复制和成对保存两组词，明确实际写入状态。用户自定义人设与分镜规则保留。
- 保留此前的 CPU 进度条同色、提示词职责分离、
  反向缺失保留原值和显式空字符串清空行为；更新本版仅需 APK，不必更新 Bridge。

- `0.0.3-agent-media-monitor`（versionCode 5）修复生成通知静默、Agent 设置保存反馈、
  分享和画廊的原图缓存复用；主页新增 CPU / 内存预览，配套 Bridge 2.1，详见 `app-reconstructed/AGENT.md`。
- `0.0.2-agent-core`（versionCode 4）落地 Agent 实例级会话、分步执行和工作流工具；
  思考强度包括极高 `xhigh` 和 `max`，随模型配置保存，详见 `app-reconstructed/AGENT.md`。
- 0.0.7 Android 基线构建成功，323 项 JVM/Android 页面测试和 3 项双语词库导入测试通过；0.0.8 的 377 项 JVM/Android/Compose 回归全部通过（基线 323 项），真实账号/代理/真机尚待验证，详见画廊实施记录。
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

本项目修改自 [HAIGC/ComfyUI-APP](https://github.com/HAIGC/ComfyUI-APP)。
本仓库基于该项目的源码快照重建构建配置并做职责拆分，不代表已恢复原始发布工程，
也不表示与上游保持同步。
应用图标引用 Pixiv 作品 ID `135715296`：[原作品页面](https://www.pixiv.net/artworks/135715296)。
本机 SDK 路径、缓存、执行记录、原始备份和 APK 交付文件均排除在 Git 之外。
