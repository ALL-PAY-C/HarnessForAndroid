package dev.dsh.mobile.app.gateway

import android.content.Context
import dev.dsh.mobile.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * 内置 CLIProxyAPI 网关。
 *
 * 为什么由 App 自己拉进程：插件跑在 Node/Rhino 里没有 fork/exec 能力，
 * 而 Android 10+ 只允许在 app 私有目录执行 ELF，所以「解压到 filesDir/gateway
 * + ProcessBuilder 起进程」这段只能在宿主侧做，插件只把 baseUrl 指向 127.0.0.1。
 *
 * 进程与 config.yaml 都落在 filesDir/gateway/：卸载即清，不需要任何外部权限。
 */
class GatewayManager(private val context: Context) {

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 8317
        /** 随 App 打包的上游版本，写进日志与 UI，方便和 PC 侧版本对齐。 */
        const val VERSION = "8.0.51"

        /**
         * 插件侧的固定 key：cpa-plugin 是数据插件，拿不到宿主生成的随机 key，
         * 所以 config.yaml 同时接受这一把 —— 网关只监听 127.0.0.1，它不承担鉴权职责，
         * 作用只是让「插件装完即可用」不必让用户复制粘贴。
         */
        const val PLUGIN_KEY = "sk-cpa-local-dsh-android"

        /** Starting 状态的最长容忍时间：超了就判 Failed 并把日志尾当原因。 */
        const val START_TIMEOUT_MS = 20_000L

