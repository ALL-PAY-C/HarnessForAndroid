package dev.dsh.mobile.pluginhost

import androidx.compose.material3.Text
import android.content.Context
import androidx.compose.runtime.Composable
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.pluginapi.BackgroundPlugin
import dev.dsh.mobile.pluginapi.BottomTab
import dev.dsh.mobile.pluginapi.ComposerButton
import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginCapability
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.QuickCommand
import dev.dsh.mobile.pluginapi.SettingsItem
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId
import dev.dsh.mobile.pluginapi.PluginUiNode
import dev.dsh.mobile.pluginapi.PluginUiSpec
import dev.dsh.mobile.pluginapi.UiBadgeTone
import dev.dsh.mobile.pluginapi.UiTextEmphasis
import dev.dsh.mobile.pluginapi.ThemeContribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoadedPlugin(
    val manifest: PluginManifest,
    val enabled: Boolean = true,
    /** 注册期错误（register() 抛出的异常）。 */
    val error: String? = null,
    /**
     * 运行期加载错误（Node 引擎 plugins.load 的真实回执）。
     *
     * 与 [error] 分开是因为两者来源不同：register() 成功不代表包能跑起来（缺依赖的 npm 包就是
     * 注册成功、引擎 import 时才炸）。插件管理列表只认这两个字段，绝不按「安装目录存在」猜状态。
     */
    val loadError: String? = null,
)

/**
 * 6b：后台插件是否该自动拉起。抽成纯函数便于单测。
 * 安全模式只放行内置（dsh. 前缀）；否则按 PluginStore 的启用态决定。
 */
internal fun shouldStartBackgroundPlugin(
    id: String,
    enabledByDefault: Boolean,
    safeMode: Boolean,
    isEnabled: (id: String, default: Boolean) -> Boolean,
): Boolean {
    val allowed = !safeMode || id.startsWith("dsh.")
    val enabled = isEnabled(id, enabledByDefault)
    return allowed && enabled
}

/**
 * Loads plugins with per-plugin isolation: a throwing register() disables only that
 * plugin, and rendering is wrapped in an error boundary so a bad composable cannot
 * take down the host screen.
 */
/**
 * 插件 config 的读写口：[SlotAction.SetConfig] 点击时宿主用它写值、重建插件实例。
 *
 * 放在 plugin-host 而不是 plugin-api：写的是宿主自己的存储，插件连地址都不该知道。
 * 留成接口而不是三个 lambda，是因为读、写、重载必须一起提供 —— 只给一半会让
 * 「点了开关界面不动」变成默认行为。
 */
interface PluginConfigBridge {
    /** 该插件当前全部 config（键 → 值）；没有就是空表。 */
    fun read(pluginId: String): Map<String, String>

    fun write(pluginId: String, key: String, value: String)

    /** 写完重建插件：JS 重新执行，槽位与界面读到新值。 */
    fun reload()
}

