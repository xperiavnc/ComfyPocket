# Danbooru 在线画廊集成计划

- 状态：0.0.9 已实施；原方案保留作设计依据，初版交付见第 13 节，排序／界面优化及验证边界见第 14 节。
- 编写日期：2026-10-17（Asia/Shanghai）。
- 代码检查基线：7f996509ee024f88d6a5270fa6d99dbaa7111605。
- 目标工程：C:/Users/x/Documents/ChatGPT/Android/app-reconstructed。
- 原始需求：画廊内在线浏览 Danbooru、查看并复制图片 Tag、自定义 HTTP 代理、使用账号 API Key 登录。

## 1. 范围与默认决策

第一阶段交付原生 Compose 页面，不嵌入整站 WebView；手机直连 Danbooru，不经过 ComfyUI 或 Mobile Bridge，不要求更新电脑插件。

| 项目 | 第一阶段决定 |
| --- | --- |
| 入口 | 现有「画廊」增加 Danbooru 来源；保留服务器和本地画廊 |
| 浏览 | 图片网格、搜索、下拉刷新、连续加载、图片详情 |
| Tag | 五类分组、单项/多选/全部复制、两种输出格式 |
| 连接 | 独立 HTTP 代理配置，覆盖 API、缩略图、预览图和原图 |
| 登录 | 用户名＋API Key；不收集账号密码，不做浏览器 Cookie 登录 |
| 缓存 | 列表、滚动位置和图片复用；明确区分缩略图、预览图、原图 |
| 详情动作 | 单图保存/分享、在浏览器打开原帖、显示作者与来源 |
| 内容 | 初始查询使用一般内容筛选；用户选择与账号可见范围共同生效 |
| 暂缓 | 收藏/投票/发帖、视频播放、批量下载、镜像站、多账号管理 |
| Agent | 不恢复自动联网查词，不改变现有离线双语词库和正反提示词逻辑 |

这里将「自定义 HTTP」明确解释为 HTTP 代理，而不是把官方 API 改成明文 HTTP；首版站点固定为 `https://danbooru.donmai.us`。代理与 Civitai、ComfyUI 的配置相互独立，不自动复用其他服务的密钥。

## 2. 已核对的官方接口与边界

以下事实来自官方 Wiki 与官方仓库；产品默认值属于本计划的设计选择，不应误写成服务端固定限制。参考资料见第 12 节。

### 2.1 认证与账号验证

- 官方支持用户名＋API Key 的 HTTP Basic 认证，也支持 `login/api_key` 参数；本项目选择 HTTPS 请求头认证，避免把 Key 放进请求 URL。[S1][S4]
- 读取 `/profile.json` 可获取当前用户，但服务端对未登录者也会返回匿名用户对象。因此，测试登录必须检查非匿名账号身份与输入用户名，不能只依据 HTTP 200。[S3][S5]
- 只读客户端创建 Key 时可限制到读取动作；首版需要 `users:profile`、`posts:index`、`posts:show`。官方 Key 按控制器/动作和允许的出口 IP 校验，不是简单的 URL 前缀白名单。配置了 IP 限制的 Key 使用代理时，也需匹配代理出口。连接测试分别验证身份和帖子读取，区分凭据失效与动作/IP 权限不足。[S4][S6]
- 搜索数量、可见帖子和其他权限受账号等级、站点策略、账号设置及 Key 权限共同约束；登录不等于提升账号等级。不把站点限制写死成一个通用常数。[S1][S4]

连接测试为真实只读请求，不执行收藏、投票、修改设置等写操作。

### 2.2 帖子与 Tag

- 列表：`GET /posts.json`；按 ID 查询详情：`GET /posts/{id}.json`。[S2]
- 主要查询参数：`tags`、`limit`、`page`；使用 URL 构造器编码整条搜索，不手动拼接空格、冒号和括号。[S1][S2]
- 模型读取五类 Tag 字段：`tag_string_artist`、`tag_string_copyright`、`tag_string_character`、`tag_string_general`、`tag_string_meta`；保留原始名称而不是从显示文字反推。[S2]
- 图片字段使用 `preview_file_url`、`large_file_url`、`file_url`；尺寸、格式、来源、评级等字段按实际响应读取，缺失时允许为空。Tag 可见而图片不可用时，仍显示 Tag 和原因，不生成虚假的原图地址。[S2]
- 第一阶段用帖子自带的分类字段展示 Tag，不为每个 Tag 额外请求网络。标签自动补全与别名查询放到后续阶段。