        private val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1")
        private const val PREFS = "dsh_gateway"
        private const val KEY_API = "api_key"
        private const val KEY_ENABLED = "autostart"
    }

    /** 二进制不在包内（当前 ABI 没有对应 native）：不可重试，见 [binaryUnsupported]。 */
    private class MissingBinaryException(message: String) : IOException(message)

    /** 网关状态：UI 与插件只读这个。 */
    sealed class State {
        object Stopped : State()
        object Starting : State()
        class Running(val models: Int) : State()
        class Failed(val message: String) : State()
    }

    private val root = File(context.filesDir, "gateway")

    // 二进制以 lib*.so 形式随 jniLibs 落进 nativeLibraryDir：targetSdk 29+ 禁止 exec filesDir
    // 里的文件（会报 error=13 Permission denied），只有 nativeLibraryDir 可执行。
    private val binary = File(context.applicationInfo.nativeLibraryDir, "libgateway.so")
    private val prootBinary = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    private val prootLoader = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    private val resolvFile = File(root, "resolv.conf")
    private val caFile = File(root, "ca.pem")
    private val proxyFile = File(root, "proxy.txt")
    private val configFile = File(root, "config.yaml")
    private val logFile = File(root, "gateway.log")
    private val authDir = File(root, "auth")
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var process: Process? = null

    /** 进入 Starting 的时刻，给 [selfHeal] 判超时用。 */
    @Volatile
    private var startingAt = 0L

    /**
     * ABI 不支持/二进制不在包内：这是一次性事实，不是可重试故障。
     * 以前每次启动都重试并打一整条 E 级堆栈，debug 包在 x86_64 上刷屏十余条；
     * 现在只告警一次，之后 start() 直接返回 false。
     */
    @Volatile
    private var binaryUnsupported = false

    /** 客户端 key：首次随机生成后固定，config.yaml 与模型配置都用它。 */
    val apiKey: String
        get() = prefs.getString(KEY_API, null) ?: randomKey().also { prefs.edit().putString(KEY_API, it).apply() }

    val baseUrl: String get() = "http://${HOST}:${PORT}/v1"

    /** 下次 App 启动是否自动拉起网关（默认开：手机不连电脑就能直接用）。 */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    fun dir(): String = root.absolutePath
    fun authPath(): String = authDir.absolutePath
    fun logPath(): String = logFile.absolutePath
    fun configPath(): String = configFile.absolutePath

    fun statusLine(): String = when (val s = _state.value) {
        is State.Running -> "运行中 · ${HOST}:${PORT} · 模型 ${s.models}"
        is State.Failed -> "启动失败：${s.message}"
        State.Starting -> "启动中…"
        State.Stopped -> "未运行"
    }

    /** [dev.dsh.mobile.core.common.LoopbackGateway] 的启动器签名是 suspend () -> Unit。 */
    suspend fun ensureRunning() {
        if (start()) return
        // 服务的作用是 App 退到后台时进程还活；后台起前台服务在 Android 12+ 会被系统拒，
        // 那条路径失败不影响网关本体（进程已经在当前进程里起来了）。
        if (autoStart) runCatching { GatewayService.startIfAllowed(context) }
    }

    /** 幂等启动。已经有存活进程就直接探活返回。 */
    suspend fun start(): Boolean = lock.withLock {
        if (binaryUnsupported) return@withLock false
        val alive = process?.takeIf { it.isAlive }
        if (alive != null) {
            _state.value = State.Running(probeModels())
            return@withLock true
        }
        _state.value = State.Starting
        startingAt = System.currentTimeMillis()
        try {
            withContext(Dispatchers.IO) {
                prepare()
                spawn()
            }
            val p = process
            // 判活只看端口：/v1/models 需要凭据，凭据没导入时它返回 0，不能据此说网关没起来。
            if (!waitForReady()) {
                val tail = logTail()
                runCatching { p?.destroy() }
                process = null
                _state.value = State.Failed(
                    "端口 ${PORT} ${START_TIMEOUT_MS / 1000}s 内未监听" + if (tail.isEmpty()) "" else "：${tail}",
                )
                return@withLock false
            }
            // 模型数是锦上添花：拿不到（凭据未导入）也照样是 Running。
            _state.value = State.Running(probeModels())
            watch(p)
            AppLog.i("gateway up, port=${PORT} models=${(state.value as? State.Running)?.models ?: -1} log=${logFile.absolutePath}")
            true
        } catch (e: MissingBinaryException) {
            binaryUnsupported = true
            AppLog.w("网关已停用：${e.message}")
            _state.value = State.Failed(e.message ?: "当前 ABI 不在包内")
            false
        } catch (e: Exception) {
            AppLog.e("gateway start failed: ${e.message}", e)
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** 手动/系统停止：杀进程，状态回「未运行」。 */
    fun stop() {
        val p = process
        process = null
        _state.value = State.Stopped
        if (p == null) return
        runCatching { p.destroy() }
        Thread {
            runCatching { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
            killOrphans()
            AppLog.i("gateway stopped")
        }.apply { isDaemon = true }.start()
    }

    /** 升级/强杀留下的垃圾：老版本从 assets 解压出来的二进制（几十 MB）与 proot 的临时目录。 */
    private fun cleanup() {
        File(root, "cli-proxy-api").takeIf { it.exists() }?.let {
            if (it.delete()) AppLog.i("gateway removed legacy assets binary")
        }
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("proot-") }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    /**
     * proot 只是 tracer：只杀它，真正的网关子进程会留成孤儿继续占着 8317。
     * 按 /proc/<pid>/cmdline 找同名进程补刀（同 uid 才有权限，正好都是本 App 的）。
     */
    private fun killOrphans() {
        val self = android.os.Process.myPid()
        val children = File("/proc").listFiles() ?: return
        for (d in children) {
            val pid = d.name.toIntOrNull() ?: continue
            if (pid == self) continue
            val cmd = runCatching {
                File(d, "cmdline").readText().replace('\u0000', ' ').trim()
            }.getOrNull() ?: continue
            if (!cmd.contains("libgateway.so") && !cmd.contains("libproot.so")) continue
            runCatching { ProcessBuilder("kill", "-9", pid.toString()).start().waitFor() }
            AppLog.w("gateway killed orphan pid=$pid")
        }
    }

    /** 探 /v1/models，返回模型个数；连不上返回 0。 */
    suspend fun probeModels(): Int = withContext(Dispatchers.IO) {
        var c: HttpURLConnection? = null
        try {
            c = (URL("http://${HOST}:${PORT}/v1/models").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 3000
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            if (c.responseCode !in 200..299) return@withContext 0
            val body = c.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optJSONArray("data")?.length() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    /** 日志末尾若干行，给设置页做「为什么没起来」的证据。 */
    fun logTail(lines: Int = 3): String = runCatching {
        if (!logFile.exists()) return ""
        logFile.readLines().takeLast(lines).joinToString(" / ").take(300)
    }.getOrDefault("")

    // ---------- 首次启动落地 ----------

    private fun prepare() {
        if (!root.exists() && !root.mkdirs()) throw IOException("无法创建 ${root.absolutePath}")
        if (!authDir.exists()) authDir.mkdirs()
        // 上次崩溃/被系统杀留下的监听者会让 bind 直接失败，先清干净再起。
        killOrphans()
        cleanup()
        // 装包时由 PackageManager 解压到 nativeLibraryDir，这里只校验在位可执行（缺 ABI 时也能说清原因）。
        if (!binary.isFile || !binary.canExecute()) {
            throw MissingBinaryException("网关二进制缺失：${binary.absolutePath}（当前 ABI 不在包内，请换机或重装）")
        }
        seedAuth()
        writeConfig()
        writeResolv()
        writeCaBundle()
    }

    /**
     * 自带 resolv.conf。Android 没有 /etc/resolv.conf，纯静态 Go 二进制会退回 127.0.0.1:53，
     * 结果是所有外网请求 DNS 全挂（日志里是 dial udp [::1]:53: connection refused）。
     * DNS 取自当前网络（ConnectivityManager），拿不到才退回公共 DNS。
     */
    private fun writeResolv() {
        val dns = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val net = cm?.activeNetwork
            cm?.getLinkProperties(net)?.dnsServers?.mapNotNull { it?.hostAddress }
        }.getOrNull().orEmpty().filter { it.isNotBlank() }.ifEmpty { FALLBACK_DNS }
        val text = dns.joinToString("") { "nameserver $it\n" }
        if (!resolvFile.exists() || resolvFile.readText() != text) resolvFile.writeText(text)
    }

    /**
     * 单一 PEM 证书包。Android 的系统 CA 在 /system/etc/security/cacerts/<hash>.0，
     * Go 找的是 /etc/ssl/certs，找不到就报 x509: certificate signed by unknown authority。
     * 系统 CA 有更新时重拼。
     */
    private fun writeCaBundle() {
        val files = File("/system/etc/security/cacerts").listFiles() ?: return
        val newest = files.maxOfOrNull { it.lastModified() } ?: return
        if (caFile.isFile && caFile.length() > 0L && caFile.lastModified() >= newest) return
        val tmp = File(root, "ca.pem.tmp")
        runCatching {
            tmp.outputStream().use { out -> files.sortedBy { it.name }.forEach { out.write(it.readBytes()) } }
            caFile.delete()
            if (!tmp.renameTo(caFile)) throw IOException("ca.pem 落盘失败")
            AppLog.i("gateway ca bundle ${caFile.length()}B / ${files.size} certs")
        }.onFailure { AppLog.w("gateway ca bundle failed: ${it.message}") }
    }

    /** 内置凭据只在 auth/ 里缺文件时写入，用户换账号（覆盖同名 json）不会被冲掉。 */
    private fun seedAuth() {
        val names = runCatching { context.assets.list("gateway/auth") }.getOrNull() ?: return
        for (n in names) {
            val dst = File(authDir, n)
            if (dst.exists() && dst.length() > 0L) continue
            runCatching {
                context.assets.open("gateway/auth/$n").use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                AppLog.i("gateway seeded auth $n")
            }.onFailure { AppLog.w("gateway seed auth $n failed: ${it.message}") }
        }
    }

    /** auth/ 里已有的凭据点（*.json）：设置页显示「已导入 N 份」。 */
    fun authNames(): List<String> =
        authDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.map { it.name }.orEmpty()

    /**
     * 导入凭据。发布包不再内置 OAuth json（内置等于把 refresh token 打进公开 APK），
     * 由用户在设置页粘贴，落进 files/gateway/auth/ 后重启网关生效。
     */
    fun importAuth(json: String, name: String = "auth.json"): String {
        val obj = runCatching { JSONObject(json) }.getOrElse { throw IOException("不是合法的 JSON：${it.message}") }
        if (!obj.has("refresh_token") && !obj.has("access_token")) throw IOException("缺 refresh_token / access_token 字段")
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "auth.json" }
            .let { if (it.endsWith(".json")) it else "$it.json" }
        if (!authDir.exists()) authDir.mkdirs()
        val dst = File(authDir, safe)
        dst.writeText(obj.toString(2))
        AppLog.i("gateway auth imported $safe ${dst.length()}B")
        return safe
    }

    private fun writeConfig() {
        val authPath = authDir.absolutePath
        val yaml = """
            # 由 DSH For Android 生成；每次启动重写，改这里会在下次启动被覆盖。
            config-version: 8
            server:
              host: "${HOST}"
              port: ${PORT}
            management:
              allow-remote: false
            access:
              api-keys:
                - "$apiKey"
                - "${PLUGIN_KEY}"
            routing:
              strategy: "round-robin"
            oauth:
              auth-dir: "$authPath"
        """.trimIndent() + "\n"
        if (!configFile.exists() || configFile.readText() != yaml) configFile.writeText(yaml)
    }

    // ---------- 进程 ----------

    private fun spawn() {
        // Go 只读 /etc/resolv.conf：用随包的 proot 把自带的那份绑进去（proot 缺失时直跑）。
        val useProot = prootBinary.isFile && prootLoader.isFile && resolvFile.isFile
        val argv = buildList {
            if (useProot) {
                add(prootBinary.absolutePath)
                add("-b")
                add("${resolvFile.absolutePath}:/etc/resolv.conf")
            }
            add(binary.absolutePath)
            add("-config")
            add(configFile.absolutePath)
        }
        val pb = ProcessBuilder(argv)
        pb.directory(root)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        pb.environment().apply {
            put("HOME", root.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            if (useProot) {
                put("PROOT_LOADER", prootLoader.absolutePath)
                put("PROOT_TMP_DIR", root.absolutePath)
            }
            if (caFile.isFile) put("SSL_CERT_FILE", caFile.absolutePath)
            // 可选上游代理：files/gateway/proxy.txt 写一行 http://host:port 就生效（受限网络用）。
            runCatching { proxyFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
                ?.takeIf { it.startsWith("http") }?.let { url ->
                    put("HTTP_PROXY", url)
                    put("HTTPS_PROXY", url)
                }
        }
        val p = pb.start()
        process = p
        AppLog.i("gateway spawn(proot=$useProot): ${binary.absolutePath} -config ${configFile.absolutePath}")
    }

    /**
     * 端口探活：TCP 连上 8317 就算网关活着。
     * 刻意不探 /v1/models —— 那个要 Authorization 和已导入的凭据，凭据空时永远返回 0。
     */
    suspend fun probePort(timeoutMs: Int = 600): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(HOST, PORT), timeoutMs) }
            true
        }.getOrDefault(false)
    }

    /** 等端口进入监听；子进程提前退出就直接判失败，不空等满 ${START_TIMEOUT_MS}。 */
    private suspend fun waitForReady(timeoutMs: Long = START_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = process ?: return false
            if (!p.isAlive) return false
            if (probePort()) return true
            delay(300)
        }
        return false
    }

    /**
     * 状态自愈，UI 每秒调一次：
     * - 端口已经在监听，但状态还停在 Starting（进程是上一次拉起来的/启动协程丢了）→ Running；
     * - Starting 超过 ${START_TIMEOUT_MS} 仍未监听 → Failed，并带上日志尾当原因。
     * 只处理 Starting，Running/Stopped 不被覆盖。
     */
    suspend fun selfHeal(): State {
        val cur = _state.value
        if (cur !is State.Starting) return cur
        if (probePort()) {
            val healed = State.Running(probeModels())
            _state.value = healed
            AppLog.i("gateway self-healed to running (port ${PORT} listening, models=${healed.models})")
            return healed
        }
        val since = startingAt
        if (since > 0 && System.currentTimeMillis() - since > START_TIMEOUT_MS) {
            val tail = logTail()
            val failed = State.Failed(
                "启动超时 ${START_TIMEOUT_MS / 1000}s 未监听 ${PORT}" + if (tail.isEmpty()) "" else "：${tail}",
            )
            _state.value = failed
            AppLog.w("gateway starting timeout -> failed")
            return failed
        }
        return cur
    }

    /** 子进程一退出就把「运行中」改掉，免得 UI 一直显示假的绿灯。 */
    private fun watch(p: Process?) {
        if (p == null) return
        Thread {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (process === p) {
                process = null
                val tail = logTail()
                _state.value = if (code == 0) State.Stopped else State.Failed("进程退出 code=$code" + if (tail.isEmpty()) "" else "：$tail")
                AppLog.w("gateway exited code=$code")
            }
        }.apply { isDaemon = true; name = "gateway-watch" }.start()
    }

    private fun randomKey(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return "sk-cpa-local-" + (1..24).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }
}
