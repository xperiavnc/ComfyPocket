# Material 样板：P0 / P1

## P0：兼容性基线

- 从 `c0ffa10`（0.0.9 / versionCode 11）开始，保留热度、排行榜、分页与刷新修复。
- 新增固定依赖 Material 3 `1.4.0` 和 Material Icons Core `1.7.8`。
- 不引入 Compose BOM，不升级既有 AGP、Kotlin、Compose 或 Miuix。
  实际解析为 AndroidX Material 3 1.4.0；Compose 仍沿用既有 1.11.2 Android 依赖线。
  详细 runtime classpath / dependencyInsight 保存在本机验证记录中。
- Material 颜色、排版、形状集中在 `ui/designsystem/PocketMaterialTheme.kt`；
  不读取或改写旧主题偏好，不嵌套到旧 Miuix 页面内。
- 原 394 项测试及新增 Material 组件试编测试通过，Android debug APK 构建成功。
  这只证明依赖与基础组件可运行，不代表真机性能或全部页面迁移完成。

## P1：实施边界

下一批提供独立、可退出的预览入口：Material 主题、五入口导航演示、
Danbooru 列表／详情／Tag 与账号／代理设置。
默认使用明确标注的离线固定数据，不自动连接或修改真实配置。
现有应用外壳与其他功能不替换；P2–P5 需样板确认后继续。
