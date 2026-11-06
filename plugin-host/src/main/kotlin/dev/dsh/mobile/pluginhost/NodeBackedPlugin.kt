package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.PluginUiNode
import dev.dsh.mobile.pluginapi.PluginUiSpec
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId
import dev.dsh.mobile.pluginapi.UiBadgeTone
import dev.dsh.mobile.pluginapi.UiTextEmphasis
import dev.dsh.mobile.runtimehost.NodeEngine
import dev.dsh.mobile.runtimehost.NodeEngineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Node 插件在宿主侧的可观察加载状态。
 *
 * 与 [JsBackedPlugin] 不同：这类插件（node:/@deepseek-ai/ 依赖）的 JS 在 NodeEngineHost
 * （libnode）里跑，本端只负责把包目录交给引擎 `plugins.load`，并把结果落成一张状态卡。
 */
sealed interface NodePluginStatus {
    data object Pending : NodePluginStatus
    data object Starting : NodePluginStatus
    data class Applied(val name: String, val version: String, val appliedAt: Long) : NodePluginStatus
    data class Failed(val stage: String, val message: String, val retryable: Boolean = true) : NodePluginStatus
}

/**
 * 需要 Node 宿主（node:/@deepseek-ai/ 依赖）的插件。
 *
 * 状态存 [status]（MutableStateFlow，可观察）；register 贡献一张插件页状态卡。
 * 加载编排见 [loadNodePlugins]：成功/失败都 re-register 一次，让卡片刷新成最终状态，
 * 这样用户在插件中心点开它时看到的是「已应用」或「[stage] 原因」，而不是永远「等待加载」。
 */
