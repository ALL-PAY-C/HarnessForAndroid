package dev.dsh.mobile.core.data.mcp

import dev.dsh.mobile.core.protocol.mcp.HttpMcpClient
import dev.dsh.mobile.core.protocol.mcp.McpClient
import dev.dsh.mobile.core.model.mcp.McpServerConfig
import dev.dsh.mobile.core.protocol.mcp.McpToolSource
import dev.dsh.mobile.core.protocol.tool.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * MCP 工具源的宿主装配（T9.10 P3）。
 *
 * 职责只有三件，网络与命名都在 [McpToolSource] 里：
 * 1. 把「设置 → MCP 服务器」的存储（[McpServerStore]）翻成协议配置；
 * 2. 配置变了就刷新工具表 —— 存储是 SharedPreferences，UI 直接自建 store 写入，没有回调，
 *    所以这里比对签名轮询（[pollIntervalMs]，默认 2.5s），改完设置下次用户说话前就生效；
 * 3. 首次预热放到后台（[warmDelayMs] 之后），不阻塞首轮请求；长时间不调用则断开回收。
 */
class McpRuntime(
    private val store: McpServerStore,
    private val scope: CoroutineScope,
    imageDir: () -> File? = { null },
    /** 建客户端的接缝：单测塞假客户端，生产用 HttpMcpClient。 */
    clientFactory: (McpServerConfig) -> McpClient = { HttpMcpClient(it) },
    private val pollIntervalMs: Long = 2_500L,
    private val idleTimeoutMs: Long = 300_000L,
    private val warmDelayMs: Long = 2_000L,
) {

    /** 工具表来源：直接交给 [dev.dsh.mobile.core.protocol.LocalEngineTransport]。 */
    val source: McpToolSource = McpToolSource(factory = clientFactory, imageDir = imageDir)

    /** 供宿主每轮取工具表（等价于 [source].snapshot()）。 */
    fun tools(): List<ToolSpec> = source.snapshot()

    private var job: Job? = null

    /** 上一次刷新用的配置签名：只有变了才重连，别看一次设置就重连一次。 */
    @Volatile
    private var lastSignature: String? = null

    /** 启动（幂等）。预热在后台，首轮请求拿到的是空表，等下一轮自然变全。 */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(warmDelayMs)
            refresh()
            while (isActive) {
                delay(pollIntervalMs)
                val configs = readConfigs() ?: continue
                if (signatureOf(configs) != lastSignature) refresh(configs)
                // 长时间没有工具调用就断开：MCP 服务器多半跑在电脑上，手机不该整夜挂着连接。
                source.recycleIdle(idleTimeoutMs)
            }
        }
    }

    /** 停止轮询并断开全部连接（transport 销毁时调）。 */
    suspend fun stop() {
        job?.cancel()
        job = null
        source.close()
    }

    private suspend fun refresh(configs: List<McpServerRecord>? = null) {
        val list = configs ?: readConfigs() ?: return
        lastSignature = signatureOf(list)
        source.refresh(list.map { it.toProtocolConfig() })
    }

    private fun readConfigs(): List<McpServerRecord>? =
        runCatching { store.load() }.getOrNull()

    private fun signatureOf(servers: List<McpServerRecord>): String =
        servers.joinToString("|") { record ->
            listOf(
                record.id,
                record.name,
                record.transport,
                record.url,
                record.enabled.toString(),
                record.toolCallTimeoutMs.toString(),
                record.headers.joinToString(",") { it.name + "=" + it.value },
            ).joinToString("~")
        }
}