### 2.3 分页：避免「只能看一点」

- 官方同时提供数字页码与 ID 游标；`page=b<ID>` 表示 ID 小于给定值，`page=a<ID>` 表示 ID 大于给定值。数字分页存在深度限制。[S1]
- 「最新」固定使用默认 ID 降序；下一页取已接收帖子最小 ID，使用 `b<ID>`。请求参数、筛选和排序在整个分页会话内保持一致。
- 评分等非 ID 排序使用对应数字分页。首版不把 ID 游标套到这类排序，也不在数字页到达站点边界时静默切换排序冒充继续浏览。
- 数字分页遇到官方定义的 HTTP 410 时显示「已到当前账号的分页边界」，不归为普通网络失败后持续重试。[S1]
- 页面按帖子 ID 去重；连续加载不得因本地列表容量、首屏数量或网络错误被错误标记为结束。
- 首版每页建议 40 条，这是客户端选值。只有成功取得空页或服务端明确结束才标记「已到底」；超时、拒绝访问、限流和字段解析失败各自显示可重试状态。
- 为控制长列表内存，缓存按查询与页块组织；深度浏览时保存游标锚点和前后页块，淘汰远端页块前保留返回加载能力，不把淘汰误报为服务端只剩几十张。

### 2.4 请求节奏

官方文档描述短时突发与长期平均请求限制，并建议持续访问约每秒一次。首版 API 队列采用每秒最多启动 1 次、并发 1 的保守默认；实际限流以服务端响应为准。[S1]

- 搜索提交、加载下一页、账号测试统一进入 API 队列；取消过期搜索，禁止同一游标重复在途。
- 使用可识别的 `User-Agent`，例如 `ComfyPocket/<version> (Danbooru; user #<id>)`；登录确认后带真实账号 ID，不包含 Key，不冒充浏览器。官方要求客户端标识并建议先在 testbooru 测试；初期接口兼容性验证优先测试站，测试站与主站账号/Key 不混用。[S1]
- 图片走单独受控队列，建议并发不超过 4；离屏与退出详情时取消无用任务，不全站抓取或后台无限预取。
- HTTP 429 尊重 `Retry-After`（存在时）；缺失时采用有上限退避，自动重试最多两次，再给出手动重试入口。
- 401/403 不自动循环重试；API 和媒体请求不共享无界重试任务。

## 3. 现有工程检查结果与接入方式

原计划的基线检查发现：画廊现在是服务器/本地两个来源，来源 setter 会把其他字符串归为服务器；直接塞入第三个字符串会出错。当前全局图片加载器走 `ComfyRepository` 的 `hostAwareCallFactory`，不能直接拿来承接 Danbooru 的独立代理请求。

| 已有文件（绝对路径） | 接入关注点 |
| --- | --- |
| C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/ui/screens/GalleryScreen.kt | 目前约 1,376 行；只增加来源入口适配，不再塞入完整联网画廊 |
| C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/ui/GalleryUi.kt | 现有服务器/本地行为保留，不让新增模块依赖整个主 ViewModel |
| C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/ui/MainViewModelGallery.kt | 保留原有来源 setter 的两来源语义，不传入 Danbooru 字符串 |
| C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/MainActivity.kt | 画廊路由改为轻量来源宿主，其他底栏与动画不重写 |
| C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/data/SecretStore.kt | 复用现有 Android Keystore 存储方式，新增独立凭据槽位 |
| C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/market/CivitaiHttp.kt | 参考 HTTP 代理/认证和错误映射做法，不耦合 Civitai 业务实现 |
| C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/ComfyApplication.kt | 现有全局 Coil 缓存保持原行为，Danbooru 不误走 ComfyUI 客户端 |
| C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/data/MediaFileCache.kt | 原图分享逻辑增加可注入加载器/缓存键的入口，旧调用默认行为不变 |

### 3.1 建议模块

新增独立 `danbooru` 包，分为 API/JSON、Repository、SettingsStore、Runtime、ViewModel、UI；搜索、分页、账号状态均由该模块持有。主 ViewModel 不增加 Danbooru 大段业务逻辑。

建议新增文件：

