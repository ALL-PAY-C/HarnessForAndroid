package dev.dsh.mobile.pluginhost

import android.content.Context
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.pluginapi.DshPlugin
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * npm 安装的结果：清单 + 运行期依赖摘要。
 *
 * 单独一个类型（而不是继续返回 [PluginManifestFile]）是因为「装上了」不等于「跑得起来」：
 * 依赖装失败时调用方要能把摘要带出去（UI 提示 / 日志），而不是去翻 logcat。
 */
data class NpmInstallResult(
    val manifest: PluginManifestFile,
    val deps: DependencySummary,
)

/** 数据包插件的执行形态。 */
internal enum class PluginKind { Declarative, Rhino, Node }

/**
 * 看包目录决定走哪条宿主：无 JS 入口 → 声明式；引用了任何本端没有的外部模块 → Node；否则 Rhino。
 *
 * 「任何」包括裸 npm 包（schemastery / semver / zod），不只是 node: 与 @deepseek-ai/。
 * Rhino 侧根本没有 npm 解析器，能满足的外部模块数是零，所以只要 [ExternalModules.scan]
 * 报出来一个模块，这条路就只能交给 NodeEngineHost —— 否则插件会在 Rhino 的模块加载阶段
 * 直接失败，用户看到的是「该插件依赖外部模块」，而不是它能跑起来。
 */
internal fun classifyPlugin(dir: File): PluginKind {
    val entry = JsPluginRuntime.resolveEntry(dir) ?: return PluginKind.Declarative
    val entryFile = File(dir, entry.path)
    val missing = ExternalModules.scan(dir, entryFile)
    return if (missing.isNotEmpty()) PluginKind.Node else PluginKind.Rhino
}

/**
 * 卸载核心：先删目录，删失败即抛、不触碰 [remove]（配置保留）；成功才 [remove]。
 * 顶层 internal seam（与 [classifyPlugin] 同风格）：便于无设备窄测用真实文件删除失败覆盖 S9b。
 */
internal fun uninstallDir(dir: File, id: String, remove: () -> Unit): Result<Unit> = runCatching {
    check(!dir.exists() || dir.deleteRecursively()) { "插件文件删除失败：$id" }
    remove()
}

/**
 * Installs and removes plugin packages.
 *
 * 一个包可以是三种形态，安装器全都吃：
 *  - **zip**：根目录（或任意单一顶层目录）放 plugin.json；
 *  - **npm tarball（.tgz）**：包内是 package/package.json，没有 plugin.json 时
 *    用 package.json 的 name/version/description/dsh 字段**合成**一份清单；
 *  - **裸 plugin.json**：单文件数据插件。
 *
 * 解包、校验、事务提交、版本化、回滚全部在 [PluginPackageService] 里；这里只负责
 * 网络抓取（下载 / npm registry）与宿主装配（Rhino / Node / 声明式）。
 *
 * 每次安装都会先走 [onBeforeInstall]，让调用方先做快照。这个钩子就是
 * 「回档到装这批插件之前」能成立的原因；批量由调用方负责（见 BackupManager.beginBatch）。
 */
