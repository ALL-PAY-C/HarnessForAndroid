# 更新与分发

App 自更新 = 一份静态 JSON 清单 + 一个 APK。仓库那边改清单、传 APK，手机下次启动就知道有没有新版；不内嵌 git 客户端（`Updater.kt:26-27`）。

本文所有 `文件:行号` 都是相对仓库根（`app/`）的行号。简称对照：

| 简称 | 完整路径 |
| --- | --- |
| `Updater.kt` | `core/data/src/main/kotlin/dev/dsh/mobile/core/data/update/Updater.kt` |
| `ManifestFetch.kt` | `core/data/src/main/kotlin/dev/dsh/mobile/core/data/update/ManifestFetch.kt` |
| `ManifestCache.kt` | `core/data/src/main/kotlin/dev/dsh/mobile/core/data/update/ManifestCache.kt` |
| `UpdatePromptPolicy.kt` / `UpdatePromptPrefs.kt` | `core/data/src/main/kotlin/dev/dsh/mobile/core/data/update/` 同名文件 |
| `Downloads.kt` / `SegmentedDownloader.kt` | `core/common/src/main/kotlin/dev/dsh/mobile/core/common/download/` 同名文件 |
| `RuntimeMirrors.kt` | `core/common/src/main/kotlin/dev/dsh/mobile/core/common/RuntimeMirrors.kt` |
| `AutoUpdatePrompt.kt` | `app/src/main/kotlin/dev/dsh/mobile/app/update/AutoUpdatePrompt.kt` |
| `DownloadVerifiedArtifact.kt` | `build-logic/src/main/kotlin/dev/dsh/buildlogic/DownloadVerifiedArtifact.kt` |

## 一、清单字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `versionCode` | Long | 唯一比较依据；缺失或 ≤ 0 直接抛「清单里没有有效的 versionCode」（`Updater.kt:124`） |
| `versionName` | string | 只用于展示与下载文件名（`Updater.kt:140`） |
| `apkUrl` | string | 主下载地址，空则抛「清单里没有 apkUrl」（`Updater.kt:125`） |
| `apkMirrors` | string[] | 备用地址，排在反代候选之前逐个试，空串剔除（`Updater.kt:113-115`） |
| `sha256` | string | **可选**，给了就做内容寻址缓存 + 下载后校验（`Updater.kt:37`、`:122`、`:148`） |
| `notes` | string | 弹窗正文，展示时截到 200 字（`AutoUpdatePrompt.kt:32`、`:95`） |

`UpdateInfo` 定义在 `Updater.kt:29-38`，解析在 `Updater.kt:112-127`。判「有没有新版」只有一句：`info.versionCode > current`（`Updater.kt:127`）。

## 二、清单获取：并行竞速 + 三级回退

**一级：并行拉取。** 所有候选源同时发车，先拿到正文的胜出，其余立刻取消 —— 不是串行试（`ManifestFetch.kt:56-113`）。串行 4 源 × 20s 最坏 80s 才报错，而清单只有几 KB（`ManifestFetch.kt:44-45`）。

- 单源超时 8s（`ManifestFetch.kt:51`），总预算 12s（`ManifestFetch.kt:54`），两个都可配（见第五节）。
- 每个源失败都单独记下原因，不是只留第一个（`ManifestFetch.kt:84`）。
- 赢了就 `jobs.forEach { it.cancel() }`，不陪剩下的源跑满超时（`ManifestFetch.kt:100`）。

**二级：内存。** 同一进程里刚拿到的正文，磁盘那次写失败也还在（`ManifestCache.kt:38`）。

**三级：磁盘（TTL）。** `filesDir/update-manifest.json`，默认 6h 新鲜期（`Updater.kt:53-58`、`ManifestCache.kt:78`）。落 `filesDir` 不落 `cacheDir`，因为缓存目录会被系统清掉，而这份副本正是断网时判断新版的唯一依据（`Updater.kt:47-52`）。

回退链本身只有 11 行（`ManifestCache.kt:94-104`）：Live → Memory（`:101`）→ Disk（`:102`）→ 全挂才 `Failed`（`:103`）。「一个源挂了」不会升级成「整体失败」（`ManifestCache.kt:86-87`）。

**原子写。** 先写 `<name>.tmp` 再改名，中途被杀不会留半截 JSON（`ManifestCache.kt:59-68`）。时间戳写在首行 `savedAt=<ms>`，正文从第二行起（`ManifestCache.kt:61`、`:46-48`）。时钟回拨（age < 0）按新鲜处理（`ManifestCache.kt:74`）。

**提示策略。** 首屏 `LaunchedEffect` 静默拉一次，失败只 `Log.w` 不弹框（`AutoUpdatePrompt.kt:56-70`，具体 `:61-64`）。同一版本只打扰一次，判据是 `promptedVersionCode < target.versionCode`（`UpdatePromptPolicy.kt:16-24`），值写在 `SharedPreferences("dsh.update")` 的 `key_prompted_version`（`UpdatePromptPrefs.kt:6-7`、`:15-19`）。点「稍后」与点对话框外面是同一个 `dismiss`（`AutoUpdatePrompt.kt:73-77`）。