open class NodeBackedPlugin(
    val spec: PluginManifestFile,
    val dir: File,
    val status: MutableStateFlow<NodePluginStatus> = MutableStateFlow(NodePluginStatus.Pending),
) : DshPlugin {

    override val manifest: PluginManifest = PluginManifest(
        id = spec.id,
        name = spec.name,
        version = spec.version,
        description = spec.description,
        author = spec.author,
        capabilities = emptySet(),
        enabledByDefault = true,
        permissions = spec.declaredPermissions(),
        modelSources = spec.modelSources,
        systemPrompt = spec.systemPrompt,
    )

    /** 可观察状态：UI 或日志侧 collect 它即可拿到加载进度 / 失败阶段。 */
    val state: StateFlow<NodePluginStatus> = status.asStateFlow()

    override fun register(registry: PluginRegistry) {
        registry.contribute(
            SlotContribution(
                slot = SlotId.PluginPage,
                id = spec.id + ".page",
                label = spec.name,
                subtitle = spec.description.ifBlank { "Node 插件" },
                pluginId = spec.id,
                action = SlotAction.Page(spec.id),
                ui = statusCard(),
            ),
        )
    }

    private fun statusCard(): PluginUiSpec = PluginUiSpec(
        root = PluginUiNode.Column(
            gap = 10,
            children = buildList {
                add(PluginUiNode.Text(spec.name, emphasis = UiTextEmphasis.Title))
                if (spec.description.isNotBlank()) add(PluginUiNode.Text(spec.description))
                add(
                    PluginUiNode.Row(
                        gap = 8,
                        children = buildList {
                            add(PluginUiNode.Badge("v" + spec.version, UiBadgeTone.Neutral))
                            add(PluginUiNode.Badge("Node 宿主", UiBadgeTone.Info))
                        },
                    ),
                )
                add(
                    PluginUiNode.Section(
                        title = "加载状态",
                        children = listOf(PluginUiNode.Text(statusText())),
                    ),
                )
            },
        ),
    )

    private fun statusText(): String = when (val s = status.value) {
        NodePluginStatus.Pending -> "等待加载"
        NodePluginStatus.Starting -> "正在 Node 引擎加载…"
        is NodePluginStatus.Applied -> "已应用：" + s.name + " " + s.version
        is NodePluginStatus.Failed -> "[" + s.stage + "] " + s.message
    }

    companion object {
        /** 插件目录里自带的 WebUI 文件名：存在就说明这个插件带了自己的页面。 */
        const val UI_PAGE_FILE: String = "ui.html"

        /** transport 阶段的判据：与引擎回传的 error.stage 对齐（引擎刚起、本端还没连上）。 */
        const val STAGE_TRANSPORT: String = "transport"

        /** 「引擎没连上」的消息原文：老引擎包不回 stage 时靠它兜底判据。 */
        const val ENGINE_NOT_CONNECTED: String = "引擎未连接"

        /** transport 重试上限：真机冷启动一次就够，留一次余量给引擎刚 accept 的抖动。 */
        private const val TRANSPORT_RETRIES: Int = 2

        /** 两次重试的固定间隔；不退避 —— 竞态是「连接还没挂上」，等太久用户就在看失败卡片了。 */
        private const val TRANSPORT_RETRY_DELAY_MS: Long = 250L

        /**
         * Node 插件编排：先按启用态筛出该真执行的插件，再确保引擎起来，逐个 `plugins.load`。
         * 每个插件的成败只落到自己的 [status]，不吞掉其它插件的加载；
         * 成功/失败都 refresh 一次，插件页卡片才显示最终状态。
         *
         * [isEnabled] 默认走 [PluginHost.isEnabled]（安全模式 + store 启用态）；测试可注入恒 false 验证禁用不执行 JS。
         *
         * [onGatewayUrl] 装配侧注入的端口回写口：带 WebUI 的插件加载成功后，宿主按 [gatewayRpcPath]
         * 派生的路径向引擎问一次 status，把拿到的 url 交回去（宿主写进该插件配置的 baseUrl）。
         * 默认 null = 不回写；rpc 失败只记日志，不影响这次加载的「已应用」态。
         */
        suspend fun loadNodePlugins(
            host: PluginHost,
            engine: NodeEngine,
            plugins: List<NodeBackedPlugin>,
            isEnabled: (NodeBackedPlugin) -> Boolean = { p -> host.isEnabled(p.spec.id, p.manifest.enabledByDefault) },
            onGatewayUrl: ((pluginId: String, url: String) -> Unit)? = null,
        ) {
            // 装配期已判失败的占位（目录已丢失）不送引擎：送也只会拿回一个「目录不存在」的
            // 二次错误，而真正的原因（哪个路径没了）已经在占位实例里，由 PluginHost.load 落行。
            val candidates = plugins.filterNot { it is PluginLoadFailure }
            val runnable = candidates.filter(isEnabled)
            candidates.filterNot { it in runnable }.forEach { p ->
                // 策略挡着（用户禁用 / 安全模式 / 权限没授全）不写宿主回执：Host 在 recompute 里
                // 由启用意图自己推出 NotLoadedReason（Disabled / SafeMode / Permission），比这里
                // 只看到 isEnabled=false 的盲猜更准。卡片仍给一张 Failed(stage=disabled)，
                // 让人在插件自己的设置页也能看见「没跑」，但不污染插件管理行的失败态（T9.27）。
                p.status.value = NodePluginStatus.Failed(stage = "disabled", message = "插件已禁用或处于安全模式", retryable = false)
            }
            if (runnable.isEmpty()) return

            runnable.forEach { it.status.value = NodePluginStatus.Starting }
            // 冷启动必须把插件目录一起交给引擎：NodeRuntime 一进程只起一次，先到的调用决定 --plugin 列表。
            // 以前这里传空表，引擎就带着 plugins=0 起来，随包/已装的 Node 插件要等后面的 plugins.load 才补上，
            // 中途调用 /llm 之类的路由就会看到「没有 provider」。plugins.load 对同一目录是幂等的，重复无害。
            val started = engine.ensureStarted(runnable.map { it.dir })
            if (started.isFailure) {
                val msg = started.exceptionOrNull()?.message ?: "Node 引擎启动失败"
                runnable.forEach { p ->
                    p.status.value = NodePluginStatus.Failed(stage = "engine", message = msg)
                    // 引擎起不来不是插件的错，别记成 loadError（那会让行显示「加载失败」+ 把
                    // 开关灰掉，用户无从下手）。记成「未加载 · 引擎不可用」：提示可操作，
                    // 开关照旧可点 —— 引擎修好后一次重载/重试就会把它清掉（T9.27）。
                    host.noteNotLoaded(p.spec.id, NotLoadedReason.EngineUnavailable)
                    host.refresh(p)
                }
                return
            }
            runnable.forEach { p ->
                try {
                    val res = loadWithTransportRetry(engine, p)
                    p.status.value = NodePluginStatus.Applied(
                        name = res.optString("name").ifBlank { p.spec.name },
                        version = res.optString("version").ifBlank { p.spec.version },
                        appliedAt = System.currentTimeMillis(),
                    )
                    host.noteLoadResult(p.spec.id, null)
                    // 自带 WebUI 的插件在 node 侧起 webServer：加载成功就把端口问回来交给装配侧，
                    // 用户下次打开它的页面（或模型源里的地址）不必自己填。只在有 ui.html 且 id
                    // 落在网关注册命名内时探一次，不给每个插件都加一次 rpc。
                    if (onGatewayUrl != null && File(p.dir, UI_PAGE_FILE).isFile) {
                        gatewayRpcPath(p.spec.id)?.let { path -> reportGatewayUrl(p, engine, path, onGatewayUrl) }
                    }
                } catch (e: CancellationException) {
                    throw e // 取消必须上抛，不能被当成插件加载失败吞掉。
                } catch (t: Throwable) {
                    val stage = (t as? NodeEngineException)?.stage ?: "load"
                    val detail = (t as? NodeEngineException)?.detail
                    // 引擎把 stack / dir / entries 放在 error 里回传；条目摘要并进 message，
                    // 卡片上的失败原因就能直接看出「包里到底有什么」，而 logcat 里拿到完整堆栈。
                    val entries = detail?.optJSONArray("entries")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                    }.orEmpty()
                    val dir = detail?.optString("dir").orEmpty().ifBlank { p.dir.absolutePath }
                    val message = buildString {
                        append(t.message ?: t.toString())
                        if (entries.isNotEmpty()) append("（包内根层: ").append(entries.joinToString(" ")).append("）")
                    }
                    p.status.value = NodePluginStatus.Failed(stage = stage, message = message)
                    // 把引擎回执接到插件管理列表：界面显示的失败原因必须来自这次真实加载。
                    host.noteLoadResult(p.spec.id, "[" + stage + "] " + message)
                    AppLog.w("Node 插件 " + p.spec.id + " 加载失败：[" + stage + "] " + message)
                    AppLog.w("Node 插件 " + p.spec.id + " 目录 " + dir + " 条目 " + entries.joinToString(" "))
                    detail?.optString("stack")?.takeIf { it.isNotBlank() }?.let {
                        AppLog.w("Node 插件 " + p.spec.id + " 引擎堆栈:\n" + it)
                    }
                    // 引擎没回条目（老引擎包 / 传输失败）时自己看一眼目录，别让卡片只剩一行 message。
                    if (entries.isEmpty()) {
                        val local = runCatching {
                            p.dir.listFiles()?.joinToString(" ") { f -> f.name + if (f.isDirectory) "/" else "" }
                        }.getOrNull().orEmpty()
                        AppLog.w("Node 插件 " + p.spec.id + " 本地目录 " + dir + " 条目 " + (local.ifBlank { "<空或不可读>" }))
                    }
                }
                host.refresh(p)
            }
        }

        /**
         * 是不是「引擎没连上」这类可重试失败。
         *
         * 只有 transport 阶段 / 「引擎未连接」算：load 阶段的清单解析错误、包损坏、目录不存在
         * 重试只会把同一个错重演一遍，还让用户在插件页多等半秒才看到真正原因。
         */
        internal fun isTransportFailure(t: Throwable): Boolean =
            (t as? NodeEngineException)?.stage == STAGE_TRANSPORT ||
                t.message.orEmpty().contains(ENGINE_NOT_CONNECTED)

        /**
         * `plugins.load` 的有界 transport 重试（任务 66 / 竞态 A）。
         *
         * 真机 2026-11-06 03:09:33 的现场：node 侧「已加载插件 pl.gateway.local」+ ENGINE_PLUGIN_LOADED
         * 都已成功，宿主同一时刻却抛 [transport] 引擎未连接 —— 引擎刚起、本端的 reader/writer 还没挂上，
         * 第一次 call 抢在连接建立之前。重试一次就拿到结果，用户不必先看「加载失败」再点重试。
         *
         * 有界：最多 [TRANSPORT_RETRIES] 次、固定 [TRANSPORT_RETRY_DELAY_MS] 间隔、不退避；
         * 非 transport 异常原样抛出（交给调用方落 Failed），取消照样上抛。
         */
        private suspend fun loadWithTransportRetry(
            engine: NodeEngine,
            plugin: NodeBackedPlugin,
        ): JSONObject {
            val params = JSONObject().put("dir", plugin.dir.absolutePath)
            var attempt = 0
            while (true) {
                try {
                    val res = engine.call("plugins.load", params)
                    if (attempt > 0) {
                        AppLog.i("Node 插件 " + plugin.spec.id + " plugins.load retried after transport，第 " + attempt + " 次重试成功")
                    }
                    return res
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (attempt >= TRANSPORT_RETRIES || !isTransportFailure(t)) throw t
                    attempt++
                    AppLog.w("Node 插件 " + plugin.spec.id + " plugins.load 命中 transport 失败，第 " + attempt + " 次重试：" + t.message)
                    delay(TRANSPORT_RETRY_DELAY_MS)
                }
            }
        }

        /**
         * 插件 id → 引擎 rpc 路径：`pl.gateway.local` → `/gateway.local`，`pl.gateway.stream` → `/gateway.stream`。
         *
         * 两个网关插件各自注册一条独立路径，写死一条会串到另一个插件身上；不在这套命名下的
         * 插件没有网关注册，返回 null 让调用点直接跳过。
         */
        internal fun gatewayRpcPath(pluginId: String): String? {
            val bare = pluginId.removePrefix("pl.")
            return if (bare == pluginId || bare.isEmpty()) null else "/" + bare
        }

        /**
         * 问 node 侧 webServer 当前地址并交给 [sink]。
         *
         * 取不到 / 回空 / 回调自己抛，都只记日志：端口回写是便利，不是加载条件 ——
         * 插件已经跑起来了，不能因为「地址没问着」把它标成失败。
         */
        private suspend fun reportGatewayUrl(
            plugin: NodeBackedPlugin,
            engine: NodeEngine,
            path: String,
            sink: (String, String) -> Unit,
        ) {
            val url = try {
                engine.rpc(path, "status").optString("url").trim()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w("Node 插件 " + plugin.spec.id + " 取 " + path + " 地址失败：" + t.message)
                return
            }
            if (url.isEmpty()) {
                AppLog.w("Node 插件 " + plugin.spec.id + " " + path + " 未返回 url，跳过端口回写")
                return
            }
            try {
                sink(plugin.spec.id, url)
            } catch (t: Throwable) {
                AppLog.w("Node 插件 " + plugin.spec.id + " 端口回写失败：" + t.message)
            }
        }
    }
}