class PluginInstaller(
    private val context: Context,
    private val installed: InstalledPlugins,
    private val onBeforeInstall: suspend (pluginId: String) -> Unit = {},
) {

    /**
     * 事务化包管理。激活钩子留空：真正的「执行一次」由 [loadableJsPlugins] /
     * [loadableNodePlugins] 在装配阶段做，失败会降级成 JsBrokenPlugin 而不是回滚安装 ——
     * 装上一个暂时跑不起来的包，比装到一半失败好。需要严格激活语义的调用方自己传 onActivate。
     */
    val packages: PluginPackageService = PluginPackageService(
        root = File(context.filesDir, "plugins"),
        registry = installed.registry(),
        onVerify = { id, _ -> onBeforeInstall(id) },
    )

    /** 当前活动目录：版本化布局下是 versions/<current>，老扁平安装直接是 plugins/<id>。 */
    fun dirOf(id: String): File = packages.activeDir(id) ?: packages.idDir(id)

    /**
     * 首启灌预置离线插件：assets/plugins 下的 zip，全程不联网。
     *
     * 幂等（见 [OfflineBundled]）：装过的包名记在 plugins/.offline.json，下次调用只返回 skipped。
     */
    suspend fun installBundled(): OfflineReport = OfflineBundled.install(
        service = packages,
        source = AssetBundledSource(context),
        marker = OfflineBundled.markerOf(File(context.filesDir, "plugins")),
    )

    /** 单包下载上限。插件包正常几 MB，超过 200 MB 基本可以判定是装错东西了。 */
    private val maxBytes = 200L * 1024 * 1024

    // ------------------------------------------------------------- entry points

    /** Install from a local zip. */
    suspend fun installZip(zip: File): Result<PluginManifestFile> = installArchive(zip)

    /**
     * 本地文件安装：按文件头自动区分 zip / gzip(tar) / 裸 JSON。
     *
     * 这是「从文件安装」按钮唯一该调的入口 —— 以前只认 zip，用户选了个 .tgz 就报
     * 「包里没有 plugin.json」，其实包是对的、只是读法不对。
     */
    suspend fun installArchive(file: File): Result<PluginManifestFile> = withContext(Dispatchers.IO) {
        packages.installArchive(file).toManifestResult()
    }

    /** Install by downloading a zip/tgz from [url]. */
    suspend fun installUrl(url: String): Result<PluginManifestFile> = withContext(Dispatchers.IO) {
        runCatching {
            val file = downloadToCache(url)
            installArchive(file).getOrElse { file.delete(); throw it }
        }
    }

    /**
     * 从 npm 装一个包。
     *
     * 默认走 registry 的 /<name>/latest（小、快、无需遍历全部版本），拿 dist.tarball
     * 直接下 tgz；传 [version] 可钉死某个版本（/<name>/<version>），修复与回归要靠它。
     * 这条路对社区插件覆盖率最高：market-v2 索引里 322/521 个可安装条目是 npm 形态。
     */
    suspend fun installNpm(name: String, version: String = "latest"): Result<NpmInstallResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = name.trim()
                require(trimmed.isNotBlank()) { "npm 包名不能为空" }
                require(!trimmed.contains("..")) { "npm 包名非法：" + trimmed }
                val spec = version.trim().ifBlank { "latest" }
                require(!spec.contains("..") && !spec.contains('/')) { "npm 版本号非法：" + spec }
                val metaUrl = npmMetaUrl(trimmed, spec)
                val meta = fetchText(metaUrl)
                val json = JSONObject(meta)
                val tarball = json.optJSONObject("dist")?.optString("tarball").orEmpty()
                if (tarball.isBlank()) error("npm 包 $trimmed 没有 tarball（可能是个空壳包）")
                val file = downloadToCache(tarball)
                val result = packages.installArchive(file, source = "npm/" + trimmed + "@" + spec)
                if (!result.ok) {
                    file.delete()
                    error(result.error ?: "安装失败")
                }
                val manifest = result.manifest ?: error(result.error ?: "安装失败：没有清单")
                // 依赖装在活动版本目录里：npm 包从不带 node_modules，缺依赖时 Node 引擎在 import
                // 阶段直接抛 Cannot find package（真机实测 @lihuu/dsh-ollama-cloud@0.2.1）。
                // 装依赖失败只降级成 warning —— 包已经落地且声明式插件照跑，不能因依赖回滚安装。
                val warnings = mutableListOf<String>()
                val deps = installDependencies(File(result.path), warnings)
                warnings.forEach { AppLog.w("npm 插件 " + trimmed + " " + it) }
                AppLog.d("npm 插件 " + trimmed + "@" + spec + " " + deps.text())
                NpmInstallResult(manifest, deps)
            }
        }

    /**
     * 装一个 npm 插件的运行期依赖。
     *
     * registry 的 /<name>/<spec> 同时吃版本号、tag 与 semver 范围，所以 package.json 里写
     * "^0.2.1" 也能取到对应 tarball。规则与边界全在 [NpmDependencyResolver]：这里只负责
     * 把安装器已有的 fetchText / downloadToCache 接到那条窄缝上，不另写 HTTP 客户端。
     */
    private suspend fun installDependencies(dir: File, warnings: MutableList<String>): DependencySummary {
        val packageJson = runCatching { JSONObject(File(dir, "package.json").readText()) }.getOrNull()
        val resolver = NpmDependencyResolver(
            source = object : NpmRegistrySource {
                override fun fetchText(url: String): String = this@PluginInstaller.fetchText(url)
                override fun download(url: String, timeoutMs: Int): File =
                    this@PluginInstaller.downloadToCache(url, timeoutMs)
            },
        )
        return resolver.install(dir, packageJson, warnings)
    }

    fun uninstall(id: String): Result<Unit> {
        val result = packages.remove(id)
        return if (result.ok) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(result.error ?: ("插件文件删除失败：" + id)))
        }
    }

    fun installedIds(): List<String> = installed.all().keys.toList()

    /** 安装/修复结果 → 老 API 的 Result<PluginManifestFile>。失败保留原始错误文本。 */
    private fun PackageResult.toManifestResult(): Result<PluginManifestFile> {
        val manifest = manifest
        return if (ok && manifest != null) {
            Result.success(manifest)
        } else {
            Result.failure(IllegalStateException(error ?: "安装失败"))
        }
    }

    /**
     * 宿主装配入口：包里有 JS 入口就真执行，没有就退回纯声明式。
     *
     * 实现就是 [loadableJsPlugins]（它对每个包都给出 DshPlugin，无入口时是
     * [DeclarativePlugin]）。保留这个名字是因为 AppContainer 装配与热加载都按它取列表。
     */
    fun loadablePlugins(): List<DshPlugin> = loadableJsPlugins()

    /**
     * 与 [loadablePlugins] 并行的第二入口：包里有 JS 入口就真执行它。
     *
     * 磁盘目录才是真相 —— installed 里只有清单 JSON，入口路径一律在
     * [dirOf] 下重新解析（[JsPluginRuntime.resolveEntry]）。三种结局：
     *  - 有入口且执行成功 → [JsBackedPlugin]；
     *  - 有入口但执行失败 → [JsBrokenPlugin]（占位卡，错误可见，宿主不受影响）；
     *  - 没有入口 → 回退 [DeclarativePlugin]，安装方式与以前完全一致；
     *  - 目录已被删 → 跳过并记日志（清单还在但没有 payload，执行只会报假错）。
     *
     * 执行在调用线程同步跑：AppContainer 在 IO 线程装配插件，单包执行是毫秒级
     * （死循环由 [JsPluginRuntime.MAX_STEPS] 兜底）。
     */
    fun loadableJsPlugins(): List<DshPlugin> = installed.all().mapNotNull { (id, spec) ->
        val dir = dirOf(id)
        if (!dir.isDirectory) {
            AppLog.w("插件 " + id + " 的目录已不存在，跳过 JS 执行：" + dir.absolutePath)
            return@mapNotNull null
        }
        // Node 插件（node:/@deepseek-ai/ 依赖）改走 loadableNodePlugins → NodeEngineHost，不在 Rhino 里跑。
        if (classifyPlugin(dir) == PluginKind.Node) return@mapNotNull null
        val config = installed.config(id)
        val runtime = JsPluginRuntime(
            config = config,
            onSetConfig = { key, value -> installed.setConfig(id, key, value) },
            log = { AppLog.d(it) },
        )
        val entry = JsPluginRuntime.resolveEntry(dir)
        if (entry == null) {
            DeclarativePlugin(
                spec = spec,
                configValues = config,
                onConfigChange = { key, value -> installed.setConfig(id, key, value) },
                // 包目录交给 UI 适配器：package.json 的 dsh.ui 与 HTML 面板都在这下面。
                packageDir = dir,
            )
        } else {
            runtime.execute(dir, id).fold(
                onSuccess = { run ->
                    AppLog.i("插件 " + id + " 已执行 " + run.entryPath + "：" + run.summary())
                    JsBackedPlugin(
                        spec = spec,
                        run = run,
                        configValues = config,
                        onConfigChange = { key, value -> installed.setConfig(id, key, value) },
                        packageDir = dir,
                    )
                },
                onFailure = { t ->
                    val err = t as? JsPluginError
                        ?: JsPluginError(JsPluginRuntime.STAGE_EXEC, t.message ?: t.toString(), t)
                    AppLog.w("插件 " + id + " JS 执行失败：[" + err.stage + "] " + err.message)
                    JsBrokenPlugin(spec, err)
                },
            )
        }
    }

    /**
     * Node 插件（node:/@deepseek-ai/ 依赖）：本端不执行 JS，只把包目录交给 NodeEngineHost
     * 的 plugins.load，状态由 [NodeBackedPlugin.status] 观察。加载失败带 stage/reason。
     */
    fun loadableNodePlugins(): List<NodeBackedPlugin> = installed.all().mapNotNull { (id, spec) ->
        val dir = dirOf(id)
        if (!dir.isDirectory) {
            AppLog.w("插件 " + id + " 的目录已不存在，跳过 Node 加载：" + dir.absolutePath)
            return@mapNotNull null
        }
        if (classifyPlugin(dir) != PluginKind.Node) return@mapNotNull null
        NodeBackedPlugin(spec = spec, dir = dir)
    }

    // ---------------------------------------------------------------- 网络

    private fun downloadToCache(url: String, timeoutMs: Int = 60_000): File {
        val ext = url.substringBefore('?').let {
            when {
                it.endsWith(".tgz", true) || it.endsWith(".tar.gz", true) -> "tgz"
                it.endsWith(".json", true) -> "json"
                else -> "zip"
            }
        }
        val file = File(context.cacheDir, "plugin-dl-" + url.hashCode() + "." + ext)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            // 依赖安装传 45s（单包上限），插件包本体仍用 60s：超时是墙钟上限，不是越快越好。
            connectTimeout = minOf(15_000, timeoutMs)
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/zip,application/gzip,application/octet-stream,*/*")
            setRequestProperty("User-Agent", "dsh-mobile")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("下载失败 HTTP " + code)
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) error("包太大（超过 200 MB）")
                        output.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "dsh-mobile")
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP " + code + " · " + url.substringAfterLast('/'))
            return text
        } finally {
            conn.disconnect()
        }
    }
}
