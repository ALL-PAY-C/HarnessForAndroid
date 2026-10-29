package dev.dsh.mobile.core.model

/**
 * 完整描述一次「现在该建立什么连接」。
 *
 * 只比 endpoint.id 会在「同 ID 但改了地址/口令/心跳/工作目录」时判成没变，从而漏掉重连；
 * 所以把端点内容、运行时版本、内嵌 Node 能力、工作目录全部纳入 key。
 */
data class ConnectionKey(
    val endpointId: String,
    val kind: TransportKind,
    val url: String,
    val token: String,
    val heartbeatMs: Long,
    val endpointCwd: String,
    val workspaceCwd: String,
    val runtimeVersion: String,
    val bundledNode: Boolean,
)

/** 从当前激活端点 + 运行时配置算出连接 key（纯函数，无 Android 依赖）。 */
fun connectionKey(
    endpoint: Endpoint,
    runtimeVersion: String,
    bundledNode: Boolean,
    workspaceCwd: String,
): ConnectionKey = ConnectionKey(
    endpointId = endpoint.id,
    kind = endpoint.kind,
    url = endpoint.url,
    token = endpoint.token,
    heartbeatMs = endpoint.heartbeatMs,
    endpointCwd = endpoint.cwd,
    workspaceCwd = workspaceCwd,
    runtimeVersion = runtimeVersion,
    bundledNode = bundledNode,
)

/**
 * 判断现在要不要重连（纯函数）。
 *
 *  - force / last == null / last != next → 重连；
 *  - 同 key 且 runtime 是终止态（Failed / NotConfigured）→ 重连（重试 / 首次接入）；
 *  - 同 key 且 runtime 是 Ready / Starting / Reconnecting → 跳过，不打断在途或已就绪的连接。
 */
fun shouldReconnect(
    last: ConnectionKey?,
    next: ConnectionKey,
    runtime: RuntimeState,
    force: Boolean,
): Boolean {
    if (force || last == null || last != next) return true
    return runtime is RuntimeState.Failed || runtime is RuntimeState.NotConfigured
}

/** 连接闸门决策：同一连接生命周期内 force 不得绕过互斥，只能取消旧尝试后重来。 */
enum class ConnectGate {
    /** 无在途连接，直接启动。 */
    Proceed,

    /** 有在途连接但非 force，跳过（幂等，不打断在途连接）。 */
    Skip,

    /** 有在途连接且 force：先取消旧尝试（其 CancellationException 会上抛并释放互斥），再串行启动新连接。 */
    CancelThenProceed,
}

/** 纯函数：据「是否有在途连接」+「是否 force」给出闸门决策，不碰任何协程状态。 */
fun connectGate(active: Boolean, force: Boolean): ConnectGate = when {
    !active -> ConnectGate.Proceed
    force -> ConnectGate.CancelThenProceed
    else -> ConnectGate.Skip
}

/**
 * B3：替换连接时，旧 transport 与旧 runtime 必须「await 停完」才能 start 新的。
 * 不能用 fire-and-forget（scope.launch），否则迟到的 stop() 会关掉刚 start 的 current。
 * 纯 suspend 编排，AppContainer.disconnectNow 复用它，测试直接验证顺序。
 */
suspend fun teardownConnection(
    disconnectTransport: suspend () -> Unit,
    stopRuntime: suspend () -> Unit,
) {
    disconnectTransport()
    stopRuntime()
}