## 三、镜像与反代

反代前缀只有一个真源：`RuntimeMirrors`（`RuntimeMirrors.kt:14-87`）。原先硬编码在 Updater / PluginMarket / LinuxEnvBootstrap 三处，换前缀要改三个文件且三份默认值会各自漂移（`RuntimeMirrors.kt:5-9`）。

默认四个前缀，按可达性排序（`RuntimeMirrors.kt:17-22`）：`gh-proxy.com`、`ghfast.top`、`hk.gh-proxy.com`、`ghproxy.net`。清单候选 = 四反代 + jsDelivr + raw 直连（`Updater.kt:225-229`；`JSDELIVR_GH` 见 `RuntimeMirrors.kt:25`，`GITHUB_RAW` 见 `:30`）。

用户可在设置里换前缀：`RuntimeMirrors.configure` 整体替换、尾部斜杠自动补齐、空列表等于回到默认（`RuntimeMirrors.kt:36-41`、`:43-45`、`:48`）。全部用 getter 取，所以换完之后下一次 `check()` 立刻用新的一套（`Updater.kt:222-223`）。

`RuntimeMirrors.downloadCandidates` 是共享的「一个 URL 的全部候选」：GitHub 域名先套各反代再给原址兜底，非 GitHub 域名原样返回（`RuntimeMirrors.kt:66-74`）。

⚠️ 自更新这条路径用的是 `Updater` 内部的同名私有函数（`Updater.kt:169-177`），它**不判域名**：`apkUrl` 不是 GitHub 地址也照样被套一遍反代前缀。所以清单里别填已经带前缀的地址，否则会先白探几个 404。

## 四、下载器：分段 + 续传

入口是 `Downloads.download`（`Downloads.kt:103-140`），全应用唯一的落盘下载通道；自更新、插件包、npm 依赖、技能 zip、GitHub zipball、Agent 预设都走它（`Downloads.kt:95-96`）。`Updater.download` 只是备好参数（`Updater.kt:137-156`）。

1. **缓存优先。** 有 `sha256` 时强缓存命中就完全跳过网络，连镜像探测都不发（`Downloads.kt:119-127`）。
2. **并行探测镜像。** 候选地址同时发 `Range: bytes=0-0`，先返回的当主镜像（`SegmentedDownloader.kt:275-283`、`:285-300`）。206 且 `Content-Range` 能读出总长 → 支持 Range；200 → 单流兜底（`:290-294`）。
3. **分段并发。** 6 连接（`SegmentedDownloader.kt:42`、`:374`），每段不小于 512 KB，小于 512 KB 的文件不切段（`:319-330`、`:378`）。
4. **段内续传。** 单段失败立刻换下一个镜像，Range 起点 = 段起点 + 已写字节，不是整段重下（`SegmentedDownloader.kt:186-187`）。失败一次即从本轮剔除，全剔光就清空重来，最多 3 轮（`:50`、`:52`、`:144-149`、`:166`）。
5. **单流兜底。** 镜像不吃 Range 就走 `singleStream`，仍带 `.part` 续传；服务器明确不回 Range 时才从头来并把 `.part` 删掉（`SegmentedDownloader.kt:217`、`:238-247`）。
6. **进度。** 已落盘字节 / 总长，只在百分比变化时回调且保证单调递增（`SegmentedDownloader.kt:126-134`）。

## 五、sha256 与断点文件

**断点现场。** `<目标名>.part` 是数据文件，`<目标名>.part.seg` 是分段进度（`SegmentedDownloader.kt:86`、`:115`）。`.seg` 首行 `len=<总长>`，之后每行 `start:end:done`（`:351-362`）。读回时总长对不上、行数不对、或没有任何一段 `done > 0` 就整体作废（`:332-349`）。命中续传时用 `setLength` 预分配、不删状态文件（`:119-122`）。

**完成动作。** 先算 `.part` 的 sha256（`SegmentedDownloader.kt:179`），再删 `.seg`（`:180`），最后 `renameInto` 改名到目标，长度对不上就抛「文件不完整：期望 N 字节，实际 M」（`:308-317`）。

**强缓存（有 sha256）。** 缓存文件名就是 `<sha256>.bin`，也直接当下载目标用，省一次复制（`Downloads.kt:22`、`:129`）。命中判定不看文件名，重新算一遍 sha 才算命中 —— 名字可以伪造，内容不行（`Downloads.kt:13-14`、`:25-31`）。

**弱缓存（没有 sha256）。** 键 = URL 的 sha，值 = 上次落盘的大小 + 副本名，登记在 `weak.index`（`Downloads.kt:15-17`、`:43-56`、`:87`）。只保证「同 URL、同大小」，服务端在同一 URL 换成大小相同的新内容时会命中旧文件 —— 这是弱缓存的内生风险。

