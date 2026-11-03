# 签名与发布（T1.7）

本工程有**两张正式证书**，加一张仓库内的 debug 证书。签哪个由 `-PdshSigning` 选，
默认 `legacy`。

## 1. 两张证书分别是干什么的

| 身份 | keystore 位置 | alias | 用途 |
|---|---|---|---|
| `legacy`（旧） | `app/keystore/app-release.jks`（**入库**，4418 B） | `dsh-release` | 已发布/已安装的旧包用的证书。只要还有设备装着旧包，就必须保留：它们只认这张证书签的包。 |
| `release`（新） | `%USERPROFILE%\.dsh\hfa-release\app-release.jks`（**仓库外**） | `dsh-release` | 以后的正式证书。keystore 与口令都不在仓库里。 |
| `local`（内部） | `app/keystore/dsh-debug.jks`（**入库**） | `androiddebugkey` | 仅当两张正式证书都不可用（干净 CI、别人的机器）时兜底，保证构建不中断。 |

口令来源（`app/build.gradle.kts` 的 `signingProp`，按优先级）：

1. 签名属性文件：默认 `%USERPROFILE%\.dsh\hfa-release\signing.properties`，
   可用 `-PdshSigningProps=<file>` 换位置。键名：`legacy.storeFile` / `legacy.keyAlias` /
   `legacy.storePassword` / `legacy.keyPassword`，`release.*` 同理。
2. 本机遗留的 `app/keystore.properties`（已不入库，键名见 `app/keystore.properties.example`）。
3. 环境变量：`DSH_SIGNING_LEGACY_STOREPASS` / `_KEYPASS` / `_STOREFILE` / `_KEYALIAS`，
   `DSH_SIGNING_RELEASE_*` 同理。

任一口令缺失 → 该证书视为不可用 → 逐级降级到 debug 证书，构建照常出包（会签名，但证书不同）。
**口令不进任何被跟踪文件，也不出现在 CI 日志里。**

## 2. 生成新证书（一次性）

```powershell
$dir = "$env:USERPROFILE\.dsh\hfa-release"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -noprompt `
  -alias dsh-release -keyalg RSA -keysize 4096 -validity 10950 `
  -dname "CN=DSH Mobile Release, OU=Mobile, O=DSH, L=Shanghai, ST=Shanghai, C=CN" `
  -storetype PKCS12 -keystore "$dir\app-release.jks" -storepass <口令> -keypass <口令>
```

- `keyalg= RSA`，`keysize=4096`，`validity=10950`（30 年），`storetype=PKCS12`。
- `validity` 必须覆盖全部升级周期：证书过期后同一 alias 签出的包不能再升级旧安装。
- 生成后把口令写进 `%USERPROFILE%\.dsh\hfa-release\credentials.txt`（仓库外），并按上一节
  填好 `signing.properties`。
- **备份**：keystore 丢了就无法再发布可升级的包（Play 之外没有找回通道）。至少两处离线备份。

查指纹：

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore "$dir\app-release.jks" -alias dsh-release
apksigner verify --print-certs app\build\outputs\apk\release\*.apk
```

## 3. 升级兼容边界（唯一硬约束）

Android 只允许**同 applicationId + 同签名证书**的包覆盖安装。因此：

- `-PdshSigning=legacy` 的包只能与旧包互相覆盖；`-PdshSigning=release` 的包与旧包**互斥**：
  在装了旧包的设备上安装会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（构建期已打印该警告）。
- 换证书后想继续升级，两条路：**卸载旧包再装**（丢本地数据），或**换 applicationId**
  （如 `dev.dsh.mobile.v2`，两个包共存，但用户数据不迁移）。
- debug 与 release 共用同一个 `-PdshSigning` 选择，因为两者 applicationId 不同
  （release `dev.dsh.mobile` / debug `dev.dsh.mobile.debug`），同证书才能各自覆盖升级。
- 一旦切到 `release` 并对外发布，`legacy` 只用于给老设备出包，**不要再用它发新版**：
  两条线同时发布会让用户在两个不兼容的包之间反复卸载。

## 4. 发布纪律

- **用户拿到的 APK 必须全部来自同一张证书。** 混用证书（或换机器时降级到 debug 证书）
  产出的包，用户装不上去，且无法通过更新修复——只能卸载重装，本地数据全丢。
- 发布前必看：`apksigner verify --print-certs <apk>` 的 SHA-256 是否等于下表值。
- 官方证书 SHA-256（`keytool -list -v` / `apksigner` 输出一致）：

```
legacy  app/keystore/app-release.jks
        76:7B:C8:B4:BC:AC:50:FA:01:F6:F4:37:26:38:AA:45:DC:BD:C4:02:47:91:CF:9F:AF:92:67:1A:A5:49:C6:23
release %USERPROFILE%\.dsh\hfa-release\app-release.jks
        54:44:5F:7E:E3:CB:70:F6:6C:87:FC:C0:0B:7D:83:41:A1:A5:F2:22:E0:60:54:E9:F2:32:5F:91:76:B6:93:0D
```
