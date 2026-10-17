# Agent 第一阶段

版本：`0.0.2-agent-core`，versionCode `4`。本批替换执行内核，保留原来的聊天、
提示词扩写和自动出图操作，不调整底栏、动画、导航或集市。

## 思考强度

入口：**Agent 设置 → 模型 API 配置 → 思考强度**。选择后点击“保存并应用此配置”。

| 档位 | Chat Completions 请求值 |
| --- | --- |
| 跟随模型（旧配置默认） | 不传 `reasoning_effort` |
| 关闭 | `none` |
| 极低 | `minimal` |
| 低 | `low` |
| 中 | `medium` |
| 高 | `high` |
| 极高 | `xhigh` |
| Max | `max` |

每套模型配置独立保存，聊天和画图均使用该配置。一轮发送时即固定 URL、模型、
Key、档位、人设、画图规则、角色档案和上一轮提示词，后台任务不再读取漂移中的设置。
显式选择档位时不再同时发送固定 `temperature`；“跟随模型”保留原有兼容请求。

参数依据 OpenAI Chat Completions 官方参考（2026-10-17 核对）：
https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create

**支持哪些档位由具体模型和服务商决定。** 兼容接口不一定实现全部值；本批不模拟、
不伪装“Max”，也不自动降档或为错误请求重发。接口报不支持时，可换成该模型支持的档位，
或选“跟随模型”。尚未对用户的付费服务或实际模型逐一验证。

## 内核职责

- `agent/AgentSessionController.kt`：实例级 `AgentSessionState` / `AgentTurn`；
  发送前同步占用 busy、唯一请求身份、阶段与结束状态，避免连点重复发送。
- `agent/AgentConversationRunner.kt`：生成回复 → 应用提示词 → 可选提交三个步骤；
  `AgentDrawExecutor` 不负责调用 LLM，生成提示词本身也没有提交副作用。
- `ui/MainViewModelAgentRuntime.kt`：每个 ViewModel 的 Android 适配器，负责消息、
  附件、工作流加载、偏好设置和现有本地/RH 提交路径。
- `data/LlmClient.kt`：按 ID 管理实际 OkHttp Call；结束释放索引；协程取消绑定请求；
  连接/读取超时与 240 秒总时限分开报告；空流、格式异常、截断不再触发静默重发。
- `agent/AgentTool.kt`：typed `AgentTool` 接口及 `inspect_workflow`、`patch_workflow`。
  目前供内核调用，不要求模型支持原生 tool calling，也不把模型文字当任意命令执行。

`patch_workflow` 先整体校验再复制修改，返回字段 diff。修改能力来自提示词节点、
本轮图像节点和用户勾选的辅助参数；未知字段、连线、类型、范围和枚举错误使整批修改失败，
失败后不提交。`class_type`、节点拓扑和其他字段保持不变。

停止、清空聊天、切换服务器/模型配置/模板会使旧轮次失效；流式回调、最终结果与清理都按
turn ID 校验，旧任务不会覆盖新会话。独立的模板异步加载还有上下文代次和载入票据，
避免慢文件读取覆盖新模板。消息列表原子更新，进度刷新节流并在最终结果完整落盘。

**停止的边界**：停止本轮 LLM 和尚未提交的步骤；已被 ComfyUI / RunningHub 接收的任务
可能继续执行，本批没有新增远端任务取消工具。

## 功能回归边界

新增测试覆盖会话占用/隔离/迟到回调、SSE 与实际 socket 取消、超时状态、全部档位的请求体、
配置保存与切换、工作流检查/修改，以及同一生产 runner 的 Fake LLM 链路。
Android 适配器测试使用真实 ViewModel、文件加载、回复解析、偏好设置、工具和本地 HTTP
模拟服务，覆盖聊天、只写提示词、自动出图、附件上传、停止及切换；Compose 测试实际点击
“极高”和“Max”。Android Keystore 加密不属于本批测试，适配器夹具使用空存储 Key，
请求阶段注入仅用于本地模拟服务的 token。

这些是功能回归，不等于真机手势/画面性能或真实 ComfyUI/RH/付费模型验证。
没有新增依赖、权限、原生 LLM actions 协议、确认卡、多会话 UI 或自动重试。

## 0.0.3 体验修复与主页资源预览

版本 `0.0.3-agent-media-monitor`（versionCode 5）：

- 生成进度、完成、失败均使用新的低优先级静默通知通道，关闭声音、振动、灯光、
  角标、悬浮和提升展示请求；旧安装的高优先级通道也会迁移。停止按钮和前台服务通知 ID 保持不变。
- 模型配置保存并应用后显示包含配置名称的确认提示；保存异常显示失败提示，
  人设和画图规则原有的保存提示继续保留。
- 图片加载器由 Application 统一创建，Activity 重建不再创建第二套磁盘缓存。
  分享先使用已有本地文件，再复用 Coil 缓存中的编码原图，不从缩略图重新编码。
  首次分享时若缓存缺失，只下载一次并写入同一个缓存；相同 URL 的并发分享合并。
  Gallery / Agent / 全屏预览仍以完整 URL 为键，服务器、子目录和查询参数保持隔离。