- C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/danbooru/DanbooruModels.kt：可空媒体字段、帖子、Tag、账号与分页类型。
- C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/danbooru/DanbooruHttp.kt：严格 TLS、HTTP 代理、认证和取消。
- C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/danbooru/DanbooruRepository.kt：查询、详情、游标与错误分类。
- C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/danbooru/DanbooruSettingsStore.kt：普通设置与凭据存储边界。
- C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/danbooru/DanbooruRuntime.kt：稳定的 API/媒体客户端和图片加载器生命周期。
- C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/danbooru/DanbooruViewModel.kt：界面状态、请求代次、取消及恢复。
- C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/danbooru/TagCopyFormatter.kt：原始/提示词格式的纯函数。
- C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/ui/screens/gallery/GalleryHomeScreen.kt：画廊来源宿主及旧来源迁移。
- C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/ui/screens/danbooru/DanbooruScreen.kt：列表与搜索。
- C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/ui/screens/danbooru/DanbooruDetailScreen.kt：预览、信息、Tag、保存/分享。
- C:/Users/x/Documents/ChatGPT/Android/app-reconstructed/app/src/main/java/com/comfyui/mobile/ui/screens/danbooru/DanbooruSettingsDialog.kt：唯一设置界面。

实现时可合并过短文件，但保留网络/状态/UI/格式化四个边界。沿用工程已有 OkHttp、Gson、Coil、Compose、协程依赖；第一阶段先用轻量分页状态，不为此整体引入新的架构框架或升级依赖。

### 3.2 来源迁移

1. 来源宿主使用类型明确的 `SERVER`、`LOCAL`、`DANBOORU`，而非散落字符串。
2. 新增 `gallery_home_source` 持久化项；首次缺失时读取旧 `gallery_source` 的 server/local 值，旧用户默认体验保持不变。
3. 选择 SERVER/LOCAL 时映射到现有 GalleryUi；选择 DANBOORU 时进入独立页面，不调用旧两来源 setter。
4. 两类页面使用同一个小型来源切换组件；宿主自身不再嵌套一个额外 Scaffold，避免重复标题栏/底栏留白。
5. 本地多选、删除、分类及服务器媒体的动作继续只作用于旧来源；在线帖子绝不传给 ComfyUI 的删除接口。

## 4. 界面与交互

### 4.1 列表页

- 来源选择：服务器 / 本地 / Danbooru，置于画廊标题附近；设置只保留一个入口，避免出现两份同名设置。
- 搜索框：支持原始 Danbooru 搜索语法；首版点击键盘搜索或按钮才提交，不逐字请求。
- 初始搜索包含一般内容筛选；高级输入如果已含评级条件，先解析并反映到筛选控件，避免悄悄附加互相冲突的条件。
- 「最新」为默认；若输入含其他排序条件，则切换到数字分页并明确显示有效排序。初版不添加一堆会被忽略的筛选按钮。
- 两列起步、稳定帖子 ID 为列表 key、固定缩略图占位比例；不在列表解码全尺寸图片。
- 首屏加载、无结果、加载失败、追加失败、权限提示、确实到底是不同状态；追加失败保留已有内容。
- 切换来源、进出详情与配置后保留搜索和滚动位置；改变查询、评级、排序、账号后才开启新查询会话。
- 离开页面不再自动续页；恢复时优先现有状态与缓存，不立即清空再重载。

### 4.2 详情页与 Tag

- 上方是图片预览，下面是帖子 ID、评级、尺寸、画师、来源及原帖入口；没有外部来源时仍可打开 Danbooru 原帖。
- Tag 分组顺序：画师 / 作品 / 角色 / 通用 / 元信息。每组展示数量，支持折叠。
- 单个 Tag：查看原始名称、点击复制；额外搜索动作放入明确菜单，避免单击同时触发复制和跳页。
- 多选：可跨组选择、全选当前组、清空；复制后显示「已复制 N 个 Tag」。
- 全部复制：保留五类，使用固定分组顺序、组内保持返回顺序；合并时去重但不重排。
- 提示词快捷复制：默认仅通用＋角色，用户可勾选画师/作品/元信息；不要把 score、评级和帖子尺寸等界面信息误拼进提示词。
- Tag 的中文注释可延后接现有离线词库；第一阶段没有翻译时显示原词，不能把英文回显标成中文译名。

### 4.3 两种复制格式

| 模式 | 示例 | 规则 |
| --- | --- | --- |
| 原始 Tag | `blue_sky cloud landscape` | 用空格连接，保留大小写与下划线，用于站内检索/归档 |
| 提示词 | `blue sky, cloud, landscape` | 用逗号＋空格连接；「下划线转空格」可开关 |

