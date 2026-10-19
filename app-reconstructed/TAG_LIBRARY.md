# 可选离线提示词库

工程的 `tag-library/` 保存只读 SQLite 数据库、原始词表与 `manifest.json`，用于生成独立词库 ZIP 包。
**默认 APK 不包含数据库或原始词表**；仅保留 295 字节的旧版迁移元数据。当前标签 141,007 项、角色 101,952 项，共 **242,959 项**：
其中 **240,851 项有中文对照，2,108 项上游缺少中文**，保留英文、别名并明确标注待补译。
数据库约 61.4 MiB，另保留完整的参考项目原始词库数据。不打包图片或模型权重，
无需部署服务端查词插件。标签计数包含通用词、画师、作品与元数据，不把这些全算成画面描述词。

## 来源与范围

- 参考项目：<https://github.com/star-abyss/ai-tag-toolbox>
- 固定提交：`2186c68862ea12a6a191f44e23bfc1699f8149fa`
- 提取数据：`data-tags.js`、`extra-tags.js`、`character-general-tags.json`、
  `synonyms.js`、`search-keywords.js`、`characters.json`、角色 `manifest.json`、
  `specific-tags.json`、`word-decisions.json` 和 `tags-canary.json`，共 **10 份**数据文件。
- 完整原始数据按上游相对路径保存到 `tag-library/toolbox/`，
  文件字节及 SHA-256 与固定提交一致；包含 33,599 份角色档案、804 条角色专用词、
  1,031 条用词判定记录和 16,473 条模型词表记录。这些数量有交集，不相加冒充独立标签数。
- 全量导入普通词、扩展词、中文、别名、同义词、检索词、角色触发词、
  可选外貌与角色专用特征；源数据中缺少中文的词也保留，不因包体大小或翻译缺失裁掉。
- `manifest.json` 记录每个输入文件的路径、字节数、SHA-256、上游版本及生成数据库 SHA-256。
- 普通词来源文件注释提及 ffdkj、Yellow-Rush、tagcomplete、SANL 等资料；本项目不将
  参考仓库整体或所有上游数据声明为同一种许可证。
- 角色数据的上游 manifest 指向 `Laxhar/noob-wiki` 快照
  `929c972dcc8aeecde42b7cd8931afe82cd864424`，该数据集卡标注 Apache-2.0：
  <https://huggingface.co/datasets/Laxhar/noob-wiki/tree/929c972dcc8aeecde42b7cd8931afe82cd864424>。
  这个标注不延伸到角色图片、第三方模型或其他来源的词表。

### Danbooru 中英文对照扩充

- 来源：<https://github.com/ffdkj/ffdkj-Danbooru_Tag-Chinese-English-Translation-Table>
- 固定提交：`fd70c7eee13917d832107c7e3a7b135dc6b2211a`，不是运行时跟随 `main` 更新。
- 输入：`tag.sqlite` 与 `LICENSE`；原始数据库 SHA-256：
  `18859f6b3a9d1ed2a1c924de39c5ecae67f6ab023606f0647584f5900f843825`。
- 上游在这个提交提供 MIT 许可证。版权与完整许可原文保存在
  `tag-library/ffdkj-LICENSE.txt`，随独立词库包分发；该许可不覆盖其他来源。
- 上游说明收集 Danbooru `post_count >= 10` 的标签，中文由 Gemini 3 Flash 翻译并结合人工纠错。
  **它是社区整理与翻译，不是 Danbooru 官方中文库；有中文不代表翻译一定正确。**
- 固定快照原始 333,437 行。额外扩充时排除 92,353 行没有可用中文、且不属于参考项目词表的数据；
  参考项目自己的词表则完整保留（包括此前因没有中文而排除的 2,048 行）。
  去重后的总量以上述实际数据库计数为准，
  不把纯英文回显、缩写或未翻译占位符称作中文覆盖。
- 保留原有中文、别名、负面词分类及可选外貌；补充新中文翻译和英文标签对应关系。
  中英文空格 / 下划线归一化后去重。同一个中文可以对应多个不同英文概念，不武断合并歧义词。
- 中文全称、去作品括号后的角色名、明确列出的中文备选译名均写入离线索引。例如
  `机械魔法少女 → mechanical_magical_girl`、`超长辫子 → very_long_braid`、
  `初音未来（VOCALOID） → 初音未来 → hatsune_miku`。

这里只整理短标签名称、中文翻译、别名、分类、角色身份与可选外貌等事实字段。
原始 JS 词表作为不可执行数据保存，不载入或执行。保留数据文件的来源注释。
不打包图片、模型权重、聊天资料、上游程序或整篇提示词。参考项目没有统一 LICENSE 文件，
不能把其全部资产视为 Apache-2.0；再分发其他资产前需另行核对。

## 重建

工程根目录执行（Python 3 标准库，无第三方依赖）：

```sh
python tools/build_local_tag_library.py
```

