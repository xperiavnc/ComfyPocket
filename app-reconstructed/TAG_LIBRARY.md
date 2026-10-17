# 内置离线提示词库

本目录的 Android 资源 `app/src/main/assets/tag-library/` 包含只读 SQLite 数据库与
`manifest.json`。当前普通词 13,358 项，角色 34,122 项，数据库约 14.9 MiB；
压缩后的 APK 增量小于原始数据库大小。无需部署服务端查词插件。

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

这里只整理短标签名称、中文翻译、别名、分类、角色身份与可选外貌等事实字段。
不打包图片、模型、聊天资料、上游程序或整篇提示词。参考项目没有统一 LICENSE 文件，
不能把其全部资产视为 Apache-2.0；再分发其他资产前需另行核对。

## 重建

工程根目录执行（Python 3 标准库，无第三方依赖）：

```sh
python tools/build_local_tag_library.py
```

脚本仅下载上面的固定版本，解析 JSON 字面量，**不执行上游 JavaScript**。也可以使用已下载的
同版本资料目录：

```sh
python tools/build_local_tag_library.py --source-dir PATH_TO_PINNED_SOURCE
```

默认覆盖本工程的生成资源；可通过 `--output PATH` 在独立目录重建、比较 manifest 与哈希。
SQLite 使用稳定排序、固定页大小、无时间戳；完成后执行完整性检查。

## 使用方式和边界

- **Agent 设置 → 提示词格式与本地词库**：开关、格式选择、普通词 / 角色分开的中英文搜索。
- 查询在 IO 线程执行，手动输入防抖并取消过时结果；普通词、角色索引相互隔离。
- 每轮绘图前从当前要求检索有限候选，把候选作为数据交给同一次模型请求；
  由 Agent 主动编写 `prompt` 与 `negative_prompt`，不是把结果机械拼接，也不是固定补一串负面词。
- 候选词库并不覆盖所有模型。未命中或读取失败不会删除自定义词、LoRA 触发词、权重和专用语法。
- 角色身份与可选外貌分开；不为 OC 绑定现成角色，不把整套外貌属性强制注入。
- 本地查词不联网、不调用 LLM；使用 Agent 编写提示词仍需要用户配置的模型服务。
- 本版撤掉 Danbooru HTTP 查询、独立查词代理、自动修订和刷新入口；Civitai 的代理 / Key 设置不变。
