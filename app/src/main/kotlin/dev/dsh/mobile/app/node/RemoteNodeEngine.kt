package dev.dsh.mobile.app.node

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.runtimehost.HostCallHandler
import dev.dsh.mobile.runtimehost.NodeEngine
import dev.dsh.mobile.runtimehost.NodeEngineException
import dev.dsh.mobile.runtimehost.NodeEngineState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * 主进程侧的 [NodeEngine]：把每一条调用转发给 `:node` 进程里的 [NodeEngineService]。
 *
 * 与 [dev.dsh.mobile.runtimehost.NodeEngineHost] 的关系：
 *  * NodeEngineHost 仍然是唯一「真正」的实现，只是它现在活在 `:node` 进程里；
 *  * 这里不碰 socket，也不碰 libnode —— 主进程进程里永远不会再出现第二次 node::Start。
 *
 * 状态语义（[state]）：
 *  * Stopped —— 没绑 / 主进程主动 stop（stop 会连 :node 一起杀掉，下次启动是全新 Node）；
 *  * Starting —— 正在等 binder 或引擎监听；
 *  * Ready(port) —— 引擎已就绪，port 是 :node 里 127.0.0.1 的监听端口（主进程不直接用）；
 *  * Failed(retryable=true) —— binder 断了（:node 崩了 / 引擎 process.exit / 被 kill）。
 *    这是本设计要的结果：引擎自杀只死一个子进程，主进程 pid 不变，再 ensureStarted 就换新实例。
 *
 * 绑定策略：每次 ensureStarted / call 前都确认 binder 活着，死了就 unbind + 重 bind，
 * 并用 bindGen 让上一条连接迟到的回调失效（否则旧 onServiceDisconnected 会把新 binder 清掉）。
 */