原始字段与显示字段分别保存，不因 UI 下划线转空格而破坏搜索。括号转义是额外的、默认关闭的选项，原始模式不做提示词转义。复制后校验最终文本非空，不修改系统剪贴板中与本次操作无关的内容。

### 4.4 返回手势

返回处理顺序为：关闭设置/Tag 弹层 → 退出多选 → 退出详情 → Danbooru 列表 → 交由现有画廊根导航处理。每次手势只消费一个层级，不在同一次事件内又关闭详情又弹出根页面。

只在当前最高优先级层拥有返回处理权；工具栏返回与系统返回调用同一个状态动作。预测返回取消不提交页面跳转；不为此改动所有底栏切换动画，也不承诺在根页面改变系统退出规则。

## 5. 设置、保存与登录

设置项：

- 用户名、API Key（遮蔽显示，可暂时查看/替换）。
- HTTP 代理启用开关、地址、端口、可选代理用户名与密码。
- 默认内容筛选、Tag 复制格式、下划线转换。
- 「保存」「测试连接」「退出登录」「清除 Danbooru 缓存」。

保存和测试使用同一份经过校验的配置快照。测试尚未保存的输入时标注「正在测试当前输入」；测试成功不等于已保存。保存完成显示明确反馈；持久化失败时保留输入、显示失败原因，不错误提示成功。普通选项与凭据保存按一份配置处理，避免出现新用户名配旧 Key 的半成功状态。

账号状态分为未登录、已保存待验证、验证中、已验证（用户名/等级）、验证失败。瞬时超时不自动删除已存 Key，也不把上次已保存配置显示为刚验证成功。

- Key 和代理密码复用 SecretStore 的加密与 no-backup 存储，新增 Danbooru 独立槽位。
- 普通偏好只放地址、开关与显示选项；Key 不进入配置导出、SavedState、日志、截图用诊断文本和 Git。
- 身份认证只加到精确匹配的官方 HTTPS API 请求；图片/CDN 请求默认不带账号认证，若后续实测确有必要，应显式核对官方要求后再扩展，而不是全域转发。
- HTTP 代理认证只用于选定代理；不会转成站点 Authorization。
- 不继承 ComfyUI 局域网客户端的宽松证书配置。代理通过 HTTPS CONNECT 承载到站点的 TLS；没有代理时使用独立直连。
- 保存代理/账号后在后台替换客户端、取消旧请求并递增请求代次；旧请求即使晚到，也不得覆盖新配置对应的列表。
- 退出登录清除 Key、账号验证态和该账号的在线缓存命名空间；可保留代理和普通偏好。用户主动保存到系统相册的图片不随退出删除。

## 6. 图片加载与缓存：避免重复下载

### 6.1 网络路由

API 和所有图片表示均使用 DanbooruRuntime 提供的代理感知客户端。可复用解析/存储工具，但不把请求交给默认 ComfyUI call factory；Coil、原图保存、分享下载三个入口必须覆盖到。

Runtime 为应用进程内稳定对象；正常返回列表、Activity 重建不重新创建 ImageLoader。修改配置时原子轮换网络客户端，资源清理放到后台，避免重演退出时主线程关闭连接导致的问题。

### 6.2 缓存身份与清理

- API 缓存键包含站点、账号/匿名作用域、有效查询、筛选、排序、分页与配置代次；不同账号的结果不混用。
- 图片缓存键区分帖子与媒体版本，以及 thumbnail/sample/original；不得把缩略图当作原图缓存命中。
- Danbooru 使用独立磁盘命名空间，建议起始预算 128 MiB、可清除；这是待测量调优的产品值，不修改现有生成图缓存额度。
- 分享临时文件继续短期清理，原图写入使用临时文件完成后再发布；同一图片的并发分享/保存合并一次下载。
- 清除 Danbooru 缓存不删除 ComfyUI 生成图、不触碰系统相册，也不撤销登录。
- 缓存被系统清理、LRU 淘汰或用户清除后重新请求属于预期行为，界面不保证永久离线保留。

### 6.3 下载次数验收口径

1. 网格只请求缩略图；进入详情按需请求预览图。
2. 如果预览 URL 与原图不同，第一次点击「分享原图」确实需要再取原图；这是获取不同质量，不算缓存回归。
3. 同一原图已成功缓存且未被淘汰时，再次分享、保存或打开原图都复用原始字节，不重复拉取。
4. 不把解码后 bitmap 重新压缩冒充原图；文件名和 MIME 从真实格式/响应确认。
5. 图片失败或为空时保留 Tag 阅读与复制；不对缺失 `file_url` 的帖子触发无效下载。
6. 首版不播放视频：遇到视频帖子保留封面、格式标记、Tag 与原帖入口，不表现为白屏或崩溃。

