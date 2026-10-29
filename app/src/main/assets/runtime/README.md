# 内置运行时投放说明（Node 引擎）

Node 引擎（`NodeEngineHost`）用 nodejs-mobile 的 `libnode.so` 在**进程内**跑 `bin.js`，不落可执行文件再 fork。

## 投放布局（assets → files）

| 源（assets） | 解包目标（files） | 说明 |
| --- | --- | --- |
| `runtime/engine.zip` | `runtime/engine-v1/` | 引擎整体（`bin.js` + `host/cordis-lite.js` + `host/loader.js` + `node_modules`）。版本进目录名（engine-v1），换包换 v2 |
| （`:node-runtime` jniLibs） | `nativeLibraryDir/libnode.so` | nodejs-mobile **v22.23.2**（第三方 fork `OpenFlun/nodejs-mobile`）+ `libc++_shared.so`，JNI `node::Start` |

解包判据 = `engine-v1/.extracted` 标记（文件数 + 总字节），命中即复用；不符（解包中断 / 换包）则整目录重来。

## 启动参数

```
node <engine-v1>/bin.js --port <port> --port-file <engine-v1>/.port --home <home> [--plugin <dir>...]
```

- `<port>` 由宿主先 `ServerSocket(0)` 选好（127.0.0.1 空闲端口），不是引擎自选。
- `<home>` = `files/runtime/home`，引擎的 HOME/DSH_HOME（`skill-manager/.agents/skills/` 之类落这里）。
- `--plugin` 每装一个 Node 宿主插件（如 `dsh-skills-manager`）就多一个。
- 引擎起好后把实际端口原子写进 `.port`（失败写 `ERR <msg>`），宿主轮询它（25s / 50ms）再连 TCP 走 JSON 行协议。

## 为什么压成 engine.zip

aapt2 打包 assets 会跳过以 `.` / `_` 开头的条目（`node_modules/.bin`、`skills/dist/_chunks` 全被吃），而 `cli.mjs` 真 import 那些 `_chunks` → 必须整包压成一个 zip（实测 851/851 全在）。`tools/pack-engine.ps1` 已实现，并把 .NET `ZipFile` 的反斜杠 entry 名换成 `/`。