class PluginHost(
    private val store: PluginStore? = null,
    private val onUninstall: (String) -> Unit = {},
    /** 没接线时 [applySlotConfig] 一律返回 false，点击方据此给用户如实提示。 */
    private val configBridge: PluginConfigBridge? = null,
) {

    private val registry = PluginRegistry()
    private val instances = LinkedHashMap<String, DshPlugin>()

    private val _plugins = MutableStateFlow<List<LoadedPlugin>>(emptyList())
    val plugins: StateFlow<List<LoadedPlugin>> = _plugins.asStateFlow()

    /** 运行期加载失败回执：插件 id → 原因。来自引擎真实加载结果，不来自目录探测。 */
    private val runtimeFailures = LinkedHashMap<String, String>()

    /**
     * 槽位版本号。`registry` 里的列表是普通可变集合，Compose 看不见它的变化；
     * 每次注册/注销插件就自增一次，界面 collect 它即可在禁用插件后立刻把对应的
     * 配置页、按钮、菜单项撤下来。
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun bump() = _revision.update { it + 1 }

    val slots: PluginRegistry get() = registry

    /** True when repeated boot failures turned third-party plugins off. */
    val safeMode: Boolean get() = store?.safeMode == true

    fun clearSafeMode() {
        store?.clearSafeMode()
    }

    /** Call once the first screen is up: proves this boot did not crash. */
    fun markBootOk() {
        store?.markBootOk()
    }

    fun load(plugin: DshPlugin, force: Boolean = false) {
        val m = plugin.manifest
        if (instances.containsKey(m.id) && !force) return
        if (force && instances.containsKey(m.id)) {
            // 4c：强制重载（装完插件就地热加载 / 失败恢复）先清旧注册再重注册，避免 load 直接 return 吞掉重载。
            registry.clearPlugin(m.id)
            instances.remove(m.id)
        }
        instances[m.id] = plugin
        // 是否启用由 isEnabled 统一算（安全模式只放行内置，否则按 store 启用态）。
        val wanted = isEnabled(m.id, m.enabledByDefault)
        var error: String? = null
        if (wanted) {
            val failure = runCatching { plugin.register(registry) }.exceptionOrNull()
            if (failure != null) {
                registry.clearPlugin(m.id)
                runCatching { AppLog.w("plugin ${m.id} register failed: ${failure.message}") }
                // 6a：null/空 message 的异常不能判成成功；用类名兜底。
                error = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName ?: "插件注册失败"
            }
        }
        val row = LoadedPlugin(m, enabled = false, error = error)
        _plugins.update { list ->
            list.filterNot { it.manifest.id == m.id } + recompute(row, wanted, error)
        }
        bump()
    }

    /**
     * 插件当前是否「该被启用」：安全模式只放行内置（dsh. 前缀），否则按 [PluginStore] 的启用态。
     * Node 编排在真正执行 JS 前用它判断要不要 plugins.load（禁用插件不能跑 JS）。
     */
    fun isEnabled(id: String, enabledByDefault: Boolean): Boolean {
        val allowed = !safeMode || id.startsWith("dsh.")
        return allowed && (store?.isEnabled(id, enabledByDefault) ?: enabledByDefault)
    }

    /**
     * 仅当 [plugin] 仍是当前已装实例（未被卸载/替换）时才强制重注册。
     * Node 编排异步回写状态卡时用这个，避免卸载/重载后把旧实例复活回来。
     */
    fun refresh(plugin: DshPlugin) {
        if (instances[plugin.manifest.id] === plugin) load(plugin, force = true)
    }

    /**
     * 记录一次**真实**运行期加载结果（Node 引擎 plugins.load 的回执）。
     *
     * register() 成功只说明贡献注册完了，包能不能跑要等引擎 import 才见分晓；以前失败原因
     * 只进 logcat，列表里依旧显示「已启用」。这里把回执落到 [LoadedPlugin.loadError]，
     * 列表就能显示「加载失败：缺什么」。传 null 表示这次加载成功（清掉旧失败）。
     */
    fun noteLoadResult(id: String, failure: String?) {
        val message = failure?.takeIf { it.isNotBlank() }
        val previous = runtimeFailures[id]
        if (message == null) {
            runtimeFailures.remove(id)
        } else {
            runtimeFailures[id] = message
        }
        if (previous == message) return
        _plugins.update { list ->
            list.map { if (it.manifest.id == id) recompute(it, null, it.error) else it }
        }
        bump()
    }

    /**
     * 由启用意图 + 注册期错误 + 运行期回执算出这一行。
     *
     * [wanted] 为 null 表示「按 store 重算」（setEnabled / noteLoadResult 用）；三处入口共用
     * 同一个算法，就不会再出现「开关说启用、引擎说失败」这种自相矛盾的行。
     */
    private fun recompute(
        row: LoadedPlugin,
        wanted: Boolean?,
        registerError: String?,
    ): LoadedPlugin {
        val id = row.manifest.id
        val loadError = runtimeFailures[id]
        val on = wanted ?: isEnabled(id, row.manifest.enabledByDefault)
        return row.copy(
            enabled = on && registerError == null && loadError == null,
            error = registerError,
            loadError = loadError,
        )
    }

    fun loadAll(plugins: List<DshPlugin>, force: Boolean = false) = plugins.forEach { load(it, force) }

    /**
     * 拉起需要常驻本地服务的插件（[BackgroundPlugin]）。
     *
     * 网关没起来时调用方只会看到连接被拒，用户看不出要先去点启动；
     * 所以在插件加载完就自动拉一次，失败只记日志不影响其它插件。
     */
    fun startBackgroundPlugins(context: Context, scope: CoroutineScope) {
        instances.entries
            .filter { it.value is BackgroundPlugin }
            .filter { (id, plugin) ->
                shouldStartBackgroundPlugin(
                    id = id,
                    enabledByDefault = plugin.manifest.enabledByDefault,
                    safeMode = safeMode,
                    isEnabled = { pluginId, default -> store?.isEnabled(pluginId, default) ?: default },
                )
            }
            .forEach { (id, plugin) ->
                plugin as BackgroundPlugin
                scope.launch {
                    runCatching { plugin.ensureStarted(context) }
                        .onSuccess { result ->
                            result.exceptionOrNull()?.let { AppLog.w("插件 " + id + " 常驻服务未就绪：" + it.message) }
                        }
                        .onFailure { AppLog.w("插件 " + id + " 常驻服务启动异常：" + it.message) }
                }
            }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val plugin = instances[id] ?: return
        store?.setEnabled(id, enabled)
        if (enabled) {
            registry.clearPlugin(id)
            val err = runCatching { plugin.register(registry) }
                .onFailure { registry.clearPlugin(id) }
                .exceptionOrNull()
            _plugins.update { list ->
                list.map { if (it.manifest.id == id) recompute(it, null, err?.message) else it }
            }
            bump()
        } else {
            registry.clearPlugin(id)
            _plugins.update { list -> list.map { if (it.manifest.id == id) it.copy(enabled = false) else it } }
            bump()
        }
    }

    fun uninstall(id: String) {
        onUninstall(id)
        registry.clearPlugin(id)
        instances.remove(id)
        // 卸载后旧回执必须一起丢掉：同名重装时不能继承上一份的「加载失败」。
        runtimeFailures.remove(id)
        _plugins.update { list -> list.filterNot { it.manifest.id == id } }
        bump()
    }

    /** All slot consumers receive only components explicitly placed by the user. */
    private var placementStore: SlotPlacementStore? = null

    fun placements(context: Context): SlotPlacementStore = placementStore
        ?: SlotPlacementStore(context.applicationContext).also { placementStore = it }

    /**
     * 装了这个位置还能放什么：插件注册过、但还没被摆到任何位置的组件。
     * UI 编辑模式的「+」拉这一份，选中后走 [SlotPlacementStore.add]。
     */
    fun availableFor(context: android.content.Context, target: SlotId): List<SlotContribution> =
        placements(context).available(registry.contributions)

    fun visibleSlots(context: android.content.Context, slot: SlotId): List<SlotContribution> {
        val placements = placements(context)
        placements.revision
        return placements.visible(slot, registry.contributions)
    }

    /**
     * 应用一次「只切状态」的槽位点击：写插件自己的 config，再让宿主重建插件。
     *
     * 为什么要重载整个宿主而不是就地改一条：JS 插件的槽位内容是执行期算好的静态结果，
     * 只有重新执行一遍才读得到新 config。重载怎么做由 [PluginConfigBridge.reload] 决定，
     * 宿主不必知道插件装在哪。
     *
     * 返回是否真的改了（没接线 / 写失败 / 重载失败都是 false），调用方据此提示。
     */
    fun applySlotConfig(action: SlotAction.SetConfig): Boolean {
        val bridge = configBridge ?: return false
        val next = if (action.toggle) {
            if (bridge.read(action.pluginId)[action.key] == "true") "false" else "true"
        } else {
            action.value
        }
        return runCatching {
            bridge.write(action.pluginId, action.key, next)
            bridge.reload()
            true
        }.getOrElse {
            runCatching { AppLog.w("插件 config 写入失败 " + action.pluginId + "." + action.key + "：" + it.message) }
            false
        }
    }

    /** Render a message through the plugin renderer if one exists; null -> built-in. */
    @Composable
    fun MessageSlot(message: ChatMessage): Boolean {
        val renderer = message.rendererId?.let { registry.messageRenderers[it] } ?: return false
        PluginBoundary(pluginId = message.rendererId) { renderer(message) }
        return true
    }
}