## 7. 开发阶段与完成条件

| 阶段 | 具体工作 | 进入下一步的条件 |
| --- | --- | --- |
| 1：接口与设置闭环 | DTO、独立网络客户端、设置存储、用户名＋Key 验证、代理测试、错误分类 | 匿名/登录两条读取路径可验证；API 及媒体都使用目标连接配置；不把匿名 200 当登录成功 |
| 2：列表与导航 | 来源宿主、旧偏好迁移、最新游标分页、搜索、筛选/排序匹配、状态恢复 | 连续多页可加载；旧请求不覆盖新搜索；原本地/服务器画廊功能保留 |
| 3：详情与 Tag | 原生预览、五类 Tag、单个/多选/全部复制、提示词格式、单图保存分享 | 复制文本精确；原图缓存复用；一次返回仅退一层 |
| 4：收尾交付 | 相关回归、构建、文档、手工实机检查清单 | 自动验证通过；真实账号/代理/手机验证状态分别记录，不互相冒充 |

第一阶段版本指以上四个小阶段共同形成的可用版本，而不是只有一个能打开的空页面。后续再考虑收藏读取、标签联想、视频、批量下载和「选中 Tag 发送生成页/Agent」；后者只允许用户显式操作，不后台改写提示词。

## 8. 相关测试与验收清单

优先纯函数、MockWebServer 与现有 Compose/Robolectric 测试，不做与该功能无关的大范围测试；真实账号与代理由可用环境另做少量只读检查。

| 场景 | 具体断言 |
| --- | --- |
| 旧版本来源迁移 | 旧 server/local 各自保持；无新配置时不误跳 Danbooru；本地分类/删除仍只作用于本地 |
| JSON 与缺字段 | 五类 Tag 正确；URL/null/未知字段/空数组不崩溃；原图不可用时仍可复制 Tag |
| Tag 格式 | 原始下划线保留；提示词逗号和转换准确；去重、组顺序、多选、空选择、复制反馈可验证 |
| 认证状态 | 有效账号、无效 Key、匿名 200、受限 Key、超时分别呈现；保存与连接测试反馈不混淆 |
| 代理覆盖 | 测试代理确认 API、缩略图、预览、原图/分享都经过代理；407 有明确提示；不影响 Civitai/ComfyUI |
| 分页正确性 | 模拟至少 5 页且超过首屏容量；b 游标推进、去重、错误后重试、不假到底；非 ID 排序不混用游标 |
| 竞态与限流 | 快速提交新搜索、切换账号/代理时旧结果被丢弃；同游标只在途一次；429 重试有上限 |
| 缓存计数 | 分别统计 thumbnail/sample/original；原图首次 1 次、第二次分享/保存新增 0 次；Activity 重建后仍命中 |
| 返回与重建 | 设置→详情→列表每次一层；取消预测返回不跳页；回列表保留位置；页面退出无主线程网络清理 |
| 失败与媒体类型 | 401/403/404/407/410/429、TLS、DNS、超时分别映射；视频/缺图有降级界面且 Tag 可用 |
| 凭据与缓存边界 | 请求记录只检查占位凭据；Key 不进入 URL/导出；退出只清本账号在线缓存；重定向不带认证到 CDN |
| 既有功能回归 | 服务器/本地预览保存分享仍复用缓存；Agent 离线词库、生成通知和底栏动画行为不改 |

Android 构建/回归命令（在 `C:/Users/x/Documents/ChatGPT/Android/app-reconstructed` 执行）：

    gradlew.bat :app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain

测试结果须区分：本地模拟通过、公开 API 读取通过、真实 Key 验证通过、真实代理通过、真机手势通过。原计划编写时未执行实现验收；本轮实际结果记录于第 13 节，不把模拟环境与真实手机验证混用。

## 9. 主要风险与处理

