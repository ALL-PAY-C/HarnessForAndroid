package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.core.common.AppLog
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
class NodeBackedPlugin(
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
        /**
         * Node 插件编排：先按启用态筛出该真执行的插件，再确保引擎起来，逐个 `plugins.load`。
         * 每个插件的成败只落到自己的 [status]，不吞掉其它插件的加载；
         * 成功/失败都 refresh 一次，插件页卡片才显示最终状态。
         *
         * [isEnabled] 默认走 [PluginHost.isEnabled]（安全模式 + store 启用态）；测试可注入恒 false 验证禁用不执行 JS。
         */
        suspend fun loadNodePlugins(
            host: PluginHost,
            engine: NodeEngine,
            plugins: List<NodeBackedPlugin>,
            isEnabled: (NodeBackedPlugin) -> Boolean = { p -> host.isEnabled(p.spec.id, p.manifest.enabledByDefault) },
        ) {
            val runnable = plugins.filter(isEnabled)
            plugins.filterNot { it in runnable }.forEach { p ->
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
                    // 引擎起不来也是真实的运行期失败：列表行要显示它，别继续挂「已启用」。
                    host.noteLoadResult(p.spec.id, msg)
                    host.refresh(p)
                }
                return
            }
            runnable.forEach { p ->
                try {
                    val res = engine.call("plugins.load", JSONObject().put("dir", p.dir.absolutePath))
                    p.status.value = NodePluginStatus.Applied(
                        name = res.optString("name").ifBlank { p.spec.name },
                        version = res.optString("version").ifBlank { p.spec.version },
                        appliedAt = System.currentTimeMillis(),
                    )
                    host.noteLoadResult(p.spec.id, null)
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
    }
}
