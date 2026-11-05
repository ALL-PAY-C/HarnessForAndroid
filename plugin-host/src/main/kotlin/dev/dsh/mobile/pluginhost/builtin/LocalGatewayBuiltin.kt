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
import dev.dsh.mobile.pluginhost.builtin.gateway.ChatGateway
import dev.dsh.mobile.pluginhost.builtin.gateway.GatewayConfig
import dev.dsh.mobile.pluginhost.builtin.gateway.defaultBaseUrl

/**
 * 本机网关内置插件：把用户自己的上游 OpenAI 兼容地址直通到本机 127.0.0.1。
 *
 * 宿主只认清单与设置项；真正的转发在 [ChatGateway]。ensureStarted 每次被调到都先比对
 * 「端口 + 令牌 + 代理 + base + 模型 + 额外头」的签名：一致就直接返回（B1 守护协程每 60s 会来一次），
 * 不一致就停旧起新 —— 改设置后不用重启 App，下一个探测周期生效。
 *
 * 上游身份全部来自设置项，代码里只有一个不可达的示例 base。
 *
 * 端口：清单里的 baseUrl 在第一次被读到时由宿主注入的 portOf 决定（走
 * PluginHost.loopbackPort，带落盘复用与进程内缓存）。默认构造不探测端口，只回
 * LoopbackPorts.DEFAULT_PREFERRED —— 设置页与单测会直接读 manifest，不能在组合期 bind 端口。
 */
class LocalGatewayBuiltinPlugin(
    private val portOf: (String) -> Int = { LoopbackPorts.DEFAULT_PREFERRED },
) : DshPlugin, BackgroundPlugin {

    /** 端口只在第一次用到时分配，之后同一实例恒为同一值（PluginHost 侧还有落盘复用）。 */
    private val assignedPort: Int by lazy { portOf(ID) }

    /** 懒计算：PluginHost.load 会反复读 manifest，懒 + 缓存保证端口只分配一次。 */
    override val manifest: PluginManifest by lazy { manifestOf(assignedPort) }

    override fun register(registry: PluginRegistry) {
        // 骨架不注册任何槽位：插件页的行、开关、设置项全部来自清单与宿主自带页面。
        // 留这条日志是为了让「装配点接上了没有」在 logcat 里可查。
        AppLog.i("内置插件 " + manifest.id + " register（骨架）")
    }

    /**
     * 幂等启动。返回 Result.failure 是给 BackgroundSupervisor 看的：它按 1/2/4/8/16/32/60s
     * 退避重拉，所以这里不自己重试、也不吞异常。
     *
     * 令牌没填不算失败：网关照样起来，/v1/models 回 200 空列表 + hint，
     * /v1/chat/completions 回 400 让人去设置里填。这样模型源里能看到这一条，
     * 用户才知道缺什么。
     */
    override suspend fun ensureStarted(context: Context): Result<Unit> = runCatching {
        // 读不到配置（单测桩 Context 没有存储、或配置密文解不开）就当没填：网关照样起，
        // 由 /v1/models 的 hint 与 chat 的 400 告诉用户缺什么，而不是让守护协程一直重拉。
        val values = runCatching { InstalledPlugins(context).config(ID) }.getOrDefault(emptyMap())
        val config = GatewayConfig(
            access = values[GatewaySettings.KEY_TOKEN].orEmpty().trim(),
            proxy = values[GatewaySettings.KEY_PROXY]?.trim()?.takeIf { it.isNotEmpty() },
            baseUrl = values[GatewaySettings.KEY_BASE_URL]?.trim()?.takeIf { it.isNotEmpty() }
                ?: defaultBaseUrl,
            model = values[GatewaySettings.KEY_MODEL]?.trim()?.takeIf { it.isNotEmpty() },
            extraHeaders = GatewaySettings.headers(values[GatewaySettings.KEY_EXTRA_HEADERS]),
        )
        val port = assignedPort
        val signature = port.toString() + "|" + config.signature()

        val current = running
        if (current != null) {
            if (current.signature == signature && current.gateway.isRunning()) {
                AppLog.i("内置网关已在运行 port=" + port)
                return@runCatching
            }
            current.gateway.stop()
            running = null
        }

        val gateway = ChatGateway(config)
        if (!gateway.start(port)) throw IllegalStateException("回环端口 " + port + " 不可用")
        running = Running(gateway, signature)
        AppLog.i(
            "内置网关就绪 port=" + port +
                " 凭据=" + (if (config.access.isBlank()) "未填" else "已填") +
                " 代理=" + (config.proxy ?: "直连"),
        )
    }

    private class Running(val gateway: ChatGateway, val signature: String)

    companion object {
        /**
         * 进程级单例：PluginHost 重载插件时会重建实例，但网关进程只能有一个 ——
         * 实例字段会让第二次 ensureStarted 撞端口。
         */
        @Volatile
        private var running: Running? = null

        const val ID = "pl.gateway.local"
        const val NAME = "本机网关"
        const val VERSION = "2.0.0"

        /**
         * 首选端口：两个网关必须落在不同端口，否则谁也绑不上、
         * 模型源里还会出现两条一模一样的 baseUrl。
         */
        const val PREFERRED_PORT = 8318

        /** 设置项：上游 base / 令牌 / 模型 / 代理 / 额外头。 */
        fun settings() = GatewaySettings.fields(
            "把你的上游 OpenAI 兼容地址填进来，本机以 127.0.0.1 暴露；留空走示例地址（不可达）",
        )

        /** port 由宿主分配；0 表示还没分配，此时清单里仍拼成合法 URL 以便提前展示。 */
        fun manifestOf(port: Int): PluginManifest = PluginManifest(
            id = ID,
            name = NAME,
            version = VERSION,
            description = "把上游 OpenAI 兼容地址接成本机网关，手机独立可用。",
            author = "HFA",
            modelSources = listOf(
                PluginModelSource(
                    id = "local-gateway",
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