| 风险 | 计划处理 |
| --- | --- |
| 搜索受账号限制被误报无结果 | 显示站点原意的可读错误；保留查询，不擅自删 Tag 重搜 |
| 用户以为缓存又失效 | 明确预览/原图区别，按表示统计下载次数；只承诺未淘汰的相同原图复用 |
| 部分资源 URL 缺失或不可见 | 字段允许空；Tag 仍可阅读；提示图片不可用，不拼接猜测地址 |
| 非 ID 排序深分页边界 | 明确提示站点边界，不更改排序假装继续 |
| 设置变更旧请求晚到 | 请求代次＋协程取消；旧结果不可写入新状态 |
| 回退直接退出/双返回 | 单一返回动作所有者；单层消费；专门回归预测返回和详情返回 |
| 页面和主 ViewModel 继续膨胀 | 新业务独立模块；既有画廊只做来源接入，不趁机大重构 |
| 多一份网络加载器造成缓存割裂 | 稳定 Runtime、明确命名空间及同一原图缓存入口；不按页面创建加载器 |

## 10. 暂不实施的事项

- 不实现发布、评论、投票、上传、编辑 Tag 等账号写操作。
- 不导入浏览器 Cookie，不需要电脑代登，不让 Bridge 转发 Danbooru 凭据。
- 不为此变更包名、签名、生成服务协议或已修复的底栏动画。
- 不重新启用 Agent 后台 Danbooru 查词，不联网替换现有双语词库。
- 不自动安装 APK、重启远端服务或更新电脑插件。

## 11. 实施后交付要求

- APK、版本说明、适用范围及已知问题。
- 本批改动目的、相关测试命令、实际结果和未验证项。
- 账号与代理的操作说明，说明原图首次请求和后续缓存复用的差异。
- 保留现有功能的验证记录；真实服务验证所用请求不含可泄露凭据。
- 本轮按用户明确请求实施并构建；不自动安装 APK、重启电脑服务或更新 Bridge。

## 12. 参考资料与核验状态

查阅日期：2026-10-17。优先使用 Danbooru 官方站点与官方源码；Wiki 资料来自官方 safebooru 子域的同名 API 文档，不表示客户端改连该子域。仓库 master 链接会变化，正式实施时应记录实际核验的源码提交。

- [S1] 官方 API 总览：认证、速率、分页、URL 参数与错误码。
  `https://safebooru.donmai.us/wiki_pages/help:api`
- [S2] 官方帖子 API：查询与帖子字段。
  `https://safebooru.donmai.us/wiki_pages/api:posts`
- [S3] 官方用户 API：当前用户与 profile 接口。
  `https://safebooru.donmai.us/wiki_pages/api:users`
- [S4] 官方认证实现：HTTP Basic、API Key 与权限检查。
  `https://github.com/danbooru/danbooru/blob/master/app/logical/session_loader.rb`
- [S5] 官方当前用户控制器：profile 对匿名请求同样返回用户对象。
  `https://github.com/danbooru/danbooru/blob/master/app/controllers/users_controller.rb`
- [S6] 官方 API Key 模型：控制器/动作权限与允许的出口 IP。
  `https://github.com/danbooru/danbooru/blob/master/app/models/api_key.rb`

原计划已核对工程接入点。0.0.8 实施时重新读取官方 Wiki 与源码，并成功匿名读取一次主站一般评级帖子 JSON（HTTP 200），未下载该帖图片。固定核验源码提交为 `1ff98b41a72e0a0378c1d893fd9aba6e6ce389a8`；身份认证、profile 匿名分支与 Key 动作/IP 权限均按该提交确认。真实账号和手机代理仍待联调。


## 13. 0.0.8 实施与交付记录（2026-10-17）

### 已实施

| 阶段 | 实际落地 |
| --- | --- |
| 1：接口与设置闭环 | 独立 Models/Http/Repository/SettingsStore/Runtime；官方 HTTPS Basic；profile 非匿名身份核验及 index/show 读取；单一设置入口、保存反馈、测试当前输入、退出和清缓存；API/媒体共用独立代理配置 |
| 2：列表与导航 | 类型化来源宿主、server/local 旧偏好迁移、显式搜索、一般评级默认、输入评级/排序同步、下拉与按钮刷新、连续加载、错误保留重试、ID 游标/非 ID 数字分页、410 边界提示、请求代次与取消 |
| 3：详情与 Tag | 原生预览/原图、帖子/画师/来源信息、五类 Tag、单个/跨组多选/全选组/清空/全部复制、提示词快捷复制、下划线和括号独立选项、保存/分享原始字节、无图/视频降级 |
| 4：收尾 | Android 构建、针对性模型/网络/状态/缓存/Compose 回归、APK 与补丁、原始计划备份及源码回滚演练、文档和远程 Git 备份；验证结果以本节下方记录为准 |

