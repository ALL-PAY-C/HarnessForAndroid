package dev.dsh.mobile.pluginhost.builtin

import android.content.Context
import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.pluginapi.BackgroundPlugin
import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginModelSource
import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginhost.InstalledPlugins
import dev.dsh.mobile.pluginhost.LoopbackPorts
import dev.dsh.mobile.pluginhost.builtin.gateway.stream.StreamConfig
import dev.dsh.mobile.pluginhost.builtin.gateway.stream.StreamGateway
import dev.dsh.mobile.pluginhost.builtin.gateway.stream.UpstreamSpec

/**
 * 流式网关内置插件：把上游对话通道接成本机 OpenAI 兼容网关（SSE 帧逐条翻译）。
 *
 * 与 [LocalGatewayBuiltinPlugin] 同形：宿主只认清单与设置项，真正的转发在 [StreamGateway]。
 * ensureStarted 每次被调到都先比对「端口 + 令牌 + 代理 + base + 模型 + 额外头」的签名：
 * 一致就直接返回（B1 守护协程每 60s 来一次），不一致就停旧起新 —— 改设置后不用重启 App。
 *
 * 上游身份全部来自设置项，代码里只有一个不可达的示例 base。
 *
 * 端口：真实端口由宿主 PluginHost.loopbackPort 分配（两个网关不能抢同一个端口），
 * 分配结果写进清单的 baseUrl。
 */
class StreamGatewayBuiltinPlugin(
    private val portOf: (String) -> Int = { LoopbackPorts.DEFAULT_PREFERRED },
) : DshPlugin, BackgroundPlugin {

    /** 端口只在第一次用到时分配，之后同一实例恒为同一值（PluginHost 侧还有落盘复用）。 */
    private val assignedPort: Int by lazy { portOf(ID) }

    /** 懒计算：PluginHost.load 会反复读 manifest，懒 + 缓存保证端口只分配一次。 */
    override val manifest: PluginManifest by lazy { manifestOf(assignedPort) }

    override fun register(registry: PluginRegistry) {
        AppLog.i("内置插件 " + manifest.id + " register（骨架）")
    }

    /**
     * 幂等启动。返回 Result.failure 是给 BackgroundSupervisor 看的：它按 1/2/4/8/16/32/60s
     * 退避重拉，所以这里不自己重试、也不吞异常。
     *
     * 令牌没填不算失败：网关照样起来，/v1/models 回 200 空列表 + hint，
     * /v1/chat/completions 回 400 让人去设置里填。
     */
    override suspend fun ensureStarted(context: Context): Result<Unit> = runCatching {
        val values = runCatching { InstalledPlugins(context).config(ID) }.getOrDefault(emptyMap())
        val token = values[GatewaySettings.KEY_TOKEN].orEmpty().trim()
        val config = StreamConfig(
            token = token,
            proxy = values[GatewaySettings.KEY_PROXY]?.trim()?.takeIf { it.isNotEmpty() },
            baseUrl = values[GatewaySettings.KEY_BASE_URL]?.trim()?.takeIf { it.isNotEmpty() }
                ?: UpstreamSpec.EXAMPLE_UPSTREAM,
            model = values[GatewaySettings.KEY_MODEL]?.trim()?.takeIf { it.isNotEmpty() },
            extraHeaders = GatewaySettings.headers(values[GatewaySettings.KEY_EXTRA_HEADERS]),
            path = values[GatewaySettings.KEY_PATH].orEmpty().trim(),
            projectId = values[GatewaySettings.KEY_PROJECT_ID].orEmpty().trim(),
            userAgent = values[GatewaySettings.KEY_USER_AGENT].orEmpty().trim(),
        )
        val port = assignedPort
        val signature = port.toString() + "|" + config.signature()

        val current = running
        if (current != null) {
            if (current.signature == signature && current.gateway.isRunning()) {
                AppLog.i("流式网关已在运行 port=" + port)
                return@runCatching
            }
            current.gateway.stop()
            running = null
        }

        val gateway = StreamGateway(config)
        if (!gateway.start(port)) throw IllegalStateException("回环端口 " + port + " 不可用")
        running = Running(gateway, signature)
        AppLog.i(
            "流式网关就绪 port=" + port +
                " 凭据=" + (if (token.isBlank()) "未填" else "已填") +
                " 代理=" + (config.proxy ?: "直连"),
        )
    }

    private class Running(val gateway: StreamGateway, val signature: String)

    companion object {
        /**
         * 进程级单例：PluginHost 重载插件时会重建实例，但网关进程只能有一个 ——
         * 实例字段会让第二次 ensureStarted 撞端口。
         */
        @Volatile
        private var running: Running? = null

        const val ID = "pl.gateway.stream"
        const val NAME = "流式网关"
        const val VERSION = "2.0.0"

        /** 首选端口，与另一个网关错开。 */
        const val PREFERRED_PORT = LoopbackPorts.DEFAULT_PREFERRED

        fun settings() = GatewaySettings.streamFields(
            "把你的上游 OpenAI 兼容地址填进来，本机以 127.0.0.1 暴露；留空走示例地址（不可达）",
        )

        fun manifestOf(port: Int): PluginManifest = PluginManifest(
            id = ID,
            name = NAME,
            version = VERSION,
            description = "把上游对话通道接成本机 OpenAI 兼容网关，手机独立可用。",
            author = "HFA",
            modelSources = listOf(
                PluginModelSource(
                    id = "stream-gateway",
                    label = NAME,
                    baseUrl = "http://127.0.0.1:" + port + "/v1",
                    apiKeyHint = "内置网关本地 key",
                    protocol = "openai",
                ),
            ),
            settings = settings(),
        )
    }
}
