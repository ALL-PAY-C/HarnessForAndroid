package dev.dsh.mobile.core.common

/**
 * 本机网关（127.0.0.1:PORT）的就绪回调。
 *
 * 模型地址常常填的是本机网关，网关是插件提供的进程：它可能还没启动、被系统杀掉、
 * 或者上一次启动还在绑端口。请求方在发请求前先调 [ensure] 把它拉起来，
 * 连不上时再重试一次 —— 这就是「断断续续」的根因治法。
 *
 * 插件在 [dev.dsh.mobile.pluginapi.BackgroundPlugin.ensureStarted] 里 [install] 自己。
 */
object LoopbackGateway {

    @Volatile
    private var starter: (suspend () -> Unit)? = null

    /** 插件装载后注册启动器；重复注册以最后一次为准。 */
    fun install(starter: suspend () -> Unit) {
        this.starter = starter
    }

    fun uninstall() {
        starter = null
    }

    fun installed(): Boolean = starter != null

    /** 幂等：启动器自己保证重复调用不出第二个进程。 */
    suspend fun ensure(): Boolean {
        val s = starter ?: return false
        return runCatching { s() }.onFailure { AppLog.w("loopback gateway start failed: " + it.message) }.isSuccess
    }

    /** 只有回环地址才需要拉起网关。 */
    fun isLoopback(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        return h == "127.0.0.1" || h == "localhost" || h == "::1" || h == "[::1]" || h == "0.0.0.0"
    }
}
