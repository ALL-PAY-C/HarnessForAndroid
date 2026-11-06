package dev.dsh.mobile.pluginhost

import android.content.Context
import androidx.compose.runtime.Composable
import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.pluginapi.BackgroundPlugin
import dev.dsh.mobile.pluginapi.BottomTab
import dev.dsh.mobile.pluginapi.Compat
import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginCompat
import dev.dsh.mobile.pluginapi.PluginDeclaration
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginModelSource
import dev.dsh.mobile.pluginapi.PluginPermission
import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
    /**
     * 安全模式挡住了这一行（用户想启用，但连续启动失败后第三方插件被暂停）。
     *
     * 与 [loadError] 分开：这不是插件自己的错，是宿主策略；界面据此给出唯一可行的动作
     * （退出安全模式），而不是把策略原因伪装成插件加载失败。
     */
    val blockedBySafeMode: Boolean = false,
    /**
     * 清单声明了权限、但用户还没全部授予（T9.25）。
     *
     * 与 [blockedBySafeMode] 并列而不是塞进 [error]：这不是插件坏了，是授权没给，
     * 界面据此显示第三态「需授权」并给出唯一可行的动作（授予），而不是「加载失败」。
     * 被它挡住时 register() 一次都不跑 —— 未授权的插件连贡献都不该出现。
     */
    val blockedByPermission: Boolean = false,
    /**
     * 没在跑的原因（T9.27）。null = 这一行在跑，或已在跑的过程中失败（看 [loadError]）。
     *
     * 与 [loadError] 互补而不是并列：[loadError] 是「引擎跑过、炸了」，本字段是「压根没跑」。
     * 界面据此把「未加载（原因）」与「加载失败（可展开看原文）」分成两种行，未加载行不标红、
     * 不禁用开关 —— 用户要能直接点开关把插件开起来。
     */
    val notLoadedReason: NotLoadedReason? = null,
    /**
     * 版本/能力协商结果：全量兼容、降级（[Compat.Degraded]，列出没生效的槽位/动作/超配额项）、
     * 拒载（[Compat.Rejected]，apiVersion 比宿主新）。插件管理页直接显示 [Compat.summary]。
     */
    val compat: Compat = Compat.Compatible,
)

/**
 * 装配期就注定跑不起来的插件自带失败原因。
 *
 * 典型是 PluginInstaller 装配 JS 包时抛错生成的 JsBrokenPlugin：失败发生在 PluginHost
 * 之前，没有引擎回执可调 [PluginHost.noteLoadResult]，于是原因只进 logcat、列表照旧显示
 * 「已停用」。宿主在 [PluginHost.load] 里把这个原因收进运行期回执，行内才看得见真相。
 */
interface PluginLoadFailure {
    val loadFailure: String
}

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

/**
 * 会话人设的读改写口：[SlotAction.SetPersona] 点击时宿主用它把「提示词注入」落到 session.persona。
 *
 * 与 [PluginConfigBridge] 分开：那份写插件自己的 config，这份写宿主的会话表 —— 插件连存储长什么样
 * 都不该知道。读写必须一起提供，只给写会让「开关取反」退化成「每次都写同一份」。
 */
interface SessionPersonaBridge {
    /** 此刻前台会话；没有打开的会话就是 null，点击方据此如实提示。 */
    fun sessionId(): String?

    /** 该会话现有 persona，用来判定开关方向；读不到返回空串。 */
    fun readPersona(sessionId: String): String

    /** 写入并返回是否成功；空串表示清除。 */
    fun writePersona(sessionId: String, text: String): Boolean
}

/**
 * 会话内槽位未摆放时的默认可见上限；返回 null 表示该槽位必须用户显式摆放。
 *
 * 只放开会话内的四类贴身槽位：抽屉 / 插件页 / 设置页仍然要用户自己摆，
 * 否则一个插件装上就改主界面结构，比「没反应」更烦人。
 * 会话内还有硬性布局上限（[COMPOSER_SLOT_CAP] / [HEADER_SLOT_CAP] 一类），这里给的是
 * 「宿主愿意默认显示几个」，两边取较小值。
 */
