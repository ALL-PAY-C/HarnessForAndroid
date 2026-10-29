package dev.dsh.mobile.runtimehost

import android.content.Context
import android.os.SystemClock
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.noderuntime.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * 进程内 Node 引擎的宿主实现。
 *
 * 链路：assets/runtime/engine → filesDir/runtime/engine-v1（首次解包）→ NodeRuntime.start
 * （node::Start 在 node-main 线程上跑 bin.js）→ 引擎把监听端口原子写进 .port → 这里轮询
 * 拿到端口 → 127.0.0.1 TCP 上跑 JSON 行协议。
 *
 * 为什么不自己起进程：Android 上没有 node 可执行文件，nodejs-mobile 只给 libnode.so，
 * 只能 JNI 调 node::Start 在进程内跑，stdout 也进不了管道（native 侧已重定向到 logcat）。
 */
class NodeEngineHost(
    context: Context,
    private val scope: CoroutineScope,
) : NodeEngine {

    private val appContext = context.applicationContext

    /** 解包目标；版本号进目录名，引擎包换了直接换 v2，不用做增量校验。 */
    private val engineDir = File(appContext.filesDir, "runtime/engine-v1")
    private val homeDir = File(appContext.filesDir, "runtime/home")
    private val portFile = File(engineDir, ".port")
    private val markerFile = File(engineDir, ".extracted")

    private val _state = MutableStateFlow<NodeEngineState>(NodeEngineState.Stopped)
    override val state: StateFlow<NodeEngineState> = _state.asStateFlow()

    private val startLock = Mutex()
    private val callLock = Mutex()
    private val ids = AtomicLong(1)

    @Volatile private var socket: Socket? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var writer: BufferedWriter? = null

    /** libnode 不支持二次启动：调用过一次 NodeRuntime.start 之后就不再尝试。 */
    @Volatile private var launched = false

    @Volatile private var stopping = false

    @Volatile private var port = 0

    init {
        // NodeRuntime 靠 install() 记下 applicationContext 才能定位 nativeLibraryDir。
        NodeRuntime.install(appContext)
    }

    override fun setHostCallHandler(handler: HostCallHandler?) {
        hostHandler = handler
    }

    override fun isRunning(): Boolean {
        val s = socket ?: return false
        return _state.value is NodeEngineState.Ready && s.isConnected && !s.isClosed
    }

    override suspend fun ensureStarted(pluginDirs: List<File>): Result<Int> = startLock.withLock {
        if (isRunning()) return@withLock Result.success(port)
        if (launched) {
            val msg = "进程内 Node 已启动过（libnode 不支持二次启动），重启应用后再试"
            _state.value = NodeEngineState.Failed(msg, retryable = false)
            return@withLock Result.failure(NodeEngineException("start", msg))
        }
        if (!NodeRuntime.isAvailable()) {
            val msg = "libnode.so 不在 nativeLibraryDir"
            AppLog.e("ENGINE_START $msg")
            _state.value = NodeEngineState.Failed(msg, retryable = false)
            return@withLock Result.failure(NodeEngineException("start", msg))
        }

        _state.value = NodeEngineState.Starting("解包引擎")
        withContext(Dispatchers.IO) { runCatching { ensureExtracted() } }
            .onFailure {
                val msg = "引擎解包失败: ${it.message}"
                AppLog.e("ENGINE_START $msg", it)
                _state.value = NodeEngineState.Failed(msg, retryable = false)
                return@withLock Result.failure(NodeEngineException("start", msg, it))
            }

        val listenPort = withContext(Dispatchers.IO) { pickPort() }
        // 上一次运行残留的端口文件必须删掉，否则会连到旧端口上。
        withContext(Dispatchers.IO) { portFile.delete() }

        val binJs = File(engineDir, "bin.js")
        val args = buildList {
            add("node")
            add(binJs.absolutePath)
            add("--port"); add(listenPort.toString())
            add("--port-file"); add(portFile.absolutePath)
            add("--home"); add(homeDir.absolutePath)
            pluginDirs.forEach { dir ->
                add("--plugin"); add(dir.absolutePath)
            }
        }

        _state.value = NodeEngineState.Starting("等待引擎监听")
        launched = true
        val argv = args.toList()
        // NodeRuntime.start 阻塞到 node 退出，必须在独立协程里跑。
        scope.launch(Dispatchers.IO) {
            val rc = NodeRuntime.start(argv)
            onNodeExit(rc)
        }

        val actual = pollPortFile(PORT_WAIT_MS)
        if (actual <= 0) {
            val msg = if (actual == ERR_CODE) "引擎启动失败: ${lastPortFileError}" else "等待端口文件超时（${PORT_WAIT_MS}ms）"
            AppLog.e("ENGINE_START $msg")
            _state.value = NodeEngineState.Failed(msg, retryable = false)
            return@withLock Result.failure(NodeEngineException("start", msg))
        }

        withContext(Dispatchers.IO) { runCatching { openSocket(actual) } }
            .onFailure {
                val msg = "连接引擎失败 127.0.0.1:$actual: ${it.message}"
                AppLog.e("ENGINE_START $msg", it)
                _state.value = NodeEngineState.Failed(msg, retryable = false)
                return@withLock Result.failure(NodeEngineException("transport", msg, it))
            }

        port = actual
        _state.value = NodeEngineState.Ready(actual)
        AppLog.i("ENGINE_READY port=$actual")

        // 握手确认协议真的通了；失败也不致命，下面 plugins.load 会暴露真实原因。
        runCatching { call("ping") }
            .onSuccess { AppLog.i("ENGINE_PING $it") }
            .onFailure { AppLog.w("ENGINE_PING failed: ${it.message}") }

        for (dir in pluginDirs) {
            // 单个插件失败只记日志，不影响整体 Ready。
            runCatching {
                val res = call("plugins.load", JSONObject().put("dir", dir.absolutePath))
                AppLog.i("ENGINE_PLUGIN_LOADED ${dir.name} $res")
            }.onFailure { t ->
                // 启动期的失败没有人在等结果，只有 logcat 一个出口：堆栈与包内根层条目都写出来。
                AppLog.w("ENGINE_PLUGIN_FAILED ${dir.name}: ${t.message}")
                val detail = (t as? NodeEngineException)?.detail
                val entries = detail?.optJSONArray("entries")
                if (entries != null) {
                    val names = (0 until entries.length()).map { entries.optString(it) }
                    AppLog.w("ENGINE_PLUGIN_ENTRIES ${dir.name} -> " + names.joinToString(" "))
                }
                detail?.optString("stack")?.takeIf { it.isNotBlank() }
                    ?.let { AppLog.w("ENGINE_PLUGIN_STACK ${dir.name}:\n$it") }
            }
        }
        Result.success(actual)
    }

    override suspend fun call(method: String, params: JSONObject): JSONObject = callLock.withLock {
        withContext(Dispatchers.IO) {
            val id = ids.getAndIncrement().toString()
            val line = LineProtocol.encode(method, params, id)
            val w = writer ?: throw NodeEngineException("transport", "引擎未连接")
            val r = reader ?: throw NodeEngineException("transport", "引擎未连接")
            try {
                w.write(line)
                w.write("\n")
                w.flush()
                val reply = LineProtocol.readMatchingReply(
                    readLine = { r.readLine() },
                    expectedId = id,
                    onStale = { staleId -> AppLog.w("ENGINE_RPC 丢弃错配回复（期望 $id，实际 $staleId）") },
                    // 反向请求就地服务：引擎此刻正阻塞等这次应答，不可能同时发自己的回复，不会死锁。
                    onRequest = { request -> serveHostRequest(request) },
                )
                when (reply) {
                    is LineProtocol.Reply.Ok -> reply.result
                    is LineProtocol.Reply.Error ->
                        throw NodeEngineException(reply.stage, reply.message, detail = reply.detail)
                }
            } catch (t: NodeEngineException) {
                throw t
            } catch (t: Throwable) {
                closeSocket()
                val msg = "引擎连接中断: ${t.message}"
                AppLog.e("ENGINE_CALL $method failed: $msg", t)
                _state.value = NodeEngineState.Failed(msg)
                throw NodeEngineException("transport", msg, t)
            }
        }
    }

    override suspend fun rpc(path: String, endpoint: String, payload: JSONObject): JSONObject =
        call(
            "rpc",
            JSONObject()
                .put("path", path)
                .put("endpoint", endpoint)
                .put("payload", payload),
        )

    override suspend fun stop() {
        startLock.withLock {
            stopping = true
            // 刻意不发 shutdown：bin.js 的 shutdown 是 server.close() + process.exit(0)，而
            // libnode 里的 exit() 会带走整个 Android 进程 —— 实测 pid 7348 SIGABRT
            // （F libc: Fatal signal 6 (SIGABRT) in tid 7402 (hwuiTask0)），随后
            // ActivityManager 重新拉起 RuntimeService。所以这里只断客户端，node-main 线程
            // 活到进程结束；libnode 不支持二次启动，重启引擎本来也只能等下一个进程。
            closeSocket()
            _state.value = NodeEngineState.Stopped
            AppLog.i("ENGINE_STOPPED socket closed, node thread kept alive")
        }
    }

    // ---- 内部 ----

    /**
     * 服务一条引擎反向请求并写回应答。
     *
     * 发生在 [call] 的读循环里：调用线程此刻在等 expectedId 的回复，所以这里用 runBlocking 把
     * suspending 的 handler 跑完再继续读 —— 引擎在拿到这次应答前不会写自己的回复，顺序安全。
     * handler 不得回调 [call]（同一把 [callLock] 会自锁），见 [HostCallHandler]。
     */
    private fun serveHostRequest(request: LineProtocol.Request) {
        // 引擎统一用 host.call 包一层，真实方法名与参数在 params 里；其余方法名直接透传。
        val innerMethod: String
        val innerParams: JSONObject
        if (request.method == "host.call") {
            innerMethod = request.params.optString("method")
            innerParams = request.params.optJSONObject("payload") ?: JSONObject()
            if (innerMethod.isEmpty()) {
                val line = LineProtocol.encodeError(request.id, "host", "host.call 缺少 params.method")
                writeHostReply(line)
                return
            }
        } else {
            innerMethod = request.method
            innerParams = request.params
        }
        val handler = hostHandler
        val line = try {
            if (handler == null) {
                LineProtocol.encodeError(request.id, "host", "宿主没有注册反向请求处理器: $innerMethod")
            } else {
                LineProtocol.encodeReply(request.id, runBlocking { handler.handle(innerMethod, innerParams) })
            }
        } catch (t: Throwable) {
            AppLog.w("ENGINE_HOST_CALL $innerMethod failed: ${t.message}")
            LineProtocol.encodeError(
                request.id,
                (t as? NodeEngineException)?.stage ?: "host",
                t.message ?: t.toString(),
            )
        }
        writeHostReply(line)
    }

    private fun writeHostReply(line: String) {
        val w = writer ?: return
        runCatching {
            w.write(line)
            w.write("\n")
            w.flush()
        }.onFailure { AppLog.w("ENGINE_HOST_CALL 写回应答失败: ${it.message}") }
    }

    private fun onNodeExit(rc: Result<Int>) {
        if (stopping) return
        closeSocket()
        val detail = rc.fold(
            onSuccess = { "node 已退出，退出码 $it" },
            onFailure = { "node 启动失败: ${it.message}" },
        )
        AppLog.e("ENGINE_NODE_EXIT $detail", rc.exceptionOrNull())
        _state.value = NodeEngineState.Failed(detail, retryable = false)
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }

    private fun pickPort(): Int =
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun openSocket(target: Int) {
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", target), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        socket = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
    }

    @Volatile private var hostHandler: HostCallHandler? = null

    @Volatile private var lastPortFileError: String = ""

    /** @return 端口号；超时返回 0；引擎写了 ERR 返回 [ERR_CODE]。 */
    private suspend fun pollPortFile(timeoutMs: Long): Int {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_INTERVAL_MS)
            val text = withContext(Dispatchers.IO) {
                runCatching { if (portFile.isFile) portFile.readText().trim() else null }.getOrNull()
            } ?: continue
            if (text.isEmpty()) continue
            if (text.startsWith("ERR")) {
                lastPortFileError = text.removePrefix("ERR").trim()
                return ERR_CODE
            }
            text.toIntOrNull()?.let { if (it in 1..65535) return it }
        }
        return 0
    }

    private fun ensureExtracted() {
        homeDir.mkdirs()
        val recorded = if (markerFile.isFile) parseMarker(runCatching { markerFile.readText() }.getOrDefault("")) else null
        if (recorded != null && recorded == measure(engineDir)) {
            AppLog.i("ENGINE_ASSETS reuse files=${recorded.first} bytes=${recorded.second}")
            return
        }
        // 标记缺失或与实际文件不符（上次解包中断 / 引擎包换过）：整个目录重来。
        engineDir.deleteRecursively()
        engineDir.mkdirs()
        val stats = longArrayOf(0L, 0L)
        val started = SystemClock.elapsedRealtime()
        unzipFromAssets(ASSET_ZIP, engineDir, stats)
        markerFile.writeText("files=${stats[0]} bytes=${stats[1]}\n")
        AppLog.i("ENGINE_ASSETS extracted files=${stats[0]} bytes=${stats[1]} ms=${SystemClock.elapsedRealtime() - started}")
    }

    private fun parseMarker(text: String): Pair<Long, Long>? {
        val files = Regex("files=(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val bytes = Regex("bytes=(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return files to bytes
    }

    /** 解包目录里实际的 (文件数, 总字节)，不算标记文件本身。 */
    private fun measure(dir: File): Pair<Long, Long> {
        var files = 0L
        var bytes = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile && f.name != markerFile.name) {
                files++
                bytes += f.length()
            }
        }
        return files to bytes
    }

    /**
     * 从 assets 里的单个 zip 解包。
     *
     * 为什么不用「assets 目录 + AssetManager.list 递归」：aapt2 打包 assets 时会跳过名字以
     * '.' 或 '_' 开头的条目，实测 engine 的 851 个文件只进去 827 个 —— node_modules/.bin 下的 12 个
     * 和 node_modules/skills/dist/_chunks 下的 11 个 mjs 全被吃掉，而 cli.mjs 正是 import 它们，
     * 属于会真炸的缺失。整包压成一个 zip 就绕开了 aapt 的命名规则（实测 851/851 全在）。
     */
    private fun unzipFromAssets(assetZip: String, dest: File, stats: LongArray) {
        appContext.assets.open(assetZip).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.isNotEmpty() && !name.contains("..")) {
                        val target = File(dest, name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> zip.copyTo(out, DEFAULT_BUFFER_SIZE) }
                            stats[0]++
                            stats[1] += target.length()
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }

    private companion object {
        const val ASSET_ZIP = "runtime/engine.zip"
        const val PORT_WAIT_MS = 25_000L
        const val POLL_INTERVAL_MS = 50L
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 30_000
        const val ERR_CODE = -1
    }
}
