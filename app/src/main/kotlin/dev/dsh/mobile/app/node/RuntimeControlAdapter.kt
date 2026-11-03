package dev.dsh.mobile.app.node

import dev.dsh.mobile.app.AppContainer
import dev.dsh.mobile.core.model.RuntimeControl
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.TransportKind
import dev.dsh.mobile.runtimehost.NodeEngineHost
import dev.dsh.mobile.runtimehost.NodeEngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 设置页「运行时」面板的 [RuntimeControl] 实现。
 *
 * 全部数据来自 `:node` 子进程：连接态取 [dev.dsh.mobile.app.AppContainer.nodeEngine]
 * （RemoteNodeEngine，AIDL 代理到 NodeEngineService 里的 NodeEngineHost），版本/占用/清日志
 * 用 NodeEngineHost 公开的路径常量直接算文件 —— 主进程与 :node 共享同一份 filesDir。
 *
 * 这层原本适配已删除的进程内运行时宿主（它从 assets 里按版本/ABI 解出 node 可执行文件，
 * 在进程内起引擎）。那条路径已经死了：APK 里只投 runtime/engine.zip。
 */
class RuntimeControlAdapter(private val container: AppContainer) : RuntimeControl {

    /** transport 在跑就报 transport 态，否则报 :node 引擎自己的态。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: StateFlow<RuntimeState> = container.transport
        .flatMapLatest { transport -> transport?.state ?: container.nodeEngine.state.map(::toRuntimeState) }
        .stateIn(
            container.scope,
            SharingStarted.WhileSubscribed(5_000),
            toRuntimeState(container.nodeEngine.state.value),
        )

    /**
     * 只有一个打包版本：APK 里的 assets/runtime/engine.zip 由构建期从 engine/ 生成，
     * 版本号就是 NodeEngineHost 的解包目录名（engine-v1）。
     *
     * 探测不到 engine.zip 时返回空表（构建配置坏了，设置页显示「未内置」），
     * 不硬编码一个恒真值 -- 那样打包出问题就再也看不出来了。
     */
    override fun versions(): List<String> =
        if (engineBundled) listOf(NodeEngineHost.ENGINE_VERSION) else emptyList()

    /**
     * 内置版本只有一个；"bundled" 是 SettingsStore.runtimeVersion 的历史占位默认值，
     * 认它等于内置包。其它版本号返回 false —— 那确实没有对应的包。
     */
    override fun hasBundled(version: String): Boolean =
        engineBundled && (version == NodeEngineHost.ENGINE_VERSION || version == LEGACY_BUNDLED)

    /**
     * :node 引擎真正占的目录（解包目录 engine-v1 + HOME）。
     *
     * 接口是同步的，所以挂在 IO 线程上算：只 walk 私有 files/runtime/（几千个文件），
     * 不是全盘遍历。量级再大会改成后台预热 + 缓存，现在是够的。
     */
    override fun storageBytes(): Long = runBlocking(Dispatchers.IO) {
        val root = NodeEngineHost.rootDirFor(container.appContext)
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 引擎日志走 logcat（libnode 的 stdout 被 native 侧重定向），落盘目录通常不存在。
     * 这里只清 NodeEngineHost 声明的日志目录里的内容，不碰 home 下的会话与配置；
     * 目录不存在就是空操作 —— 不为了「看起来有日志」把它建出来。
     */
    override suspend fun clearLogs() = withContext(Dispatchers.IO) {
        NodeEngineHost.logDirsFor(container.appContext).forEach { dir ->
            dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        }
    }

    /**
     * 重启引擎 = 杀掉 :node，再走现有的 connectActive 重建 transport。
     *
     * RemoteNodeEngine.stop() 会 binder restart() 让 :node 自杀并解绑（否则 BIND_AUTO_CREATE
     * 会把它又拉起来），下一次 ensureStarted 自然 bind 到全新进程；不新造状态机。
     */
    override fun reconnect() {
        container.scope.launch {
            runCatching { container.nodeEngine.stop() }
            container.connectActive(force = true)
        }
    }

    /**
     * APK 里有没有 engine.zip。这是「有没有内置引擎」的唯一真实判据 ——
     * 旧实现的 assets/runtime/<version>/<abi>/node 布局已经不存在。
     */
    private val engineBundled: Boolean get() = container.engineBundled

    private companion object {
        const val LEGACY_BUNDLED = "bundled"

        /**
         * :node 侧的引擎态 -> UI 的 RuntimeState。
         *
         * Ready 报 [TransportKind.InProcess]：引擎确实是本机进程（:node），不是远端 WebSocket。
         * 状态文案沿用 :node 的 detail，设置页 StatusCapsule 只按 RuntimeState 画，展示不变。
         */
        fun toRuntimeState(state: NodeEngineState): RuntimeState = when (state) {
            NodeEngineState.Stopped -> RuntimeState.NotConfigured
            is NodeEngineState.Starting -> RuntimeState.Starting(state.detail)
            is NodeEngineState.Ready -> RuntimeState.Ready(TransportKind.InProcess, "本地引擎 :node")
            is NodeEngineState.Failed -> RuntimeState.Failed(state.message, state.retryable)
        }
    }
}