- 缓存沿用 256 MiB LRU；分享临时文件沿用 30 分钟清理。系统清缓存、手动清理或
  缓存淘汰后再次下载属于预期行为，不把临时缓存当永久相册。
- 主页本地 ComfyUI 系统状态新增 CPU 使用率、内存占用百分比和已用 / 总量 GiB；
  内存直接读取原生 `/system_stats` 的 `ram_total` / `ram_free`，不依赖监控插件。
  CPU 优先显示 Crystools 实时数据，也支持 Bridge 2.1 的 `/api/mobile/system/monitor`。
  缺失数据明确显示“暂无数据”，不把未知当作 0%。
- 主页可见并处于前台时每 5 秒刷新系统指标，不随每次采样扫描模型库。
  Bridge 采样使用 ComfyUI 电脑的 psutil，在工作线程测量 100 ms，不阻塞服务事件循环。
  RunningHub 页面继续使用原有云端任务数据，不显示手机硬件代替云端资源。

本批相关回归：通知 API 35 / 36、设置保存、原图字节与 HTTP 请求计数、
应用级缓存、CPU/内存映射及 Compose 展示；Bridge 另测工作线程采样和实际主机样本。
Windows 上的缓存测试仅替换 AndroidX FileProvider 的 URI 边界，
HTTP、Coil 读写、分享文件字节均为真实执行；尚待真机分享及 OEM 通知效果复测。

CPU 监控需更新并重启电脑上的完整 Bridge 2.1 插件（或保留已有 Crystools）。
本批只验证配套插件源码和本地采样，远端插件部署及真机显示仍待更新后复测。

## 0.0.4 提示词职责拆分与 Danbooru 核对

版本 `0.0.4-agent-prompts`（versionCode 6）；不改 Bridge、导航和底栏动画。

### 提示词与格式

入口：**Agent 设置 → 提示词格式与标签核对**。人设只定义 reply 的语气；分镜只定义
画面内容、连续性、主体属性归属和可见表情；外层 JSON 由 App 的统一契约负责。

- 通用 Tags：兼容已有标签工作流。
- Anima 混合：英文 tags + 必要的空间/属性绑定短句，保留段落、标点和引号。
- 自然语言 / 反推：连贯描述可见画面，不臆测身份、相机或画师，反推本身不自动出图。

参考资料中的 XML、regex、image### 等宿主包装不会成为 App 的输出协议。
外部示例文件保留在用户本地，不随源码提交；内置规则仅提炼可见状态、属性绑定与格式分工。
未对 Anima 真实生成效果做保证。仅完全匹配旧内置默认值才升级，旧值保存在本地偏好副本；
用户改过的提示词保留。所选格式优先于自定义规则中的旧格式约定，仍可手动编辑或恢复默认。

### Danbooru

默认开启，独立 HTTP 代理默认关闭/直连，不借用或修改 ComfyUI、集市、LLM 的客户端。
自动模式优先使用 Chat Completions 函数工具；明确收到 400/422 的工具不支持错误时，
只进行一次兼容初稿请求。也可直接选“兼容核对”。模型忽略工具时由 App 从初稿提取候选。
UI/记录区分“模型请求查询”和“App 兼容核对”。

只执行受限的 search_danbooru_tags（短查询词和结果上限），不搜索图片、帖子或用户。
一轮最多一次查询阶段、最多三个未缓存 GET、一次最终修订；优先一个待确认关键词，
多词共享预算，重复规范化词合并；预算用尽的项标为未完成，不把截断结果当作不存在。
初稿与查询阶段都没有工作流提交副作用，最后沿用现有 patch/submit，最多提交一次。

搜索：search[name_or_alias_matches]，按 search[order]=count 排序，精确名称优先。
别名只接受 active 的 antecedent_name → consequent_name，模糊候选不直接替换。
Wiki 用标题路径，独立“无 Wiki”状态，摘要只取定义段并限制到 600 字符。
查询失败/超时/限流/解析异常与空结果区分；保留未知、自定义、权重、转义和 LoRA 语法。

每次未缓存请求至少间隔 1 秒；429 遵循 Retry-After，本轮不重试。标签/别名缓存 24 小时，
Wiki 缓存 7 天，以 API 类型、规范化词和独立代理摘要隔离，最多 200 项 / 2 MiB。
缓存位于 cacheDir，应用现有清缓存入口可清除；不存 Danbooru 账号或 API Key。
对话可展开候选、类别、作品数、别名、Wiki 与未查证状态；主动刷新只更新字典结果，
不重写提示词、不再次调用 LLM、不重新出图。停止或切换上下文后的旧查询不写入新轮次。

2026-10-17 对正式 API 执行了三次 GET，标签、别名、Wiki 均返回 200，并将公共响应样本
用于生产解析器回归。单元/Compose/MockWebServer 测试覆盖两种模型路径、缓存与频率、
设置保存、正文语法保留、查询中止、刷新不重提任务；没有调用付费 LLM 或真实出图服务。
主页 CPU、显存、内存进度条统一使用主题 primary 色。

官方接口参考：
- https://safebooru.donmai.us/wiki_pages/api:tags
- https://safebooru.donmai.us/wiki_pages/api:tag_aliases
- https://safebooru.donmai.us/wiki_pages/api:wiki_pages
- https://platform.openai.com/docs/guides/function-calling
