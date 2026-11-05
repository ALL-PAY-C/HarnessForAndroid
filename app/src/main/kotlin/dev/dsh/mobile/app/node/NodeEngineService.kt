package dev.dsh.mobile.app.node

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.runtimehost.HostCallHandler
import dev.dsh.mobile.runtimehost.NodeEngineException
import dev.dsh.mobile.runtimehost.NodeEngineHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 跑在 `:node` 独立进程里的引擎宿主。
 *
 * 进程内还是老的 [NodeEngineHost]（libnode + assets/runtime/engine.zip + 127.0.0.1 TCP
 * 行协议），一点没改；这个类只是把它包成 AIDL 服务，跨进程的两件事在这里转接：
 *
 *  1. 请求方向：主进程 call/rpc/ensureStarted → 这里同步 [runBlocking] 到 NodeEngineHost；
 *  2. 反向请求：NodeEngineHost 的 HostCallHandler 换成调 [IDshNodeEngineCallback]，
 *     也就是回主进程的 PluginHostCalls（llm.chat / credentials.*）。
 *
 * 线程模型：binder 上收到的四个会落到引擎的方法（ensureStarted / call / rpc / stop）全是
 * oneway，binder 线程只做入队就返回；真正的 runBlocking 跑在 [worker] 这条专用单线程上，
 * 结果通过 [IDshNodeEngineResult] 异步送回主进程。binder 池因此永远不会被引擎的长调用占满。
 *
 * 失败语义：一律走应答信封，不再抛 [RemoteException] ——
 *  * ensureStarted 失败 → {"ok":false,"error":{"stage":"start","message":"..."}}；
 *  * call / rpc 失败 → stage / message / detail 原样带回（见 [envelope]）。
 */
class NodeEngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var engine: NodeEngineHost

    /** 主进程注册的回调；null 时引擎的反向请求会立刻得到 stage=host 的错误而不是超时。 */
    @Volatile
    private var callback: IDshNodeEngineCallback? = null

    /** 主进程注册的应答回调；null 时结果只进日志（主进程已解绑）。 */
    @Volatile
    private var results: IDshNodeEngineResult? = null

    /**
     * 引擎调用的唯一执行线程。
     *
     * 单线程是刻意的：引擎侧是「一条 socket 行协议 + 一个 node-main 线程」，并发进来也只是排队，
     * 单线程反而省掉了引擎内部的锁竞争；更关键的是它把阻塞从 binder 线程上彻底摘走。
     */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "node-engine-calls") }

    override fun onCreate() {
        super.onCreate()
        // 这行是「:node 换了一个新进程」的唯一证据：pid 变化 + startCount 归 1。
        AppLog.i("ENGINE_NODE_PROCESS boot pid=${Process.myPid()}")
        val host = NodeEngineHost(this, scope)
        host.setHostCallHandler(object : HostCallHandler {
            override suspend fun handle(method: String, params: JSONObject): JSONObject =
                forwardToHost(method, params)
        })
        engine = host
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        AppLog.i("ENGINE_NODE_PROCESS destroy pid=${Process.myPid()}")
        // 停引擎同样不能在主线程上阻塞：丢给 worker，shutdown() 会把它排队里的任务跑完再收线程。
        worker.execute { runCatching { runBlocking { engine.stop() } } }
        worker.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 引擎 → 主进程。阻塞式 binder 调用：引擎此刻正卡在这次反向请求上等应答，
     * 主进程的 PluginHostCalls 是纯 Kotlin（不再回调引擎），所以同步等是安全的。
     */
    private suspend fun forwardToHost(method: String, params: JSONObject): JSONObject {
        val cb = callback ?: throw NodeEngineException("host", "主进程未注册反向请求回调（setCallback）: $method")
        val raw = withContext(Dispatchers.IO) {
            try {
                cb.handleHostCall(method, params.toString())
            } catch (t: RemoteException) {
                throw NodeEngineException("host", "回调主进程失败: ${t.message}", t)
            }
        }
        val envelope = runCatching { JSONObject(raw) }.getOrElse {
            throw NodeEngineException("host", "主进程回调应答不是 JSON: ${raw.take(200)}", it)
        }
        if (envelope.optBoolean("ok", false)) {
            return envelope.optJSONObject("result") ?: JSONObject()
        }
        val err = envelope.optJSONObject("error") ?: JSONObject()
        throw NodeEngineException(
            err.optString("stage").ifEmpty { "host" },
            err.optString("message").ifEmpty { "主进程反向请求失败" },
        )
    }

    private fun jsonOf(text: String?): JSONObject =
        if (text.isNullOrBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject() }

    private fun errorEnvelope(stage: String, message: String, detail: JSONObject?): JSONObject {
        val err = JSONObject().put("stage", stage).put("message", message)
        if (detail != null && detail.length() > 0) err.put("detail", detail)
        return JSONObject().put("ok", false).put("error", err)
    }

    /** 把一次 call/rpc 包成信封，让 stage / detail 穿过 binder 边界。 */
    private suspend fun envelope(block: suspend () -> JSONObject): String =
        try {
            JSONObject().put("ok", true).put("result", block()).toString()
        } catch (t: NodeEngineException) {
            errorEnvelope(t.stage, t.message ?: "", t.detail).toString()
        } catch (t: Throwable) {
            AppLog.w("ENGINE_NODE_OP failed: ${t.message}", t)
            errorEnvelope("internal", t.message ?: t.toString(), null).toString()
        }

    /** 把结果送回主进程；主进程已解绑/已死时静默丢弃（它那边的 awaitEnvelope 会按超时收场）。 */
    private fun reply(requestId: String, envelope: JSONObject) {
        val cb = results ?: return
        runCatching { cb.onResult(requestId, envelope.toString()) }
            .onFailure { AppLog.w("ENGINE_NODE_REPLY failed id=${requestId.take(8)}: ${it.message}") }
    }

    private val binder = object : IDshNodeEngine.Stub() {

        override fun setCallback(cb: IDshNodeEngineCallback?) {
            callback = cb
            AppLog.i("ENGINE_NODE_CALLBACK ${if (cb == null) "cleared" else "registered"}")
        }

        override fun setResultCallback(cb: IDshNodeEngineResult?) {
            results = cb
            AppLog.i("ENGINE_NODE_RESULT_CALLBACK ${if (cb == null) "cleared" else "registered"}")
        }

        override fun ensureStarted(requestId: String?, pluginDirs: Array<out String>?) {
            val id = requestId.orEmpty()
            val dirs = pluginDirs?.map { File(it) }.orEmpty()
            worker.execute {
                val started = runBlocking { engine.ensureStarted(dirs) }
                reply(
                    id,
                    started.fold(
                        onSuccess = { port ->
                            AppLog.i("ENGINE_NODE_START ok port=$port pid=${Process.myPid()} plugins=${dirs.size}")
                            JSONObject().put("ok", true).put("result", JSONObject().put("port", port))
                        },
                        onFailure = { t ->
                            val msg = t.message ?: "引擎启动失败"
                            AppLog.e("ENGINE_NODE_START failed pid=${Process.myPid()}: $msg", t)
                            errorEnvelope("start", msg, null)
                        },
                    ),
                )
            }
        }

        override fun call(requestId: String?, method: String?, paramsJson: String?) {
            val id = requestId.orEmpty()
            worker.execute {
                reply(id, JSONObject(runBlocking { envelope { engine.call(method.orEmpty(), jsonOf(paramsJson)) } }))
            }
        }

        override fun rpc(requestId: String?, path: String?, endpoint: String?, payloadJson: String?) {
            val id = requestId.orEmpty()
            worker.execute {
                reply(
                    id,
                    JSONObject(
                        runBlocking {
                            envelope { engine.rpc(path.orEmpty(), endpoint.orEmpty(), jsonOf(payloadJson)) }
                        },
                    ),
                )
            }
        }

        override fun stop(requestId: String?) {
            val id = requestId.orEmpty()
            worker.execute {
                val failure = runCatching { runBlocking { engine.stop() } }.exceptionOrNull()
                AppLog.i("ENGINE_NODE_STOP socket closed, node thread kept alive pid=${Process.myPid()}")
                reply(
                    id,
                    if (failure == null) {
                        JSONObject().put("ok", true).put("result", JSONObject())
                    } else {
                        errorEnvelope("stop", failure.message ?: "引擎停止失败", null)
                    },
                )
            }
        }

        /**
         * 自杀。libnode 不支持二次启动，所以「换一个新 Node 实例」唯一可行的做法是换进程：
         * 这里杀掉 :node，主进程收到 binder death 后再 bindService 就是全新的 libnode。
         * 延迟 150ms 是为了让这次 binder 应答先回到主进程。
         */
        override fun restart(): Int {
            val pid = Process.myPid()
            AppLog.i("ENGINE_NODE_RESTART requested pid=$pid")
            Handler(Looper.getMainLooper()).postDelayed({
                AppLog.i("ENGINE_NODE_RESTART suicide pid=$pid")
                Process.killProcess(pid)
            }, RESTART_DELAY_MS)
            return 0
        }

        override fun isRunning(): Boolean = engine.isRunning()
    }

    private companion object {
        /** 让 restart() 的 binder 应答先送达主进程，再动手自杀。 */
        const val RESTART_DELAY_MS = 150L
    }
}
