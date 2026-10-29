# 更新与分发

`update.json` 放仓库 `main` 根目录，应用首屏由 `AutoUpdatePrompt` 在 `LaunchedEffect` 里静默拉一次（`AutoUpdatePrompt.kt:56-70`）；网络失败只记 `Log.w`，不弹错误框（`:61-64`）。`versionCode` 大于已装值才算有新版（`Updater.kt:97-98`、`UpdatePromptPolicy.kt:9-10`）。同一版本只打扰一次，点「稍后」与点对话框外面是同一件事，都写 `key_prompted_version`（`UpdatePromptPolicy.kt:16-24`、`UpdatePromptPrefs.kt:7-19`）。

## 清单字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `versionCode` | Long | 唯一比较依据；缺失或 ≤ 0 时 `check()` 抛「清单里没有有效的 versionCode」（`Updater.kt:95`） |
| `versionName` | string | 只用于展示与下载文件名 |
| `apkUrl` | string | 主下载地址；空则抛「清单里没有 apkUrl」（`Updater.kt:96`） |
| `apkMirrors` | string[] | 排在主地址之前逐个试，空串剔除（`Updater.kt:85-87`） |
| `notes` | string | 弹窗正文，超过 200 字截断（`AutoUpdatePrompt.kt:32`、`:95`） |

`UpdateInfo` 定义在 `Updater.kt:26-33`，解析在 `:84-96`。

## 更新源

内置四个候选，顺序即回退顺序（`Updater.kt:194-202`）：`gh-proxy.com` 反代、`ghfast.top` 反代、`cdn.jsdelivr.net/gh/<repo>@main/update.json`、`raw.githubusercontent.com` 直连。反代排前面是因为国内直连 raw 的握手常挂到超时（`:193` 注释）。

自定义源存在 `SharedPreferences("dsh.update")` 的 `source` 键，不是 DataStore（`Updater.kt:37`、`:211`）；非空时整份候选列表被它顶掉（`:43-46`）。

## 下载

候选顺序：清单里的 `apkMirrors` → 四个反代前缀 + `apkUrl` → `apkUrl` 原地址（`Updater.kt:134-142`）。前缀不判断域名，`apkUrl` 不是 GitHub 地址也照样被加一遍，所以清单里不要填已经带前缀的地址。四个前缀是 `gh-proxy.com`、`ghfast.top`、`hk.gh-proxy.com`、`ghproxy.net`（`:205-210`）。

落 `cacheDir/update-<versionName>.apk`，小于 1 MB 视为不完整换下一个地址（`:108-118`）。连接超时 15 s、读超时 60 s，`User-Agent` 固定 `dsh-mobile-updater`（`:145-150`）。安装走 `FileProvider` + `ACTION_VIEW`，首次会弹「允许安装未知应用」（`:124-131`）。

## 发布

1. 改 `app/build.gradle.kts` 顶部的 `appVersionName`（当前 `v0.01`）与 `appVersionCode`（当前 88）（`:161-162`）。这两个值是唯一来源，`defaultConfig` 与 APK 文件名都读它们（`:160`、`:174-175`）。
2. `./gradlew assembleRelease`，产物名 `HarnessForAndroid-release-<appVersionName 空格前第一段>.apk`（`:164`、`:281`）。
3. 建 tag、传 APK，再改 `update.json` 的 `versionCode` / `versionName` / `apkUrl` / `apkMirrors` / `notes`（现网清单就是仓库根那份）。

`versionCode` 只能往上加：88 是接着上一代 0.07 的 87 排的，降号会让已装的旧包盖不上新包（`CHANGELOG.md:7`）。清单先改、APK 还没传，会把所有已装用户卡在 404 的弹窗上。

## 签名

三份签名材料都在版本库里：`app/keystore/dsh-debug.jks`、`app/keystore/app-release.jks`、`app/keystore.properties`。`.gitignore` 先用 `*.jks` 全排除，再用 `!` 逐个反选回来（`.gitignore:36-42`）。

`keystore.properties` 存在且 `storeFile` 指向的文件在时用 `releaseSelf` 签；缺了就退回仓库内的 debug 证书 `local`（`app/build.gradle.kts:151-158`、`:189-205`）。debug 与 release 共用同一份 `sharedSigning`（`:201-205`、`:211`、`:217`），但 debug 带 `applicationIdSuffix = ".debug"`，是两个独立包，各自升级、互不覆盖（`:199-209`）。换成别的证书签的包会与已装包签名冲突，只能先卸再装（`:180-182`）。