源码入口：

- `app/src/main/java/com/comfyui/mobile/danbooru/`：七个独立业务文件，不加回主 ViewModel。
- `app/src/main/java/com/comfyui/mobile/ui/screens/danbooru/`：列表、详情和设置。
- `app/src/main/java/com/comfyui/mobile/ui/screens/gallery/GalleryHomeScreen.kt`：轻量来源宿主与共用切换组件，不新增外层 Scaffold。

应用显示名与自动导出的文件名分别为 **ComfyPocket** 和 **ComfyPocket.apk**，包名仍为 `com.comfyui.mobile.rebuilt`。不换签名，不改底栏切换动画。
Agent 的「通用 Tags / Anima 混合 / 自然语言」三个格式控件及偏好已删除；按用户需求、模型和自定义分镜组织提示词，并保留自然语言、图中文字与模型语法。正反向能力和 240,851 项双语离线词库不变；Agent 没有自动联网查 Danbooru。

### 如何使用

1. 打开 **画廊 → Danbooru**。默认一般评级，填写 Tag 后点击搜索或键盘搜索，不逐字发请求。
2. **设置**中用户名与 API Key 同时填写；匿名则同时留空。Key 最小只读动作：`users:profile`、`posts:index`、`posts:show`。
3. HTTP 代理填写 `http://主机:端口`，用户名/密码单独填写。手机本机代理用 `127.0.0.1`；电脑代理需用电脑局域网 IP。API、缩略图、预览、原图/分享均经这一配置。
4. 「测试当前输入」不保存；成功后仍需点击「保存」。保存成功会有明确提示。普通选项和凭据放入一个加密配置信封，以 SecretStore 的原子文件事务提交，避免新用户名搭配旧 Key；不复制到普通偏好或导出配置。
5. Key 的出口 IP 限制需匹配代理出口；登录只确认原有权限，不提升账号等级。测试会核验非匿名用户名，再读取帖子列表和单帖；空列表时不会声称已验证单帖权限。
6. 点击帖子查看 Tag。单击原词复制、长按进入多选；可跨组全选、清空或复制全部。快捷提示词默认通用＋角色，其他三组由用户勾选。默认不转义括号，原始模式总是保留下划线。
7. 预览和原图是不同质量；第一次获取原图可能发生独立请求。之后相同原图的分享、保存和查看使用原始字节缓存，未淘汰时复用，不重压 bitmap。

### 分页、缓存与返回的实际边界

- 每页 40 条，前端在内存保留最多 8 个页块，而非限制总浏览量；继续下拉可无限推进到服务端边界，顶部「加载前一页」可按保存游标重载已淘汰的页块。测试覆盖连续 12 页及双向窗口恢复。
- API 页块独立 16 MiB 磁盘 LRU；媒体独立 128 MiB LRU。原图临时分享文件另有 24 小时/128 MiB 清理，不影响生成图缓存或系统相册。
- 来源、查询、页窗口、滚动和详情 ID 支持保存/恢复；密钥不进入 SavedState。Runtime/ImageLoader 为应用级对象，配置轮换与连接关闭在 IO 线程。
- API 串行且启动间隔至少一秒，图片最多四个响应体并发。429 最多自动重试两次，尊重秒数/日期形式的 Retry-After；401/403 不循环重试。错误不被当作空页。
- 设置平台弹层独占返回；其余返回依次退出多选、详情，列表根返回交现有导航。预测返回取消不提交跳转。没有改动应用根退出规则。
- 视频只提供封面、Tag 与原帖入口；不做播放、批量下载或账号写操作。不提供 Tag 中文翻译回显；英文原词不冒充中文注释。

### 本轮实际验证

