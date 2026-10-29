package dev.dsh.mobile.app.node

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import dev.dsh.mobile.core.common.AppLog
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
 * 为什么 binder 线程上可以直接阻塞：AIDL 方法是同步的，:node 只有主进程一个客户端，
 * 每个请求各自占一条 binder 线程，被阻塞的是 binder 线程池而不是主进程 UI 线程；
 * 反向回调走主进程的另一条 binder 线程，不构成环。
 *
 * 失败语义：
 *  * [ensureStarted] 抛 [RemoteException]（message 即原因，主进程侧拿到 RemoteException 再包成 NodeEngineException）；
 *  * call / rpc 不抛，返回信封 JSON，把 stage / detail 原样带回去。
 */
class NodeEngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var engine: NodeEngineHost

    /** 主进程注册的回调；null 时引擎的反向请求会立刻得到 stage=host 的错误而不是超时。 */
    @Volatile
    private var callback: IDshNodeEngineCallback? = null

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
        runCatching { runBlocking { engine.stop() } }
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

    private val binder = object : IDshNodeEngine.Stub() {

        override fun setCallback(cb: IDshNodeEngineCallback?) {
            callback = cb
            AppLog.i("ENGINE_NODE_CALLBACK ${if (cb == null) "cleared" else "registered"}")
        }

        override fun ensureStarted(pluginDirs: Array<out String>?): Int {
            val dirs = pluginDirs?.map { File(it) }.orEmpty()
            val started = runBlocking { engine.ensureStarted(dirs) }
            return started.getOrElse { t ->
                val msg = t.message ?: "引擎启动失败"
                AppLog.e("ENGINE_NODE_START failed pid=${Process.myPid()}: $msg", t)
                throw RemoteException(msg)
            }.also { port ->
                AppLog.i("ENGINE_NODE_START ok port=$port pid=${Process.myPid()} plugins=${dirs.size}")
            }
        }

        override fun call(method: String?, paramsJson: String?): String = runBlocking {
            envelope { engine.call(method.orEmpty(), jsonOf(paramsJson)) }
        }

        override fun rpc(path: String?, endpoint: String?, payloadJson: String?): String = runBlocking {
            envelope { engine.rpc(path.orEmpty(), endpoint.orEmpty(), jsonOf(payloadJson)) }
        }

        override fun stop() {
            runBlocking { engine.stop() }
            AppLog.i("ENGINE_NODE_STOP socket closed, node thread kept alive pid=${Process.myPid()}")
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
