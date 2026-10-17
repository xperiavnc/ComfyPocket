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