/**
 * Slot wrapper. Compose forbids try/catch around composable invocations, so isolation
 * happens at registration time (PluginHost.load / setEnabled); render failures surface
 * through the process-wide handler. Kept as the single place for slot-level telemetry.
 */
@Composable
fun PluginBoundary(pluginId: String?, content: @Composable () -> Unit) {
    content()
}

/**
 * Built-in example plugin, proves the slot mechanism end to end:
 * a message renderer, a quick command, a composer button and a settings row.
 */
object SamplePlugin : DshPlugin {
    override val manifest = PluginManifest(
        id = "dsh.sample",
        name = "示例插件",
        version = "1.0.0",
        description = "演示消息渲染器 / 快捷指令 / 输入框按钮 / 设置项四个槽位",
        author = "DSH",
        capabilities = setOf(
            PluginCapability.MessageRenderer,
            PluginCapability.QuickCommand,
            PluginCapability.ComposerButton,
            PluginCapability.SettingsItem,
        ),
    )

    override fun register(registry: PluginRegistry) {
        registry.quickCommand(
            QuickCommand(
                id = "dsh.sample.summarize",
                label = "总结上下文",
                payload = "请用要点总结当前会话的关键信息，控制在 10 行内。",
                pluginId = manifest.id,
            ),
        )
        registry.composerButton(
            ComposerButton(
                id = "dsh.sample.expand",
                label = "润色",
                pluginId = manifest.id,
                transform = { text ->
                    if (text.isBlank()) text else "$text\n\n（请把上面的内容表达得更清晰、更简洁）"
                },
            ),
        )
        registry.settingsItem(
            SettingsItem(
                id = "dsh.sample.about",
                title = "示例插件已启用",
                subtitle = "消息渲染器、快捷指令、输入框按钮、设置项均已注册",
                pluginId = manifest.id,
                // 走转译层的 legacy 分支：插件直画也套上宿主 tokens（需求 7）。
                content = {
                    PluginUiHost(
                        spec = null,
                        tokens = rememberHostThemeTokens(),
                        onAction = {},
                        legacy = { Text("插件槽位工作正常") },
                    )
                },
            ),
        )
        registry.theme(
            ThemeContribution(
                id = "dsh.sample.theme",
                name = "示例 · 靛青",
                accent = 0xFF5B6CFF,
                bubbleMine = 0xFF5B6CFF,
                bubbleOther = 0xFFEFF0F6,
                pluginId = manifest.id,
            ),
        )
        // 声明式槽位（需求 7）：界面由节点树描述，宿主按当前主题 tokens 渲染。
        registry.contribute(
            SlotContribution(
                slot = SlotId.Settings,
                id = "dsh.sample.declarative",
                label = "示例插件 · 声明式卡片",
                subtitle = "插件不写 Compose，只描述节点树",
                pluginId = manifest.id,
                action = SlotAction.Prompt("示例插件：声明式 UI 已生效"),
                ui = PluginUiSpec(
                    root = PluginUiNode.Column(
                        gap = 8,
                        children = listOf(
                            PluginUiNode.Text("声明式 UI", emphasis = UiTextEmphasis.Title),
                            PluginUiNode.Text(
                                "这段界面由节点树描述：颜色只给语义角色，圆角/字号/间距全部来自当前主题。",
                            ),
                            PluginUiNode.Row(
                                gap = 8,
                                children = listOf(
                                    PluginUiNode.Badge("tokens 驱动", UiBadgeTone.Info),
                                    PluginUiNode.Badge("可单独禁用", UiBadgeTone.Neutral),
                                ),
                            ),
                            PluginUiNode.Button(
                                label = "发送示例指令",
                                action = SlotAction.Prompt("示例插件：声明式 UI 已生效"),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