internal fun defaultSlotCap(slot: SlotId): Int? = when (slot) {
    SlotId.ComposerPlus -> 4
    SlotId.ChatOverflow -> 2
    SlotId.ChatBottom -> 2
    SlotId.ChatSettings -> 3
    else -> null
}

class PluginHost(
    private val store: PluginStore? = null,
    private val onUninstall: (String) -> Unit = {},
    /** 没接线时 [applySlotConfig] 一律返回 false，点击方据此给用户如实提示。 */
    private val configBridge: PluginConfigBridge? = null,
    /** 没接线时 [applySlotPersona] 一律返回 false（同上）。 */
    private val personaBridge: SessionPersonaBridge? = null,
    /** 插件注册/常驻服务失败的落盘回调（T9.12）；默认 null，老调用方不用改。 */
    private val onPluginCrash: ((String, String) -> Unit)? = null,
    /**
     * 进程级安全模式（T9.12 步骤3）：启动守卫判定崩溃循环后由 app 层注入。
     * 与 [PluginStore.safeMode]（插件自己数的连续失败）取或，两套来源共用同一条放行规则。
     */
    private val extraSafeMode: () -> Boolean = { false },
    /**
     * 权限授予存储（T9.25）。缺省回落到 [store]（PluginStore 自己实现了 PermissionGrants），
     * 所以 app 侧接线零改动；单测注入内存实现，不需要 Context。
     */
    grantsStore: PermissionGrants? = null,
) {

    private val grants: PermissionGrants? = grantsStore ?: store

    private val registry = PluginRegistry()
    private val instances = LinkedHashMap<String, DshPlugin>()

    private val _plugins = MutableStateFlow<List<LoadedPlugin>>(emptyList())
    val plugins: StateFlow<List<LoadedPlugin>> = _plugins.asStateFlow()

    /** 运行期加载失败回执：插件 id → 原因。来自引擎真实加载结果，不来自目录探测。 */
    private val runtimeFailures = LinkedHashMap<String, String>()

    /**
     * 宿主侧的「没跑」回执：插件 id → 原因。只由 [noteNotLoaded] 写，引擎给出真实结果
     * （[noteLoadResult]）时立刻清掉。与 [runtimeFailures] 同生同灭：一个插件不可能同时
     * 「没跑因为引擎没起来」和「跑了但失败」。
     */
    private val runtimeNotLoaded = LinkedHashMap<String, NotLoadedReason>()

    /** 版本拒载回执：插件 id → 原因。协商不过的插件不进注册表，register() 一次都不跑。 */
    private val versionRejections = LinkedHashMap<String, String>()

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
    val safeMode: Boolean get() = store?.safeMode == true || extraSafeMode()

    fun clearSafeMode() {
        store?.clearSafeMode()
        // 解除安全模式必须立刻重算每一行：挡住的原因来自 safeMode 本身，不重算的话
        // 行内会一直挂着「安全模式」的失败态，用户以为没生效。
        _plugins.update { list -> list.map { recompute(it, null, it.error) } }
        bump()
    }

    /** Call once the first screen is up: proves this boot did not crash. */
    fun markBootOk() {
        store?.markBootOk()
    }

    fun load(plugin: DshPlugin, force: Boolean = false) {
        val m = plugin.manifest
        if (instances.containsKey(m.id) && !force) return
        // 被换掉的那个实例：同一 id 换了实现（修好重装 / 占位插件被真插件顶掉）时，
        // 上一份运行期失败必须一起丢掉，否则「修好了还显示加载失败」。
        val previous = instances[m.id]
        if (force && instances.containsKey(m.id)) {
            // 4c：强制重载（装完插件就地热加载 / 失败恢复）先清旧注册再重注册，避免 load 直接 return 吞掉重载。
            registry.clearPlugin(m.id)
            instances.remove(m.id)
        }
        instances[m.id] = plugin
        when {
            // 装配期失败自带原因（JsBrokenPlugin 这类）：先落回执再算行，
            // 列表才不会把一个跑不起来的插件显示成「已停用」。
            plugin is PluginLoadFailure -> runtimeFailures[m.id] = plugin.loadFailure
            previous != null && previous !== plugin -> {
                runtimeFailures.remove(m.id)
                runtimeNotLoaded.remove(m.id)
            }
        }
        // 启用意图与安全模式放行分开算：注册受安全模式约束，但行内要说得清「是你想开、
        // 被安全模式挡住了」，所以回执按意图算，不用 isEnabled 的合取结果。
        val intent = store?.isEnabled(m.id, m.enabledByDefault) ?: m.enabledByDefault
        // 声明了权限但没全部授予：与安全模式一样「不 register」，但行内说的是「需授权」而不是
        // 「被挡住」—— 前者用户点一下就能解决，后者要退出安全模式。
        val wanted = safeModeAllows(m.id) && permissionAllows(m) && intent
        // 版本协商先于注册：清单声明的 apiVersion 比宿主新就拒载，register() 一次都不调，
        // 免得插件按新版契约写的槽位在旧宿主上跑出半个界面。
        val rejected = versionReject(m)
        var error: String? = rejected
        if (rejected == null && wanted) {
            val failure = runCatching { plugin.register(registry) }.exceptionOrNull()
            if (failure != null) {
                registry.clearPlugin(m.id)
                runCatching { AppLog.w("plugin ${m.id} register failed: ${failure.message}") }
                // 6a：null/空 message 的异常不能判成成功；用类名兜底。
                error = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName ?: "插件注册失败"
                // 插件失败是「进程没死但功能没了」，只有落盘才能在用户下次反馈时还原现场。
                runCatching { onPluginCrash?.invoke(m.id, error.orEmpty()) }
            }
        }
        val row = LoadedPlugin(m, enabled = false, error = error)
        _plugins.update { list ->
            list.filterNot { it.manifest.id == m.id } + recompute(row, intent, error)
        }
        bump()
    }

    /**
     * 插件当前是否「该被启用」：安全模式只放行内置（dsh. 前缀），否则按 [PluginStore] 的启用态。
     * Node 编排在真正执行 JS 前用它判断要不要 plugins.load（禁用插件不能跑 JS）。
     */
    /** 没有 store（纯宿主/单测场景）时开关落在这里，否则 setEnabled 写不进去、读回来还是默认值。 */
    private val enabledFallback = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun isEnabled(id: String, enabledByDefault: Boolean): Boolean =
        safeModeAllows(id) &&
            permissionAllows(instances[id]?.manifest) &&
            (store?.isEnabled(id, enabledByDefault) ?: enabledFallback[id] ?: enabledByDefault)

    /** 安全模式只放行内置（dsh. 前缀）。放行规则只有这一处，isEnabled 与行状态共用。 */
    private fun safeModeAllows(id: String): Boolean = !safeMode || id.startsWith("dsh.")

    // ------------------------------------------------------------ 权限（T9.25）

    /**
     * 权限放行：没声明 = 不需要授权（老插件走这条），声明了就要求逐项授予。
     *
     * 与 [safeModeAllows] 并列放在这里，[load] / [setEnabled] / [isEnabled] / [recompute] 共用
     * 同一个判定 —— 四处各写一遍必然有一天「开关说启用、贡献却不在」。
     */
    private fun permissionAllows(manifest: PluginManifest?): Boolean {
        if (manifest == null || manifest.permissions.isEmpty()) return true
        val stored = grants?.permissions(manifest.id) ?: return false
        return manifest.permissions.all { it.name in stored }
    }

    /** 该插件已授予的权限（只算清单里声明过的：删掉声明等于撤销，不用另写清理逻辑）。 */
    fun permissionGrants(id: String): Set<PluginPermission> {
        val declared = instances[id]?.manifest?.permissions ?: return emptySet()
        val stored = grants?.permissions(id) ?: return emptySet()
        return declared.filterTo(LinkedHashSet()) { it.name in stored }
    }

    /** 声明了但还没授予的权限；非空即「需授权」，宿主不 register。 */
    fun missingPermissions(id: String): Set<PluginPermission> {
        val declared = instances[id]?.manifest?.permissions ?: return emptySet()
        // 没有授予存储时不能提前返回空集：那等于把「声明了但没处记授权」说成「不缺权限」，
        // 行内会显示已启用，而 permissionAllows 实际拦着不注册。
        val stored = grants?.permissions(id).orEmpty()
        return declared.filterNotTo(LinkedHashSet()) { it.name in stored }
    }

    /**
     * 授予权限并立刻生效。
     *
     * 授予后必须重算并按需注册（与 [clearSafeMode] 同一路子）：只写存储不重算，行内会一直挂着
     * 「需授权」，用户点了「允许」却什么都没发生。
     */
    fun grantPermissions(id: String, permissions: Set<PluginPermission>) {
        if (permissions.isEmpty()) return
        val plugin = instances[id] ?: return
        val store = grants ?: return
        store.grant(id, (permissionGrants(id) + permissions).mapTo(HashSet()) { it.name })
        if (missingPermissions(id).isEmpty() && isEnabled(id, plugin.manifest.enabledByDefault)) {
            val err = registerNow(plugin)
            _plugins.update { list -> list.map { if (it.manifest.id == id) recompute(it, null, err) else it } }
        } else {
            _plugins.update { list -> list.map { if (it.manifest.id == id) recompute(it, null, it.error) else it } }
        }
        bump()
    }

    /** 撤销权限：贡献立刻下架，行回到「需授权」。 */
    fun revokePermissions(id: String, permissions: Set<PluginPermission>) {
        if (permissions.isEmpty()) return
        val store = grants ?: return
        store.grant(id, (permissionGrants(id) - permissions).mapTo(HashSet()) { it.name })
        registry.clearPlugin(id)
        _plugins.update { list -> list.map { if (it.manifest.id == id) recompute(it, null, it.error) else it } }
        bump()
    }

    /** 清旧注册后重跑 register()，返回注册期错误（null = 成功）。与 [setEnabled] 的启用分支同一条路。 */
    private fun registerNow(plugin: DshPlugin): String? {
        val id = plugin.manifest.id
        registry.clearPlugin(id)
        versionReject(plugin.manifest)?.let { return it }
        val failure = runCatching { plugin.register(registry) }.exceptionOrNull() ?: return null
        registry.clearPlugin(id)
        runCatching { AppLog.w("plugin " + id + " register failed: " + failure.message) }
        return failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName ?: "插件注册失败"
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
        // 引擎既然给出了真实结果，「没跑」的原因就过期了（成功也算：引擎这次起来了）。
        val stale = runtimeNotLoaded.remove(id) != null
        if (previous == message && !stale) return
        _plugins.update { list ->
            list.map { if (it.manifest.id == id) recompute(it, null, it.error) else it }
        }
        bump()
    }

    /**
     * 记录一次「宿主决定不加载」（T9.27）：引擎没起来这类不属于插件自身的阻塞。
     *
     * 与 [noteLoadResult] 分开：传进来的是原因枚举而不是错误原文，界面因此能把它渲染成
     * 「未加载 · 引擎不可用」这种可操作提示，而不是一条会被当成插件 bug 的失败原文。
     * 真跑起来之后 [noteLoadResult] 会把它清掉，不需要调用方再手动清。
     */
    fun noteNotLoaded(id: String, reason: NotLoadedReason?) {
        val previous = runtimeNotLoaded[id]
        if (reason == null) {
            runtimeNotLoaded.remove(id)
        } else {
            runtimeNotLoaded[id] = reason
        }
        if (previous == reason) return
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
        // 这里读「启用意图」而不是 isEnabled：安全模式挡住的插件必须能被标成
        // 「被安全模式挡住」，否则开关弹回去、状态写「已停用」，用户看不出发生了什么。
        val on = wanted ?: (store?.isEnabled(id, row.manifest.enabledByDefault) ?: enabledFallback[id] ?: row.manifest.enabledByDefault)
        val blocked = on && !safeModeAllows(id)
        val blockedByPermission = on && !permissionAllows(row.manifest)
        // 宿主侧「没跑」回执（引擎不可用等）也算阻塞：它没跑，开关就不能显示成「已启用」。
        val reported = runtimeNotLoaded[id]
        val running = on && registerError == null && loadError == null && !blocked &&
            !blockedByPermission && reported == null
        // 优先级按「用户最该做什么」排：开关关着 → 先开；安全模式 → 先退；缺权限 → 先授；
        // 引擎不可用 → 先修引擎。真失败（loadError）不占本字段，走 loadError 那条路径。
        val notLoaded = when {
            running || loadError != null || registerError != null -> null
            !on -> NotLoadedReason.Disabled
            blocked -> NotLoadedReason.SafeMode
            blockedByPermission -> NotLoadedReason.Permission
            reported != null -> reported
            else -> NotLoadedReason.NotStarted
        }
        return row.copy(
            enabled = running,
            error = registerError,
            loadError = loadError,
            blockedBySafeMode = blocked,
            blockedByPermission = blockedByPermission,
            notLoadedReason = notLoaded,
            compat = compatOf(id),
        )
    }

    /**
     * 清单版本协商；拒载时把原因记进 [versionRejections]，重装/改好后自动清掉。
     *
     * 只判版本（apiVersion / minHostApi）；槽位与动作的对账在注册期由 registry 记降级原因。
     */
    private fun versionReject(m: PluginManifest): String? {
        val compat = PluginCompat.negotiate(
            PluginDeclaration(apiVersion = m.apiVersion, minHostApi = m.minHostApi),
        )
        if (compat is Compat.Rejected) {
            versionRejections[m.id] = compat.reason
            return compat.reason
        }
        versionRejections.remove(m.id)
        return null
    }

    /** 插件当前的协商结果：版本拒载优先，否则看注册期记下的降级原因。 */
    fun compatOf(pluginId: String): Compat =
        versionRejections[pluginId]?.let { Compat.Rejected(it) } ?: registry.compat(pluginId)

    fun loadAll(plugins: List<DshPlugin>, force: Boolean = false) = plugins.forEach { load(it, force) }

    /**
     * 当前真正在跑的插件行。
     *
     * 模型源与提示词追加共用这一份过滤：被安全模式挡住、权限没授全、加载失败、开关关着
     * 的插件都不算 —— 否则会出现「插件停用了但模型列表里还留着它的 baseUrl」。
     * 读的时候现算，不缓存：开关一动下一次读就是新结果。
     */
    private fun activeRows(): List<LoadedPlugin> = plugins.value.filter {
        it.enabled && it.loadError == null && !it.blockedBySafeMode && !it.blockedByPermission
    }

    /**
     * 插件贡献的模型源，id 已加上 `plugin.<pluginId>.` 前缀。
     *
     * 前缀是必须的：两个插件各自声明 id="local" 是常态，裸 id 会在模型列表里撞车。
     * 同插件内重复的 source id 追加 `__2`/`__3` 后缀，先到先得、顺序稳定。
     *
     * baseUrl 覆盖：插件清单把「自定义地址」约定成 config 键 `baseUrl`（插件自己的设置项
     * 声明的同名键），用户在插件设置里填了就覆盖该插件声明的**全部** modelSources ——
     * 一个插件可以声明多个源，但用户填的是一个网关地址，不该只改到第一条。
     * 收益是插件换镜像/自建网关不用重打包。空值（或缺键）表示「用清单默认」，即不改动。
     */
    fun modelSources(): List<PluginModelSource> {
        val used = HashSet<String>()
        // 顺序按 manifest.id 定：plugins 里的行是「谁先 load 完谁先入列」，列表项会跟着加载
        // 完成顺序换位（真机 03:10:17 的两次 MODEL_SOURCES 里 local / stream 的位置就对调了）。
        return activeRows().sortedBy { it.manifest.id }.flatMap { row ->
            val override = pluginBaseUrl(row.manifest.id)
            row.manifest.modelSources.map { src ->
                src.copy(
                    id = uniqueSourceId("plugin." + row.manifest.id + "." + src.id, used),
                    baseUrl = override ?: src.baseUrl,
                )
            }
        }
    }

    /**
     * 插件配置里的 `baseUrl`，trim 后非空才算「用户填了」。
     *
     * 读配置失败按没填处理：一个坏掉的 config bridge（文件损坏 / 插件被删）只该让这一插件
     * 退回清单默认值，不能把整张模型列表连坐抛空。
     */
    private fun pluginBaseUrl(pluginId: String): String? =
        runCatching { configBridge?.read(pluginId)?.get("baseUrl")?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun uniqueSourceId(base: String, used: MutableSet<String>): String {
        var candidate = base
        var n = 2
        while (!used.add(candidate)) {
            candidate = base + "__" + n
            n++
        }
        return candidate
    }

    /**
     * 要追加进系统提示词的文本，按加载顺序、去重。
     *
     * 单插件内的一键开关（plugin.json config 里的 `prompt.enabled`）关掉时这一段消失，
     * 但插件本身照常加载 —— 用户只是不想让它改提示词，不是想停用它。
     */
    fun promptAdditions(): List<String> {
        val out = LinkedHashSet<String>()
        activeRows().forEach { row ->
            if (!promptEnabled(row.manifest.id)) return@forEach
            row.manifest.systemPrompt.trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out.toList()
    }

    /** `prompt.enabled` 缺省视为开；"false"/"0"/"off"/"no" 视为关。 */
    private fun promptEnabled(pluginId: String): Boolean {
        val raw = configBridge?.read(pluginId)?.get("prompt.enabled")?.trim()?.lowercase()
        return raw !in setOf("false", "0", "off", "no")
    }

    /**
     * 后台插件的守护协程：插件 id → 那个循环。
     *
     * 一个插件同时只允许一个守护（重装/重载会 cancel 旧的再起新的），
     * 否则端口抢不到时会有两个循环抢同一个服务，日志里全是重复失败。
     */
    private val backgroundGuards = LinkedHashMap<String, Job>()

    /**
     * 拉起需要常驻本地服务的插件（[BackgroundPlugin]），并给每个插件留一个守护协程（B1）。
     *
     * 网关没起来时调用方只会看到连接被拒，用户看不出要先去点启动；所以加载完就先拉一次。
     * 拉不起来不再是一次性的：守护协程按 1s→60s 退避重试，成功后退成周期探测，
     * 失败原因照旧记日志 + onPluginCrash，不影响其它插件。
     */
    fun startBackgroundPlugins(context: Context, scope: CoroutineScope) {
        instances.entries
            .filter { it.value is BackgroundPlugin }
            .forEach { (id, instance) ->
                val plugin = instance as BackgroundPlugin
                // 门控不过就连守护都不要起：禁用/安全模式挡住的插件重试只是刷日志。
                if (!backgroundGate(id, instance)) {
                    cancelBackgroundGuard(id)
                    return@forEach
                }
                guardBackgroundPlugin(id, plugin, instance, context, scope)
            }
    }

    /**
     * 后台插件该不该跑：[shouldStartBackgroundPlugin] 的宿主侧绑参版。
     *
     * 首拉与每次重试都过这一条 —— 用户在重试期间关掉插件，下一轮就直接退出。
     */
    private fun backgroundGate(id: String, plugin: DshPlugin): Boolean =
        shouldStartBackgroundPlugin(
            id = id,
            enabledByDefault = plugin.manifest.enabledByDefault,
            safeMode = safeMode,
            isEnabled = { pluginId, default -> store?.isEnabled(pluginId, default) ?: default },
        )

    private fun guardBackgroundPlugin(
        id: String,
        plugin: BackgroundPlugin,
        instance: DshPlugin,
        context: Context,
        scope: CoroutineScope,
    ) {
        backgroundGuards.remove(id)?.cancel()
        backgroundGuards[id] = scope.launch {
            BackgroundSupervisor(
                isAllowed = { backgroundGate(id, instance) },
                start = { plugin.ensureStarted(context) },
                onFailure = { error ->
                    AppLog.w("插件 " + id + " 常驻服务未就绪：" + error.message)
                    runCatching {
                        onPluginCrash?.invoke(id, error.message ?: (error::class.simpleName ?: "ensureStarted 异常"))
                    }
                },
            ).run()
        }
    }

    /**
     * 插件被禁用 / 卸载 / 从磁盘消失时叫停守护：不能让它继续给一个不存在的服务续命。
     *
     * 端口缓存一起清：下次启用要重新按存储判定，而不是拿着上一个实例的端口继续用。
     */
    private fun cancelBackgroundGuard(id: String) {
        backgroundGuards.remove(id)?.cancel()
        loopbackPorts.forget(id)
    }

    /**
     * 端口登记器（B1）：进程内缓存 + 落盘复用，读的是插件自己的 config 键 `loopback.port`。
     *
     * 放这里而不是每个插件自己存：宿主是唯一知道「这个插件上次用了哪个端口」的地方，
     * 而 config 读在 B2 之后是解密路径，必须有缓存兜住。
     */
    private val loopbackPorts = LoopbackPortRegistry(
        read = { id -> runCatching { configBridge?.read(id)?.get(LoopbackPorts.CONFIG_KEY) }.getOrNull() },
        write = { id, value -> runCatching { configBridge?.write(id, LoopbackPorts.CONFIG_KEY, value) } },
    )

    /**
     * 插件本地服务端口：**已分配即稳定**。
     *
     * 有记录（进程内缓存或 config 里的 `loopback.port`）就直接返回，绝不再拿 `isFree` 复核 ——
     * 复核必然失败，占用者就是插件自己的服务；以前每次周期探测都会顺延一格并改写 config，
     * 结果是「存储说 8318、服务在 8317」，用户填在模型源里的地址下次冷启动就失效。
     *
     * 只有没有记录时才真的分配一次并落盘。要换端口请走 [reallocateLoopbackPort]。
     */
    fun loopbackPort(pluginId: String, preferred: Int = LoopbackPorts.DEFAULT_PREFERRED): Int =
        loopbackPorts.port(pluginId, preferred)

    /**
     * 重分配端口：只在插件自己报「Address already in use」时调。
     *
     * 清掉旧记录 → 从 [preferred] 重新找一个空闲端口 → 写盘。调用方必须把返回的新端口
     * 落到自己的服务里，并同步模型源的 Base URL；返回 -1 表示 [LoopbackPorts.MAX_PROBES]
     * 个候选全被占用。
     */
    fun reallocateLoopbackPort(pluginId: String, preferred: Int = LoopbackPorts.DEFAULT_PREFERRED): Int =
        loopbackPorts.reallocate(pluginId, preferred)

    fun setEnabled(id: String, enabled: Boolean) {
        val plugin = instances[id] ?: run {
            // 实例不在（装配被跳过 / 已卸载）时写存储只会造出「存储说开、列表没这行」，
            // 用户点了开关却什么都没有 —— 至少留一条可查的日志。
            AppLog.w("插件 " + id + " 不在已加载实例里，启用请求被忽略")
            return
        }
        if (store != null) store.setEnabled(id, enabled) else enabledFallback[id] = enabled
        if (enabled) {
            // 安全模式挡着 / 权限没授全就不注册贡献：注册了会留下「开关关着却有槽位」的幽灵界面，
            // 只把意图写进存储并让这一行显示被挡的原因。
            if (!safeModeAllows(id) || !permissionAllows(plugin.manifest)) {
                _plugins.update { list -> list.map { if (it.manifest.id == id) recompute(it, null, it.error) else it } }
                bump()
                return
            }
            // 与 [grantPermissions] 共用同一条注册路径：两处各写一遍，迟早有一处漏掉版本协商。
            val err = registerNow(plugin)
            _plugins.update { list ->
                list.map { if (it.manifest.id == id) recompute(it, null, err) else it }
            }
            bump()
        } else {
            registry.clearPlugin(id)
            // B1：停用即停守护，否则退避循环会一直给一个用户已经关掉的服务重试。
            cancelBackgroundGuard(id)
            // 走同一条重算：停用后 blockedBySafeMode 必须一起清掉，否则「关掉的安全模式
            // 失败行」会跟着下一次启用又冒出来。
            _plugins.update { list -> list.map { if (it.manifest.id == id) recompute(it, null, it.error) else it } }
            bump()
        }
    }

    fun uninstall(id: String) {
        onUninstall(id)
        registry.clearPlugin(id)
        instances.remove(id)
        // B1：实例没了，守护协程不能再拿着旧实例继续重试。
        cancelBackgroundGuard(id)
        // 卸载后旧回执必须一起丢掉：同名重装时不能继承上一份的「加载失败」/「拒载」。
        runtimeFailures.remove(id)
        runtimeNotLoaded.remove(id)
        versionRejections.remove(id)
        _plugins.update { list -> list.filterNot { it.manifest.id == id } }
        bump()
    }

    /**
     * 重新扫描之后，磁盘上已经不存在的插件连行带实例一起摘掉。
     *
     * [load] 只会往上加/覆盖，从不删 —— 于是「包被删了，列表还挂着一条幽灵行」只能在这里收口。
     */
    fun keepOnly(ids: Collection<String>) {
        val keep = ids.toSet()
        val gone = instances.keys.filterNot { it in keep }
        if (gone.isEmpty()) return
        gone.forEach {
            registry.clearPlugin(it)
            instances.remove(it)
            cancelBackgroundGuard(it)
            runtimeFailures.remove(it)
            runtimeNotLoaded.remove(it)
            versionRejections.remove(it)
        }
        _plugins.update { list -> list.filter { it.manifest.id in keep } }
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
        val visible = placements.visible(slot, registry.contributions)
        // 用户在这个槽里显式摆过至少一项时，以摆放结果为准，不自作主张。
        if (visible.isNotEmpty()) return visible
        val cap = defaultVisibleCap(slot) ?: return visible
        val placed = placements.all().map { it.pluginId to it.contributionId }.toSet()
        // 会话内槽位默认展示插件贡献：装完就能点，不必先进「编辑槽位」手动摆放——
        // 只认摆放结果是「装上插件毫无反应」的头号原因（用户原话：很多插件装上后根本不起作用）。
        return registry.contributions
            .filter { it.slot == slot && (it.pluginId to it.id) !in placed }
            .sortedWith(compareBy({ it.pluginId }, { it.id }))
            .take(cap)
    }

    private fun defaultVisibleCap(slot: SlotId): Int? = defaultSlotCap(slot)

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

    /**
     * 应用一次「一键写会话人设」：toggle 时按当前 persona 取反，否则照写。
     *
     * 不重载插件：人设是宿主会话表里的东西，插件界面上的开关状态本来就不该由它推。
     * 返回是否真的改了 —— 没接线 / 没有前台会话 / 写失败 / 结果与原值相同 都是 false。
     */
    fun applySlotPersona(action: SlotAction.SetPersona): Boolean {
        val bridge = personaBridge ?: return false
        val sessionId = bridge.sessionId() ?: return false
        val current = bridge.readPersona(sessionId)
        val next = if (action.toggle) {
            if (current.isNotBlank()) "" else action.text
        } else {
            action.text
        }
        // 词包正文缺失时 toggle 会算出「写入空串」，与现状一样：如实返回 false，别报成功。
        if (next == current) return false
        return runCatching { bridge.writePersona(sessionId, next) }
            .onFailure { AppLog.w("插件写会话人设失败 " + sessionId + "：" + it.message) }
            .getOrDefault(false)
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