<!-- RESULTS_START -->
- Android 构建命令：`gradlew.bat :app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain`，已成功。
- 原基线 323 项、新版 377 项 JVM/Android/Compose 测试全部通过（新增 54 项）；覆盖至少五页真实 MockWebServer 读取、连续 12 页窗口推进/回载、重复/空/失败页、旧搜索竞态、429 上限、身份/保存状态、复制格式、来源迁移、并发原图复用、系统返回与预测返回取消。
- API/媒体的 HTTP 代理路由及代理认证使用本地 MockWebServer 占位请求；这不是用户手机上的 HTTPS CONNECT 或实际代理联调。
- 主站匿名 `/posts.json?limit=1&tags=rating:g` 返回 HTTP 200；官方 Wiki 与固定提交源码读取成功，未请求该帖媒体。
- 240,851 项离线词库文件不变，APK 交付检查会再次核对打包内容、显示名、包名、版本及基线签名证书。
- 本地交付目录为 `C:/Users/x/Documents/ChatGPT/Android/artifacts/danbooru-gallery/`：`ComfyPocket.apk`、`changes.patch`、`verification.json`、`rollback.py`，配套原始文件/计划备份和清单。精确命令、输入哈希、stdout/stderr、退出码、补丁回放、回滚及远程 SHA 核验结果均写入交付验证记录，而非提交构建缓存或 APK 到 GitHub。
- 补丁以保存的原计划为基线，不丢失用户原文；回滚脚本先核对所有目标和原件哈希，再恢复本批文件，不重写 Git 历史。演练仅在隔离副本执行，工作区保留新版。
<!-- RESULTS_END -->

真实 Danbooru 用户名/API Key、手机上的代理、真机 UI/手势、相册写入和系统分享交互尚未联调；模拟测试不代表这些实机路径已通过。没有自动安装 APK、更新插件或重启 ComfyUI。

## 14. 0.0.9 排序与界面优化（2026-10-17）

### 排序与榜期

- 工具栏直接提供最新、热度、排行榜、评分、收藏；评级通过右侧入口选择，搜索框仍接受 Danbooru 语法。
- 热度使用服务端 `order:rank`，不是把当前页按点赞数重排；评分和收藏分别使用 `order:score`、`order:favcount`。
- 日／周／月榜沿用官方 popular 控制器的 `status:active order:score date:开始..下一期开始` 查询。
  周榜按周一起算，月榜按自然月；日期以站点的 `America/New_York` 时区为准，可翻看上一期、下一期或回到本期。
  评级和用户标签继续参与查询；有附加筛选时，结果为该筛选内的榜单。
- 最新仍用 ID 游标，其他排序和排行榜使用数字页；保留 API 给出的次序，不在手机上二次按 ID 排序。
  切换排序／榜期重置分页；排序、榜期与日期随查询恢复。手写 `order:` 优先，点选排序只替换排序词，不删用户标签。

### 视觉与交互

- 列表使用双列图片瀑布流，按图片比例布局，图片下方不再显示帖子编号、评级或视频文字。
  高密度屏优先读取清晰预览，并与详情页复用同一 sample 缓存。
- 移除常驻账号、分页调试说明和大块搜索／刷新按钮，使用统一主题色、紧凑搜索与排序工具栏。
- 下拉刷新改用 Miuix 0.9.3 的 `PullToRefresh`，提供拖动、松手、刷新与结束动画。
  刷新时保留已有图片；失败保留页窗口并提示重试，成功后原子替换首屏。
- 设置采用应用同款 Miuix 弹层，分「连接／偏好」，缩短字段标签、折叠连接说明，保存／取消固定在底部。
- Tag 页保留单击复制、长按多选与跨组选择；复制格式采用小型分段控件，转换与附加分组选项默认收起。
  分组标题、Tag 与复制操作缩小到统一层级；图片元信息仅放在详情中。
- 本批没有改动底栏动画、Agent、Bridge、账号加密存储或原图保存分享逻辑。

### 验证与边界

- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain` 构建成功；
  394 项 JVM／Android／Compose 测试全部通过，0 失败、0 错误、0 跳过，比原基线新增 17 项。
- 匿名访问官方 API 的热度第一页／第二页以及日／周／月榜均返回 HTTP 200；热度页正常推进，榜单返回评分降序。
  仅读取 ID、评分与时间等 JSON 字段，没有请求实际帖子的媒体。
- 回归覆盖服务端排序参数、数字分页、日历边界、榜单独立缓存、状态恢复、刷新保留／失败／竞态、
  下拉手势和弹层单次返回，以及无编号的列表与折叠 Tag 选项。
- 深浅色截图由真实 Compose 组件在 Robolectric 原生 Skia 下渲染；使用本地绘制的风景测试图。
  该渲染器对 Miuix AGSL 的支持有限，截图测试使用库内置的圆角回退，应用运行时未关闭 Squircle。
  这些检查验证布局与功能状态，不替代用户手机上的实际动画观感、代理和系统分享测试。
- APK、补丁、验证记录、原件与可执行回滚仅保存在本地 `artifacts/danbooru-polish/`。
  精确构建命令、输入哈希、输出与退出状态、测试汇总、隔离回滚及远程备份核对结果见随附验证记录。
