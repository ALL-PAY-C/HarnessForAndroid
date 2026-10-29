# 离线预置插件（assets/plugins）

首启由 `PluginInstaller.installBundled()` 装进 `filesDir/plugins`：不联网，幂等
（装过的包名记在 `filesDir/plugins/.offline.json`，第二次只跳过不重装；用户手动卸载过的不会被复活）。

| 包 | 体积 | 内容 |
| --- | --- | --- |
| dsh-offline-hello.zip | 1819 B | plugin.json + package.json + index.js；Node 宿主插件，rpc `/hello` 的 `whoami` |
| dsh-offline-whoami.zip | 1246 B | plugin.json + index.js；Node 宿主插件，rpc `/whoami` 的 `whoami` |

判型靠内容：两个包都在入口 `import os from 'node:os'`，plugin-host 的 `classifyPlugin`
因此把它们判成 Node 插件，走 NodeBackedPlugin 的 `plugins.load`（不是 Rhino）。

改动预置包内容后，把 `OfflineBundled.REV` 加 1，已装过老批次的设备会重灌一遍。
zip 用 store 方式、UTF-8 entry 名（zip 规范要求正斜杠，别用 Windows 的 Compress-Archive）。