class RemoteNodeEngine(
    context: Context,
    /** 与 [dev.dsh.mobile.runtimehost.NodeEngineHost] 同形的构造参数，调用点不用为两种实现写两套。 */
    scope: CoroutineScope,
    /** :node 非预期死亡（真崩 / 被系统杀）的回调；主动 stop() 不走这里。 */
    private val onUnexpectedDeath: ((String) -> Unit)? = null,
) : NodeEngine {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<NodeEngineState>(NodeEngineState.Stopped)
    override val state: StateFlow<NodeEngineState> = _state.asStateFlow()

    private val bindLock = Mutex()

    @Volatile private var remote: IDshNodeEngine? = null

    /** :node 是否还活着（由 ServiceConnection 回调维护），stop() 用它确认进程真的死了。 */
    @Volatile private var alive = false

    /** stop() 触发的死亡不该被报成 Failed。 */
    @Volatile private var expectedDeath = false

    @Volatile private var pending: CompletableDeferred<IDshNodeEngine>? = null

    @Volatile private var hostHandler: HostCallHandler? = null

    private var activeConnection: ServiceConnection? = null

    /** 每次 bind/unbind 自增：旧连接的迟到回调按代号丢弃。 */
    private var bindGen = 0

    /** :node → 主进程的反向请求（llm.chat / credentials.*），binder 线程上同步服务。 */
    private val callback = object : IDshNodeEngineCallback.Stub() {
        override fun handleHostCall(method: String?, paramsJson: String?): String {
            val name = method.orEmpty()
            val handler = hostHandler
                ?: return envelopeError("host", "主进程没有注册 HostCallHandler: $name")
            val params = runCatching { JSONObject(paramsJson ?: "{}") }.getOrElse { JSONObject() }
            return try {
                // binder 线程上阻塞没问题：引擎正卡在这次反向请求上等应答，主线程不参与。
                val result = runBlocking(Dispatchers.IO) { handler.handle(name, params) }
                JSONObject().put("ok", true).put("result", result).toString()
            } catch (t: Throwable) {
                AppLog.w("ENGINE_HOST_CALL $name failed: ${t.message}")
                envelopeError((t as? NodeEngineException)?.stage ?: "host", t.message ?: t.toString())
            }
        }
    }

    /** 在途请求：requestId → 应答信封。:node 侧 oneway 调用一发出就登记，结果从 [resultCallback] 回来。 */
    private val pendingResults = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** :node → 主进程的应答通道（T9.14 修 4）。这里只做 complete，不阻塞 binder 线程。 */
    private val resultCallback = object : IDshNodeEngineResult.Stub() {
        override fun onResult(requestId: String?, envelopeJson: String?) {
            val id = requestId.orEmpty()
            val deferred = pendingResults.remove(id)
            if (deferred == null) {
                AppLog.w("ENGINE_REMOTE_RESULT orphan id=${id.take(8)}")
                return
            }
            deferred.complete(envelopeJson.orEmpty())
        }
    }

    override fun setHostCallHandler(handler: HostCallHandler?) {
        hostHandler = handler
    }

    override fun isRunning(): Boolean {
        val svc = remote ?: return false
        return try {
            svc.asBinder().isBinderAlive && svc.isRunning
        } catch (t: RemoteException) {
            false
        }
    }

    override suspend fun ensureStarted(pluginDirs: List<File>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val svc = service()
            _state.value = NodeEngineState.Starting("等待 :node 引擎监听")
            val dirs = pluginDirs.map { it.absolutePath }.toTypedArray()
            val envelope = JSONObject(awaitEnvelope("start", START_TIMEOUT_MS) { id -> svc.ensureStarted(id, dirs) })
            if (!envelope.optBoolean("ok", false)) {
                val err = envelope.optJSONObject("error") ?: JSONObject()
                throw NodeEngineException(
                    err.optString("stage").ifEmpty { "start" },
                    err.optString("message").ifEmpty { "远程引擎启动失败" },
                )
            }
            val port = envelope.optJSONObject("result")?.optInt("port", 0) ?: 0
            _state.value = NodeEngineState.Ready(port)
            AppLog.i("ENGINE_REMOTE_READY port=$port mainPid=${Process.myPid()}")
            Result.success(port)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val msg = t.message ?: "远程引擎启动失败"
            AppLog.e("ENGINE_REMOTE_START failed: $msg", t)
            _state.value = NodeEngineState.Failed(msg, retryable = true)
            Result.failure(if (t is NodeEngineException) t else NodeEngineException("start", msg, t))
        }
    }

    override suspend fun call(method: String, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val svc = service()
        decode(awaitEnvelope("call $method", CALL_TIMEOUT_MS) { id -> svc.call(id, method, params.toString()) })
    }

    override suspend fun rpc(path: String, endpoint: String, payload: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val svc = service()
            decode(
                awaitEnvelope("rpc $path", CALL_TIMEOUT_MS) { id ->
                    svc.rpc(id, path, endpoint, payload.toString())
                },
            )
        }

    /**
     * 断 socket **并且**把 :node 杀掉。
     *
     * 进程内版本只能断 socket（node-main 线程杀不掉、libnode 不能二次启动），于是 stop 之后
     * 引擎就永远回不到干净状态。这里补上最后一步：restart() 让 :node 自杀，等 binder 真的断掉
     * 再返回 —— 之后任何一次 ensureStarted 都会 bind 到一个全新的进程（全新 libnode）。
     */
    override suspend fun stop() {
        val svc = remote
        if (svc == null) {
            _state.value = NodeEngineState.Stopped
            return
        }
        expectedDeath = true
        withContext(Dispatchers.IO) {
            runCatching { awaitEnvelope("stop", STOP_TIMEOUT_MS) { id -> svc.stop(id) } }
                .onFailure { AppLog.w("ENGINE_REMOTE_STOP ${it.message}") }
            runCatching { svc.restart() }.onFailure { AppLog.w("ENGINE_REMOTE_RESTART ${it.message}") }
            // 立刻解绑：只要还有 client 绑着，系统就会在 :node 死后按 BIND_AUTO_CREATE 再把它拉起来，
            // stop() 就等于没停。解绑之后那个进程没有活下去的理由，restart() 的 kill 又保证它必死。
            bindLock.withLock {
                unbindLocked()
                expectedDeath = false
            }
            // 等自杀落地（restart() 内部延迟 150ms 再 kill），否则紧接着的 ensureStarted 会绑到将死的进程。
            delay(STOP_SETTLE_MS)
        }
        _state.value = NodeEngineState.Stopped
        AppLog.i("ENGINE_REMOTE_STOPPED killed=true mainPid=${Process.myPid()}")
    }

    // ---- 绑定 / 转发 ----

    private suspend fun service(): IDshNodeEngine = bindLock.withLock {
        val existing = remote
        if (existing != null && existing.asBinder().isBinderAlive) return@withLock existing
        unbindLocked()

        val gen = bindGen
        val deferred = CompletableDeferred<IDshNodeEngine>()
        pending = deferred
        val connection = connectionFor(gen)
        activeConnection = connection
        val ok = runCatching {
            appContext.bindService(
                Intent(appContext, NodeEngineService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrElse { false }
        if (!ok) {
            pending = null
            activeConnection = null
            throw NodeEngineException("transport", "无法绑定 :node 引擎进程")
        }
        _state.value = NodeEngineState.Starting("等待 :node 进程")
        val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
        if (bound == null) {
            pending = null
            unbindLocked()
            throw NodeEngineException("transport", "绑定 :node 引擎进程超时（${BIND_TIMEOUT_MS}ms）")
        }
        bound
    }

    private fun connectionFor(gen: Int): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (gen != bindGen) {
                runCatching { appContext.unbindService(this) }
                return
            }
            val svc = IDshNodeEngine.Stub.asInterface(service)
            if (svc == null) {
                lost(gen, "引擎进程返回空 binder")
                return
            }
            remote = svc
            alive = true
            runCatching { svc.setCallback(callback) }
                .onFailure { AppLog.w("ENGINE_NODE_SET_CALLBACK ${it.message}") }
            runCatching { svc.setResultCallback(resultCallback) }
                .onFailure { AppLog.w("ENGINE_NODE_SET_RESULT_CALLBACK ${it.message}") }
            AppLog.i("ENGINE_REMOTE_BOUND :node mainPid=${Process.myPid()}")
            pending?.complete(svc)
            pending = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            lost(gen, "引擎进程已退出（:node binder 断开）")
        }

        override fun onBindingDied(name: ComponentName?) {
            lost(gen, "引擎进程绑定失效（:node 已重启）")
        }

        override fun onNullBinding(name: ComponentName?) {
            lost(gen, "引擎进程拒绝绑定（onNullBinding）")
        }
    }

    private fun lost(gen: Int, reason: String) {
        if (gen != bindGen) return
        alive = false
        remote = null
        pending?.completeExceptionally(NodeEngineException("transport", reason))
        pending = null
        // 在途请求全部就地失败，别让调用方干等到自己的超时。
        pendingResults.values.forEach { it.completeExceptionally(NodeEngineException("transport", reason)) }
        pendingResults.clear()
        if (expectedDeath) {
            expectedDeath = false
            _state.value = NodeEngineState.Stopped
            AppLog.i("ENGINE_REMOTE_LOST expected: $reason")
            return
        }
        AppLog.w("ENGINE_REMOTE_LOST $reason")
        _state.value = NodeEngineState.Failed(reason, retryable = true)
        // 只有「没打算死却死了」才是崩溃物证：主动 stop / 重绑都走上面的 expectedDeath 分支。
        runCatching { onUnexpectedDeath?.invoke(reason) }
    }

    private fun unbindLocked() {
        bindGen += 1
        val connection = activeConnection
        activeConnection = null
        alive = false
        remote = null
        if (connection != null) {
            runCatching { appContext.unbindService(connection) }
                .onFailure { AppLog.w("ENGINE_REMOTE_UNBIND ${it.message}") }
        }
    }

    /**
     * 发一条 oneway 调用并等它的应答（T9.14 修 4）。
     *
     * 以前这里是同步 AIDL：一条 llm.chat 就占住一条 binder 线程几分钟，16 条线程池一满，
     * 连 :node 的反向 handleHostCall 都排不上。现在 binder 只做入队，结果从 [resultCallback] 回来。
     *
     * requestId 每次新生成，finally 里必定移除：:node 死了的话应答永远不来，
     * 超时抛 NodeEngineException，调用方按 transport 失败处理。
     */
    private suspend fun awaitEnvelope(what: String, timeoutMs: Long, send: (String) -> Unit): String {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingResults[id] = deferred
        try {
            send(id)
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: throw NodeEngineException("transport", "引擎应答超时（$what，${timeoutMs}ms）")
        } catch (t: RemoteException) {
            val msg = "引擎进程连接中断（$what）: ${t.message}"
            AppLog.e("ENGINE_REMOTE_CALL $msg", t)
            _state.value = NodeEngineState.Failed(msg, retryable = true)
            throw NodeEngineException("transport", msg, t)
        } finally {
            pendingResults.remove(id)
        }
    }

    private fun decode(raw: String): JSONObject {
        val envelope = runCatching { JSONObject(raw) }.getOrElse {
            throw NodeEngineException("protocol", "远程引擎应答不是 JSON: ${raw.take(200)}", it)
        }
        if (envelope.optBoolean("ok", false)) {
            return envelope.optJSONObject("result") ?: JSONObject()
        }
        val err = envelope.optJSONObject("error") ?: JSONObject()
        throw NodeEngineException(
            err.optString("stage").ifEmpty { "internal" },
            err.optString("message").ifEmpty { "引擎返回失败但没有 message" },
            detail = err.optJSONObject("detail")?.takeIf { it.length() > 0 },
        )
    }

    private fun envelopeError(stage: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("error", JSONObject().put("stage", stage).put("message", message))
        .toString()

    private companion object {
        const val BIND_TIMEOUT_MS = 15_000L
        const val STOP_SETTLE_MS = 500L

        /** 启动要等 libnode 起来，给足 60s。 */
        const val START_TIMEOUT_MS = 60_000L

        /** 引擎调用没有上限（llm.chat 可能很久），这里只是防止应答永久丢失。 */
        const val CALL_TIMEOUT_MS = 600_000L
        const val STOP_TIMEOUT_MS = 30_000L
    }
}