/**
 * 目录已丢失的 Node 插件占位（T9.22）。
 *
 * 清单还在、包目录没了：没有实例可 load，以前的行按启用意图显示「已启用」，等于把
 * 「插件没了」伪装成「它好好的」。占位实例不执行任何东西，只实现 [PluginLoadFailure]，
 * 让 [PluginHost.load] 把「哪个路径没了」写进列表行；[NodeBackedPlugin.loadNodePlugins]
 * 会跳过它，不拿一个不存在的目录去问引擎。
 */
class NodeMissingDirPlugin(
    spec: PluginManifestFile,
    dir: File,
) : NodeBackedPlugin(
    spec = spec,
    dir = dir,
    status = MutableStateFlow<NodePluginStatus>(
        NodePluginStatus.Failed(
            stage = BrokenPlugin.STAGE_MISSING,
            message = "插件目录已不存在：" + dir.absolutePath,
            retryable = false,
        ),
    ),
), PluginLoadFailure {

    override val loadFailure: String
        get() = "[" + BrokenPlugin.STAGE_MISSING + "] 插件目录已不存在：" + this.dir.absolutePath
}

/**
 * 一次 `plugins.load` 批次的端口回写收口（任务 66 / 竞态 B）。
 *
 * 以前每回写一个插件的端口就全量 `syncPluginModelSources` 一次，两个网关插件各触发一次，
 * 中间那一版模型列表是「一个旧端口 + 一个新端口」（真机 03:10:17：local=36191, stream=42117，
 * 约 0.8 s）。批次版：逐条只落配置（[record]），批次结束 [close] 时统一重注册一次。
 *
 * [close] 只在真写过端口时才 flush：没有插件带 WebUI 的批次不该多打一条 MODEL_SOURCES。
 */
class GatewayUrlBatch(
    private val record: (pluginId: String, url: String) -> Unit,
    private val flush: () -> Unit,
) {
    private var touched = false

    /** 收下一条回写：只落配置，本轮不触达模型源注册。 */
    fun accept(pluginId: String, url: String) {
        record(pluginId, url)
        touched = true
    }

    /** 批次结束：本次写过端口才全量重注册一次。可重复调用，第二次起是 no-op。 */
    fun close() {
        if (!touched) return
        touched = false
        flush()
    }
}
