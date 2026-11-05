# plugin-html-apk —— 可独立安装的 dsh.html 插件包外壳（保留）

**结论先行：本模块不是死壳，删不得。** 它是 `com.android.application`，唯一职责是把
`:plugin-html`（插件实现 library）打成**可独立安装的插件 APK**，
`applicationId = dev.dsh.mobile.plugin.html`。0 个 Kotlin 文件是设计结果，不是废弃残留。

## 一、为什么需要它

`:plugin-html` 在 T1.2 之后变成普通 `com.android.library`，有两个消费方：

1. `:app` —— `implementation(project(":plugin-html"))`，作为内置插件注册 `HtmlPlugin`；
2. `:plugin-html-apk` —— 把同一份实现包成独立 APK，走 `PackageManager.getPackageArchiveInfo` /
   `PathClassLoader` 被宿主发现并加载。

删掉本模块 = 失去"插件可单独分发、不必随宿主发版"的能力，宿主侧的外部插件加载链路就没有可安装物。
O6 里登记过的替代方案是「用 Gradle variant 替代」，该方案未启动；按 O6 的第二选项，
本模块以 README 写明存在理由的方式保留（见 `docs/REFACTOR-NOTES.md` §五）。

## 二、模块内容（全量 3 个文件）

| 文件 | 作用 |
|---|---|
| `build.gradle.kts` | `com.android.application`；写 `applicationId` / 版本号 / debug 签名 / 不 minify |
| `src/main/AndroidManifest.xml` | 空 manifest，只提供 APK 身份 |
| `src/main/assets/plugin.json` | 插件描述符：`{"entry":"dev.dsh.mobile.plugin.html.HtmlPlugin","id":"dsh.html","name":"HTML 渲染","version":"v0.08","author":"DSH"}` |

实现体量与它无关：`plugin-html/src/main/**` 才是插件代码。

## 三、关键约束

1. **版本号不写死**：`versionCode` / `versionName` 读 `gradle.properties` 的
   `dshAppVersionCode` / `dshAppVersionName`，与 `:app` 单一来源对齐（原先 `plugin-html` 自写 2.0.0/5 的双源已消除）。
2. **打包边界**：`resources.excludes += "**/dev/dsh/mobile/pluginapi/**"`，宿主那份 `plugin-api` 绝不进插件包，
   否则类型同一性破坏、插件加载时 `ClassCastException`。
3. **签名**：复用 `app/keystore/dsh-debug.jks`，产物可 `adb install` 直装。
4. **lint 关闭**：`plugin-api` 与 Compose 全是 `compileOnly`，lint 会报缺失类，release 检查关掉。
5. `plugin.json` 里的 `version` 字段是插件自身 schema 版本，与 APK `versionName` 不是一回事，改任一处都要确认另一处。

## 四、引用点（均为 include / 注释 / 文档，无代码依赖）

| 位置 | 形态 |
|---|---|
| `app/settings.gradle.kts:36` | `include(":plugin-html-apk")` |
| `app/build.gradle.kts:29` | 注释：只有 `plugin-html-apk` 是 application |
| `app/app/build.gradle.kts:200`、`:302` | 注释：版本号单一来源、独立插件 APK 由本模块产出 |
| `app/plugin-html/build.gradle.kts:9` | 注释：本模块是第二个消费方 |
| `app/gradle.properties:9` | 注释：版本号唯一来源的两个读者 |
| `app/README.md:100` | 模块表一行 |

没有任何 `project(":plugin-html-apk")` 依赖边 —— 它是叶子产出模块，只被构建调用，不被代码引用。