脚本只下载上述固定版本，核对每个输入的 SHA-256，解析 JSON 字面量并只读访问源 SQLite，
**不执行上游 JavaScript**。也可以使用已下载的同版本资料目录：

```sh
python tools/build_local_tag_library.py --source-dir PATH_TO_TOOLBOX_SNAPSHOT --danbooru-dir PATH_TO_FFDKJ_SNAPSHOT
```

`PATH_TO_FFDKJ_SNAPSHOT` 内需有 `tag.sqlite` 和 `LICENSE`。默认更新本工程的独立词库资源；
可通过 `--output PATH` 在独立目录重建、比较数据库、manifest 与许可证哈希。
SQLite 使用稳定排序、固定页大小、无时间戳；完成后执行完整性检查。导入规则测试：

```sh
python tools/test_build_local_tag_library.py -v
```

## 生成可选安装包

不需要联网，直接复用仓库已有的完整固定快照：

```sh
python tools/pack_local_tag_library.py
```

输出 `build/outputs/tag-library/ComfyPocket-tag-library.zip`。ZIP 根目录包含 `manifest.json`、
`catalogue-v1.db`、许可证和完整 `toolbox/` 原始数据；生成过程校验数据库计数与源文件哈希，
使用固定顺序及 ZIP 时间戳。Android 只安装查询数据库和清单，原始数据保留在 ZIP 中供追溯。
APK 常规构建不运行下载脚本，也不打包 `tag-library/`。查询测试使用仓库资源作为外部测试夹具。

## 使用方式和边界

- **Agent 设置 → 可选资源 → 本地词库 → 导入词库**：使用系统文件选择器选择 `ComfyPocket-tag-library.zip`。
- 默认未安装且不启用查词；安装成功自动启用。绘图前查词开关立即保存并给出系统 Toast 确认。
- 已安装时可展开普通词 / 角色查询、更新词库或确认移除；未安装时隐藏无效查询控件，不逐轮弹提示。
- 导入在 IO 线程执行，校验清单、数据库大小、SHA-256、SQLite 完整性及条目数，校验完成后原子切换。
  取消选择不改变状态；导入失败或读取取消保留原有词库，并清理临时文件。
- 已安装文件保存在应用私有 `noBackupFilesDir/tag-library`，升级 APK 会保留；卸载或清除应用数据会移除。
  旧版已经解压的当前完整词库自动识别并沿用；旧版从未初始化的设备需手动导入。
- 移除词库只影响离线查询，不删除人设、分镜、聊天或模型配置。
- 查询在 IO 线程执行，手动输入防抖并取消过时结果；普通词、角色索引相互隔离。
- 页面数量读取已安装资源包的 manifest，不再硬编码旧词库数量。
- 每轮绘图前从当前要求检索有限候选，把候选作为数据交给同一次模型请求；
  由 Agent 主动编写 `prompt` 与 `negative_prompt`，不是把结果机械拼接，也不是固定补一串负面词。
- 中文整句检索中文词义与别名；候选同时携带英文标签与中文含义。支持雨、雪等单字概念，
  单字命中不扩散到角色 / 画师身份。包含歧义或误译时以用户本轮要求为准。
- 删除聊天里的“本地词库命中…详情 / 收起”面板；后台检索和设置页手动查词继续保留。
- 没有额外的提示词格式挡位；遵循用户指定的 tags、混合短句或自然语言格式和当前模型需要。
- 候选词库并不覆盖所有模型。未命中或读取失败不会删除自定义词、LoRA 触发词、权重和专用语法。
- 角色身份与可选外貌分开；不为 OC 绑定现成角色，不把整套外貌属性强制注入。
- 101,952 项角色词库完整保留在独立资源包，不按安装包体积裁剪。保留有来源的触发词与可选外貌，
  只有身份标签的新角色明确显示“未附带外貌资料”，不凭空补写人物设定或角色图片。
- 本地查词不联网、不调用 LLM；使用 Agent 编写提示词仍需要用户配置的模型服务。
- 本版撤掉 Danbooru HTTP 查询、独立查词代理、自动修订和刷新入口；Civitai 的代理 / Key 设置不变。

## Agent 默认提示词

借鉴参考项目 `10-主AI固定提示词-PRIMARY_AGENT.txt` 的任务调度分层，以及
`09-固定生成Tag子代理-GENERATE_TAGS_AGENT.txt` 的概念标签 / 空间与动作绑定写法，
重新编写适合 ComfyPocket 的默认人设和分镜，而不是复制其整个提示词。
用户本轮明确指令优先于默认风格、历史画面和词库候选；完整保留用户要求，
不擅自删改主题，不以个人偏好无故拒绝或说教。
保留本 App 的 `reply/prompt/negative_prompt/should_draw` 契约，
不加入参考项目专有但本 App 未实现的工具调用，也不保证改变模型服务本身的限制。

升级只替换与旧内置默认值完全一致的已保存文本，并在本地偏好保存旧文副本；
用户手动编辑过的人设和分镜原样保留，可在设置中选择恢复默认后使用新版。
