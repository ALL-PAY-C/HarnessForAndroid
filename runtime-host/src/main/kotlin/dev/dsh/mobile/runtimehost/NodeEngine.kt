package dev.dsh.mobile.runtimehost

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * 进程内 Node 引擎的生命周期与请求通道。
 *
 * Android 上没有独立的 node 可执行文件（nodejs-mobile 只提供 libnode.so，由 JNI 调
 * node::Start 在进程内跑），所以 node 的 stdout 进 logcat、读不到管道；引擎把监听端口
 * 原子写进一个端口文件，宿主轮询该文件拿到端口后再走 127.0.0.1 TCP 的 JSON 行协议。
 */
sealed interface NodeEngineState {
    data object Stopped : NodeEngineState
    data class Starting(val detail: String) : NodeEngineState
    data class Ready(val port: Int) : NodeEngineState
    data class Failed(val message: String, val retryable: Boolean = true) : NodeEngineState
}

/** [stage] 取 load / apply / rpc / transport / start，与引擎返回的 error.stage 对齐。 */
class NodeEngineException(
    val stage: String,
    message: String,
    cause: Throwable? = null,
    /** 引擎 error 对象的原始内容（可能含 stack / dir / entries），调用方据此回显现场。 */
    val detail: JSONObject? = null,
) : Exception(message, cause)

/**
 * 引擎 → 宿主的反向请求处理器（llm.chat / llm.resolve / credentials.*）。
 *
 * 实现必须是纯 Kotlin：它在 [NodeEngine.call] 的读循环里被调用，**不得回调 [NodeEngine.call]**，
 * 否则会在同一把锁上自锁（引擎那边正等着这次反向请求的应答）。
 * 抛出的异常会被编成 {ok:false,error:{stage,message}} 回给引擎，插件侧得到的是明确的错误而不是超时。
 */
fun interface HostCallHandler {
    suspend fun handle(method: String, params: JSONObject): JSONObject
}

interface NodeEngine {
    val state: StateFlow<NodeEngineState>

    /** 幂等启动；[pluginDirs] 里的插件目录按序 plugins.load。返回实际端口。 */
    suspend fun ensureStarted(pluginDirs: List<File> = emptyList()): Result<Int>

    /** 发一条请求并返回 result 对象；失败抛 [NodeEngineException]。 */
    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject

    /** 转发到插件注册的 rpc 路径，返回插件原样 result。 */
    suspend fun rpc(path: String, endpoint: String, payload: JSONObject = JSONObject()): JSONObject

    suspend fun stop()

    fun isRunning(): Boolean

    /** 注册反向请求处理器；null = 注销（引擎侧调用会立刻收到 stage=host 的错误）。 */
    fun setHostCallHandler(handler: HostCallHandler?) {}
}