**校验。** 调用方给了 `expectedSha256` 才校验，不符抛「sha256 校验失败：期望 x，实际 y」（`SegmentedDownloader.kt:302-306`）。自更新把清单的 `sha256` 传进去（`Updater.kt:148`），缓存目录是 `cacheDir/dl-cache`（`Updater.kt:44-45`）。

## 六、超时与上限

| 环节 | 值 | 位置 |
| --- | --- | --- |
| 清单单源超时 | 8 s | `ManifestFetch.kt:51` |
| 清单总预算 | 12 s | `ManifestFetch.kt:54` |
| 清单 HTTP 连接 / 读 | 12 s / 20 s | `Updater.kt:181-182` |
| 下载连接 / 读 | 15 s / 60 s | `Downloads.kt:110-111` |
| 分段器自述缺省 | 12 s / 30 s（被 `Downloads` 覆盖） | `SegmentedDownloader.kt:43-44` |
| 镜像探测预算 | 4 s | `SegmentedDownloader.kt:46` |
| 整次下载硬超时 | 20 分钟 | `SegmentedDownloader.kt:48` |
| 分段并发 | 6 | `SegmentedDownloader.kt:374` |
| 最小分段 | 512 KB | `SegmentedDownloader.kt:378` |
| 镜像重试轮数 / 剔除阈值 | 3 轮 / 失败 1 次即剔除 | `SegmentedDownloader.kt:50-52` |
| APK 落地后最小体积 | 1 MB | `Updater.kt:152` |
| 更新说明展示上限 | 200 字 | `AutoUpdatePrompt.kt:32` |

两个可配项存在 `SharedPreferences("dsh.update")`：`manifest_timeout_ms` 钳在 1–60 s（`Updater.kt:199-201`、`:235`），`manifest_budget_ms` 钳在 1–120 s（`Updater.kt:204-206`、`:236`）。清单 TTL 的键是 `manifest_ttl_ms`（`Updater.kt:237`）。

## 七、失败文案（照着 grep）

| 触发 | 文案 | 位置 |
| --- | --- | --- |
| 全部源失败且本地无副本 | `清单拉取失败：<url> → 超时 8000ms；(总预算) → 超过 12000ms` | `Updater.kt:110` + `ManifestFetch.kt:34-39`、`:73`、`:106` |
| 候选列表为空 | `没有可用更新源` | `ManifestFetch.kt:36` |
| 下载候选为空 | `下载失败：没有可用地址` | `Updater.kt:142` |
| 所有镜像都不通 | `更新包 失败：所有地址都不通（4 个候选，例如 https://…）` | `SegmentedDownloader.kt:79-81` |
| sha 不符 | `更新包 sha256 校验失败：期望 <want>，实际 <actual>` | `SegmentedDownloader.kt:305` |
| 长度不符 | `文件不完整：期望 <n> 字节，实际 <m>` | `SegmentedDownloader.kt:310` |
| 落地体积过小 | `文件不完整（<n> 字节）` | `Updater.kt:152` |
| 超时 | `更新包 超时：超过 1200 秒没有下完` | `SegmentedDownloader.kt:101-102` |
| 超过体积上限（调用方给了才触发） | `更新包 超过上限：<n> MB，上限 <m> MB` | `SegmentedDownloader.kt:82-83`、`:95-96` |

UI 上这些字符串原样进弹窗（`AutoUpdatePrompt.kt:107-112`），所以文案里要带源地址或字节数，别只说「失败」。

## 八、发布流程

1. 改 `gradle.properties:10-11`：`dshAppVersionName`（当前 `v0.08`）、`dshAppVersionCode`（当前 `102`）。构建脚本从 Gradle 属性读（`app/build.gradle.kts:201-202`），写进 `defaultConfig`（`:220-221`）。
2. `./gradlew assembleRelease`，产物名 `HarnessForAndroid-release-<versionName 空格前第一段>.apk`（`app/build.gradle.kts:204`、`:337`）。
3. 先传 APK，再改 `update.json` 的 `versionCode` / `versionName` / `apkUrl` / `sha256` / `notes`。反了会把所有已装用户卡在 404 的弹窗上。
4. `versionCode` 只能往上加：降号会让已装的旧包盖不上新包。

构建期下载（空工程第一次构建要联网拿的 libnode / 内核归档）不是本文范围，见 README「构建」与 `DownloadVerifiedArtifact.kt`；那条链路是 sha256 内容寻址 + `--offline` 立即失败并打印预置指引（`DownloadVerifiedArtifact.kt:139`、`:249-258`）。

## 九、签名

`-PdshSigning=legacy|release`，默认 `legacy`：不带参数构建的包与已安装旧包同证书，能直接覆盖升级（`app/build.gradle.kts:174-177`）。`legacy` 用仓库内的 `app/keystore/app-release.jks`（`:181`），`release` 用仓库外的 `%USERPROFILE%\.dsh\hfa-release\app-release.jks`。debug 与 release 共用同一个选择，但 debug 带 `applicationIdSuffix = ".debug"`，是两个独立包（`app/build.gradle.kts:276`）。

完整证书清单、口令来源与迁移步骤见 [signing.md](signing.md)。
