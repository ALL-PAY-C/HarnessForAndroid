# 设置页

`feature/settings` 的单页滚动列表，六个区块的顺序与 id 写死在 `SettingsScreen.kt`，不支持排序或隐藏（`:212`、`:363`、`:398`、`:555`、`:651`、`:968`）。「插件设置」整块被 `if (extraSettings.isNotEmpty())` 包住，没有插件贡献配置时连标题都不渲染（`:965`）。

| 区块 | id | 内容 |
| --- | --- | --- |
| 应用与更新 | `basic` | 检查更新 `:222`、更新源 `:230`、插件 `:274`、工作区 `:281`、全文件访问 `:323`、说明文档 `:345` |
| 外观 | `theme` | 图标 / 壁纸 / 材质 / 细节，外加「恢复默认外观」`:375` |
| 会话 | `chat` | 发送与显示 `:407`、上下文与工具 `:454`、群组编排 `:477`、快捷指令与模板 `:528` |
| 通知 | `notify` | 总开关、免打扰、常驻聚合通知 `:570`、思维链展示 |
| 环境 | `env` | 运行时 `:660`、连接 `:723`、工具通道 `:775`、网络与下载 `:828`、权限与后台 `:877`、Linux 环境 `:922` |
| 插件设置 | `pluginSettings` | 插件 `config[]` 声明的项，副标题显示数量 `:970` |

## 参数链路

外观项写 DataStore（`SettingsStore.kt:4-11`），经 `ThemeTokens` 与 `LocalThemeTokens` / `LocalSurfaceStyle` 下发到 `SectionCard` / `SurfaceCard` 的描边与材质叠层（`Theme.kt:331-357`、`Components.kt:730`）。一个外观项要么落在 `ThemeTokens`，要么落在 `LocalSurfaceStyle`，两条链之外的组件读不到它；新增外观项先确认挂在哪条。

Agent 预设不在这页，入口是会话页左侧抽屉 `id = "presets"`（`AppNav.kt:216-223`）。

## 坑

- 面板材质是四档枚举不是布尔：`none` / `matte` / `liquid` / `acrylic`，默认 `matte`（`BackMaterial.kt:10-29`）。旧键 `glass_enabled` 首次读取自动迁移：`false` → `none`，`true` 与「两个键都没有」都落 `matte`（`:48-52`）。
- 雾化取 `maxOf(材质下限, 用户值)`：液态 3 dp、亚克力 10 dp 是下限，滑块只能往上加（`Material.kt:60`、`:83`、`Theme.kt:325-329`）。Android 12 以下恒为 0 dp（`Theme.kt:327`），材质仍在，只按强度降 alpha。
- 强度 `0.45–1.00` 是正文压在壁纸上的可读下限，写入与读取共用 `MIN_ALPHA` / `MAX_ALPHA`（`BackMaterial.kt:32-33`、`SettingsStore.kt:425-430`）。亚克力面板不透明度是常量 0.78f，滑块只管 matte 与 liquid（`Material.kt:42`、`:78`）；「无」全实底，壁纸不参与绘制（`Material.kt:50`）。
- 壁纸必须复制进 `filesDir/appearance` 再存绝对路径：SAF 的 `content://` 授权可能被回收，且同一文件重选后 uri 不变、`Theme` 拿 uri 当缓存键，表现为「换壁纸不刷新」（`AppearanceImages.kt:12-19`、`:28-30`）。解码按长边 1600 px 采样（`Theme.kt:247`）。
- 「恢复默认外观」整组重置图标、自定义图标、壁纸、主色调、圆角、字号、密度、材质、强度与雾化（`SettingsScreen.kt:375-389`）。
- 权限与后台四项（无障碍、通知使用权、电池优化白名单、Shizuku）只显示状态与跳转入口，不代用户开关系统权限（`SettingsScreen.kt:891-912`）。
- pip 全量集超过 300 MB 或安装失败时自动卸 `numpy` / `pandas` / `opencv-python-headless` / `lxml` 回退轻量集，日志落 `env-bootstrap.log`（`LinuxEnvBootstrap.kt:38`、`:147-159`、`LinuxSettingsController.kt:169`）。
