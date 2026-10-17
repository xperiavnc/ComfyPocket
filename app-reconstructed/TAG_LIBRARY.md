# 内置离线提示词库

本目录的 Android 资源 `app/src/main/assets/tag-library/` 包含只读 SQLite 数据库与
`manifest.json`。当前标签 140,871 项、角色 99,980 项，共 **240,851 项，全部有中文对照**，
数据库约 59.3 MiB。标签计数包含通用词、画师、作品与元数据，不把这些全算成画面描述词。
不打包图片或模型，无需部署服务端查词插件。

## 来源与范围

- 参考项目：<https://github.com/star-abyss/ai-tag-toolbox>
- 固定提交：`2186c68862ea12a6a191f44e23bfc1699f8149fa`
- 提取数据：`data-tags.js`、`extra-tags.js`、`character-general-tags.json`、
  `synonyms.js`、`search-keywords.js`、`characters.json` 和角色 `manifest.json`。
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
  `app/src/main/assets/tag-library/ffdkj-LICENSE.txt`，随 APK 分发；该许可不覆盖其他来源。
- 上游说明收集 Danbooru `post_count >= 10` 的标签，中文由 Gemini 3 Flash 翻译并结合人工纠错。
  **它是社区整理与翻译，不是 Danbooru 官方中文库；有中文不代表翻译一定正确。**
- 固定快照原始 333,437 行。导入时排除 93,457 行没有可用中文、且原有词库也没有中文对应的数据；
  原有角色先补译，再排除仍无中文的 2,048 行。去重后的总量以上述实际数据库计数为准，
  不把纯英文回显、缩写或未翻译占位符称作中文覆盖。
- 保留原有中文、别名、负面词分类及可选外貌；补充新中文翻译和英文标签对应关系。
  中英文空格 / 下划线归一化后去重。同一个中文可以对应多个不同英文概念，不武断合并歧义词。
- 中文全称、去作品括号后的角色名、明确列出的中文备选译名均写入离线索引。例如
  `机械魔法少女 → mechanical_magical_girl`、`超长辫子 → very_long_braid`、
  `初音未来（VOCALOID） → 初音未来 → hatsune_miku`。

这里只整理短标签名称、中文翻译、别名、分类、角色身份与可选外貌等事实字段。
不打包图片、模型、聊天资料、上游程序或整篇提示词。参考项目没有统一 LICENSE 文件，
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

`PATH_TO_FFDKJ_SNAPSHOT` 内需有 `tag.sqlite` 和 `LICENSE`。默认更新本工程的生成资源；
可通过 `--output PATH` 在独立目录重建、比较数据库、manifest 与许可证哈希。
SQLite 使用稳定排序、固定页大小、无时间戳；完成后执行完整性检查。导入规则测试：

```sh
python tools/test_build_local_tag_library.py -v
```

## 使用方式和边界

- **Agent 设置 → 提示词格式与本地词库**：开关、格式选择、普通词 / 角色分开的中英文搜索。
- 查询在 IO 线程执行，手动输入防抖并取消过时结果；普通词、角色索引相互隔离。
- 页面数量直接读取打包的 manifest，不再硬编码旧词库数量。
- 每轮绘图前从当前要求检索有限候选，把候选作为数据交给同一次模型请求；
  由 Agent 主动编写 `prompt` 与 `negative_prompt`，不是把结果机械拼接，也不是固定补一串负面词。
- 中文整句检索中文词义与别名；候选同时携带英文标签与中文含义。支持雨、雪等单字概念，
  单字命中不扩散到角色 / 画师身份。包含歧义或误译时以用户本轮要求为准。
- 删除聊天里的“本地词库命中…详情 / 收起”面板；后台检索和设置页手动查词继续保留。
- 三种提示词格式确实分别控制 tags、混合短句和自然语言输出，并影响解析，不是装饰性选项，
  因而保留。它们与词库的中英文索引无关。
- 候选词库并不覆盖所有模型。未命中或读取失败不会删除自定义词、LoRA 触发词、权重和专用语法。
- 角色身份与可选外貌分开；不为 OC 绑定现成角色，不把整套外貌属性强制注入。
- 99,980 项角色词库完整随 APK 打包，不按安装包体积裁剪。保留有来源的触发词与可选外貌，
  只有身份标签的新角色明确显示“未附带外貌资料”，不凭空补写人物设定或角色图片。
- 本地查词不联网、不调用 LLM；使用 Agent 编写提示词仍需要用户配置的模型服务。
- 本版撤掉 Danbooru HTTP 查询、独立查词代理、自动修订和刷新入口；Civitai 的代理 / Key 设置不变。
