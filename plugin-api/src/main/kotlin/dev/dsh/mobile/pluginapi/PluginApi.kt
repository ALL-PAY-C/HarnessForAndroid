package dev.dsh.mobile.pluginapi

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.dsh.mobile.core.model.AgentProfile
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.Session

/**
 * plugin-api: zero dependency on :app and on any feature module.
 * A plugin declares capabilities and registers native slots; nothing is injected
 * as HTML/JS (no WebView in this app), so rendering is always native and typed.
 */
/**
 * 插件声明的模型源：一个可被宿主当成「模型供应商」注册的 baseUrl + 默认模型。
 *
 * [id] 与 [baseUrl] 是硬要求 —— 缺任一项宿主直接跳过该条（见 PluginHost.modelSources）。
 * [apiKeyHint] 只是给用户看的提示（"填 xxx 的 key"），不是密钥本身：插件包是明文 zip，
 * 里面放不了也不该放真实凭据，真正填 key 的动作在设置页由用户完成。
 */
data class PluginModelSource(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String = "",
    val apiKeyHint: String = "",
    val protocol: String = "openai",
)

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    val capabilities: Set<PluginCapability> = emptySet(),
    val enabledByDefault: Boolean = true,
    /** 清单声明的插件 API 版本；缺省 = 宿主当前版本，老插件零改动可加载。 */
    val apiVersion: Int = PLUGIN_API_VERSION,
    /** 插件要求的最低宿主 API 版本；缺省 0 = 不要求。高过 [PLUGIN_API_VERSION] 即拒载。 */
    val minHostApi: Int = 0,
    /**
     * 清单声明的权限：空集 = 不需要授权。
     *
     * 与 [capabilities] 正交 —— 那是「往哪个渲染插槽里塞东西」，这是「能对设备/宿主做什么」。
     * 声明了却没全部授予的插件宿主不 register，列表显示「需授权」；老插件不写这一项，
     * 空集走同一条加载路径，行为逐字不变。
     */
    val permissions: Set<PluginPermission> = emptySet(),
    /**
     * 清单声明的模型源；空列表 = 不贡献模型，老插件零改动。
     *
     * 与 [capabilities] / [permissions] 三者正交：这是「往宿主的模型供应商列表里加一项」。
     */
    val modelSources: List<PluginModelSource> = emptyList(),
    /**
     * 追加到系统提示词末尾的一段文本；空串 = 不追加。
     *
     * 宿主在拼 system 时把已启用插件的这一段按顺序接上去，插件被停用即消失。
     */
    val systemPrompt: String = "",
    /**
     * 原生（编译进 APK）插件声明的设置项；声明式插件用 plugin.json 的 config[]，这里留空。
     *
     * 宿主把它们折成设置行渲染（与 plugin.json 的 config 走同一套渲染通道），值仍然写回
     * 插件自己的 config —— 不新增第二份存储。空列表 = 这个插件没有设置项，老插件零改动。
     */
    val settings: List<ConfigField> = emptyList(),
)

/**
 * 插件权限：宿主在 register 之前就要判的那几类能力。
 *
 * 只列真会越界的三类 + 一类文件读写；新增一项必须同时加 design 资源与显示映射，
 * 否则列表里的「需授权」只会说一个用户看不懂的英文单词。
 */
enum class PluginPermission {
    /** 执行命令（shell）。 */
    Shell,

    /** 无障碍操控手机（点击/滑动/读屏）。 */
    PhoneControl,

    /** Shizuku / Root 特权通道。 */
    Privileged,

    /** 读写工作区文件。 */
    Workspace,
    ;

    companion object {
        /**
         * plugin.json 里的写法 → 枚举；认不出返回 null（调用方忽略该条）。
         *
         * 大小写、下划线、连字符都不敏感：清单是人写的，"phone_control" 和 "PhoneControl"
         * 指的是同一项。认不出就丢，不抛 —— 新宿主给老插件多写一项不该让整包加载失败。
         */
        fun ofName(raw: String): PluginPermission? {
            val key = raw.filter { it.isLetterOrDigit() }.lowercase()
            return entries.firstOrNull { it.name.lowercase() == key }
        }
    }
}

enum class PluginCapability {
    Theme, Layout, BubbleStyle, QuickCommand, SettingsItem, BottomTab,
    SessionRow, AgentCard, ComposerButton, MessageRenderer,
}

interface DshPlugin {
    val manifest: PluginManifest

    /** Called once when the plugin is loaded. Register slots here. */
    fun register(registry: PluginRegistry)
}

/**
 * 贡献模型源的插件（声明式插件由 manifest 直接满足，无需实现本接口）。
 *
 * 宿主读的是 [PluginManifest.modelSources]，本接口只是给原生插件一个自述的名字。
 */
interface ModelSourceContributor {
    val modelSources: List<PluginModelSource>
}

/**
 * 往系统提示词里追加一段文本的插件（声明式插件由 manifest 直接满足）。
 *
 * 宿主读的是 [PluginManifest.systemPrompt]，本接口只是给原生插件一个自述的名字。
 */
interface PromptAdder {
    val systemPromptAddition: String
}

// ------------------------------------------------------------------- slots

typealias MessageRenderer = @Composable (ChatMessage) -> Unit
typealias SessionRowExtra = @Composable (Session) -> Unit
typealias AgentCardExtra = @Composable (AgentProfile) -> Unit
typealias SettingsItemContent = @Composable () -> Unit

data class SettingsItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val pluginId: String = "",
    val content: SettingsItemContent,
)

data class BottomTab(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val pluginId: String = "",
    val content: @Composable () -> Unit,
)

/**
 * Entry contributed to the left drawer. [content] is the full screen the entry
 * opens, so a plugin ships its own management UI without touching :app.
 */
data class DrawerEntry(
    val id: String,
    val label: String,
    val subtitle: String = "",
    val icon: ImageVector? = null,
    val pluginId: String = "",
    val content: @Composable () -> Unit,
)

/**
 * A plugin's own configuration screen, listed in the plugin hub.
 *
 * The hub is the single entry point ("插件" tab): it renders one row per page
 * and opens [content] in place, so a plugin never has to claim a bottom tab of
 * its own just to expose settings.
 */
data class PluginConfigPage(
    val id: String,
    val label: String,
    val subtitle: String = "",
    val icon: ImageVector? = null,
    val pluginId: String = "",
    val content: @Composable () -> Unit,
)

data class ComposerButton(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val pluginId: String = "",
    /** Receives current composer text, returns the new text. */
    val transform: (String) -> String,
)

data class QuickCommand(
    val id: String,
    val label: String,
    val payload: String,
    val pluginId: String = "",
)

data class ThemeContribution(
    val id: String,
    val name: String,
    val accent: Long,
    val bubbleMine: Long,
    val bubbleOther: Long,
    val pluginId: String = "",
)

/** Everything a plugin can contribute. Owned by :plugin-host, read by features. */
class PluginRegistry {

    val messageRenderers = LinkedHashMap<String, MessageRenderer>()
    val sessionRowExtras = mutableListOf<SessionRowExtra>()
    val agentCardExtras = mutableListOf<AgentCardExtra>()
    val settingsItems = mutableListOf<SettingsItem>()
    val bottomTabs = mutableListOf<BottomTab>()
    val composerButtons = mutableListOf<ComposerButton>()
    val quickCommands = mutableListOf<QuickCommand>()
    val drawerEntries = mutableListOf<DrawerEntry>()
    val configPages = mutableListOf<PluginConfigPage>()
    val themes = mutableListOf<ThemeContribution>()
    val contributions = mutableListOf<SlotContribution>()

    /**
     * 统一撤销清单：注册即记录「怎么撤」，[clearPlugin] 只跑它。
     *
     * 以前是 12 行手写 removeAll，加一个槽位就得回来补一行（漏一次就是禁用插件后残留）。
     * 现在撤销动作跟着注册动作一起写，新增槽位只改注册函数。
     */
    private val undoLog = LinkedHashMap<String, MutableList<() -> Unit>>()

    /** 配额占用：pluginId → 配额 → 已占用的注册 key。 */
    private val quotaUse = LinkedHashMap<String, MutableMap<PluginQuota, LinkedHashSet<String>>>()

    /** 每一次被拒的注册（超配额 / 未知槽位 / 未知动作）。界面照抄 [RegistrationRejection.reason]。 */
    val rejections = mutableListOf<RegistrationRejection>()

    /** 降级原因：pluginId → 原因。三态协商 [compat] 读的就是它。 */
    private val degradeNotes = LinkedHashMap<String, MutableList<String>>()

    /** 注册序号，给没有 id 的槽位（会话行扩展）当配额 key，保证每条注册各占名额。 */
    private var seq = 0

    /** 记一条撤销动作；同一个插件注册多少次就记多少条，撤销时逆序跑。 */
    private fun record(pluginId: String, undo: () -> Unit) {
        undoLog.getOrPut(pluginId) { mutableListOf() } += undo
    }

    /**
     * 占一个配额名额。
     *
     * 返回 null = 放行；否则是可直接显示的拒收原因，同时记进 [rejections] 与降级原因。
     * pluginId 为空的贡献来自进程内内置插件（宿主自己的代码），不受配额约束 —— 内置插件
     * 不是这个开关要防的对象，共用同一个空 owner 也会让它们互相挤掉。
     */
    fun claim(pluginId: String, quota: PluginQuota, key: String): String? {
        if (pluginId.isBlank()) return null
        val keys = quotaUse.getOrPut(pluginId) { mutableMapOf() }.getOrPut(quota) { linkedSetOf() }
        if (key in keys) return null
        if (keys.size >= quota.max) {
            val reason = "超过 " + quota.key + " 配额（上限 " + quota.max + " 条），已拒绝：" + key
            reject(pluginId, quota.key, reason)
            return reason
        }
        keys += key
        return null
    }

    /** 该插件在某类配额上已用掉的名额。 */
    fun quotaUsed(pluginId: String, quota: PluginQuota): Int =
        quotaUse[pluginId]?.get(quota)?.size ?: 0

    /** 记一条拒收：既进 [rejections] 也进降级原因，两处都能读到原因。 */
    fun reject(pluginId: String, key: String, reason: String) {
        rejections += RegistrationRejection(pluginId, key, reason)
        degrade(pluginId, reason)
    }

    /** 记一条降级原因（未知槽位 / 未知动作 / 超配额）。同一句不重复记。 */
    fun degrade(pluginId: String, reason: String) {
        val notes = degradeNotes.getOrPut(pluginId) { mutableListOf() }
        if (reason !in notes) notes += reason
    }

    fun degradedReasons(pluginId: String): List<String> = degradeNotes[pluginId]?.toList().orEmpty()

    /** 注册完之后的协商结果：没有任何降级原因就是 [Compat.Compatible]。 */
    fun compat(pluginId: String): Compat {
        val reasons = degradedReasons(pluginId)
        return if (reasons.isEmpty()) Compat.Compatible else Compat.Degraded(reasons)
    }

    fun messageRenderer(id: String, pluginId: String, render: MessageRenderer): String? {
        claim(pluginId, PluginQuota.Media, "renderer:" + id)?.let { return it }
        messageRenderers[id] = render
        record(pluginId) { messageRenderers.remove(id) }
        return null
    }

    fun sessionRowExtra(pluginId: String, extra: SessionRowExtra): String? {
        claim(pluginId, PluginQuota.MessageActions, "session.row:" + (++seq))?.let { return it }
        sessionRowExtras += extra
        record(pluginId) { sessionRowExtras.removeAll { it === extra } }
        return null
    }

    fun agentCard(pluginId: String, card: AgentCardExtra) {
        agentCardExtras += card
        record(pluginId) { agentCardExtras.removeAll { it === card } }
    }

    fun settingsItem(item: SettingsItem) {
        settingsItems += item
        record(item.pluginId) { settingsItems.removeAll { it === item } }
    }

    fun bottomTab(tab: BottomTab) {
        bottomTabs += tab
        record(tab.pluginId) { bottomTabs.removeAll { it === tab } }
    }

    fun composerButton(button: ComposerButton): String? {
        claim(button.pluginId, PluginQuota.ComposerButtons, "composer.button:" + button.id)?.let { return it }
        composerButtons += button
        record(button.pluginId) { composerButtons.removeAll { it === button } }
        return null
    }

    fun quickCommand(command: QuickCommand): String? {
        claim(command.pluginId, PluginQuota.Micro, "quick:" + command.id)?.let { return it }
        quickCommands += command
        record(command.pluginId) { quickCommands.removeAll { it === command } }
        return null
    }

    fun configPage(page: PluginConfigPage): String? {
        claim(page.pluginId, PluginQuota.Panels, "page:" + page.id)?.let { return it }
        configPages.removeAll { it.id == page.id }
        configPages += page
        record(page.pluginId) { configPages.removeAll { it.id == page.id } }
        return null
    }

    fun drawerEntry(entry: DrawerEntry) {
        drawerEntries.removeAll { it.id == entry.id }
        drawerEntries += entry
        record(entry.pluginId) { drawerEntries.removeAll { it.id == entry.id } }
    }

    fun theme(theme: ThemeContribution) {
        themes += theme
        record(theme.pluginId) { themes.removeAll { it === theme } }
    }

    /**
     * Puts [c] into its slot, in-place replacing any earlier contribution with the same id+slot+pluginId.
     *
     * 超配额时**不注册**并返回可读原因（[rejections] / [compat] 里也能看到），
     * 调用方据此显示「这一条没生效」而不是静默丢掉。
     */
    fun contribute(c: SlotContribution): String? {
        c.slot.quota?.let { quota ->
            claim(c.pluginId, quota, c.slot.wire + ":" + c.id)?.let { return it }
        }
        // 6d：同 slot 同 id 按 owner 去重 —— 两个插件可各自贡献同名 id，互不覆盖；
        // 就地替换保留注册顺序：重新贡献 = 更新原位，不把条目挪到列表末尾。
        val idx = contributions.indexOfFirst { it.id == c.id && it.slot == c.slot && it.pluginId == c.pluginId }
        if (idx >= 0) contributions[idx] = c else contributions += c
        // 撤销按三元组摘，不管中途替换过几次：列表里当前那一条一定被摘掉。
        record(c.pluginId) {
            contributions.removeAll { it.id == c.id && it.slot == c.slot && it.pluginId == c.pluginId }
        }
        return null
    }

    /** Everything addressed to [slot], in registration order. */
    fun inSlot(slot: SlotId): List<SlotContribution> = contributions.filter { it.slot == slot }

    /**
     * 摘掉一个插件的全部注册。
     *
     * 只跑 [undoLog]：注册过的每一项都记着自己的撤销动作，所以这里不存在「漏写一行」的槽位。
     */
    fun clearPlugin(pluginId: String) {
        undoLog.remove(pluginId)?.asReversed()?.forEach { runCatching(it) }
        quotaUse.remove(pluginId)
        // 降级原因跟着注册一起清：重新装载的同一插件应当从 Compatible 重新协商。
        degradeNotes.remove(pluginId)
    }
}

// ------------------------------------------------------- declarative slots

/**
 * The places a plugin may put something, addressed by a stable string.
 *
 * A plugin never imports a host screen: it names a slot, and the host decides
 * where that slot is rendered. Adding a new integration point means adding a
 * case here and one call site in the host — plugins keep working untouched.
 */
enum class SlotId(val wire: String) {
    /** Entries inside the composer's "+" sheet. */
    ComposerPlus("chat.composer.plus"),

    /** Entries inside the chat screen's overflow (⋯) menu. */
    ChatOverflow("chat.topbar.menu"),

    /** Entries inside a session row's overflow menu. */
    SessionRow("session.row.menu"),

    /** Entries in the left drawer, above the plugin hub. */
    Drawer("app.drawer"),

    /** Rows in Settings. */
    Settings("app.settings"),

    /** Pages listed in the plugin hub. */
    PluginPage("plugin.page"),

    /** 会话「配置智能体」里的预设人设：插件装好即出现在预设列表。 */
    AgentPreset("agent.preset"),

    /** 聊天页输入框上方（快捷指令 / 提示词模板那一带）。 */
    ChatBottom("chat.bottom"),

    /** 会话设置整页的空白区。 */
    ChatSettings("chat.settings"),

    /** 会话列表页贴边的浮动区。 */
    SessionList("session.list"),
    ;

    companion object {
        fun of(wire: String): SlotId? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What a declarative contribution does when tapped.
 *
 * Deliberately small: every kind maps onto something the host can already do,
 * so a package that ships only JSON can still act (send a prompt, hit an
 * endpoint, open a screen) without loading foreign code.
 */
sealed interface SlotAction {
    /** Sends [text] to the active session as a user message. */
    data class Prompt(val text: String, val submit: Boolean = true) : SlotAction

    /** Fires an HTTP request; the response body is shown as a toast. */
    data class Http(val method: String, val url: String, val body: String? = null) : SlotAction

    /** Opens [uri] with the system handler. */
    data class Link(val uri: String) : SlotAction

    /** Opens one of the plugin's own declared pages. */
    data class Page(val pageId: String) : SlotAction

    /** A code plugin can render/do anything; used by in-APK plugins only. */
    data class Custom(val id: String) : SlotAction

    /**
     * 只改插件自己的 config，不发消息：点一下 = 写配置 + 重载插件界面。
     *
     * [toggle] 为真时把 [key] 现有的 "true"/"false" 取反，[value] 被忽略（会话级一键开关
     * 用这一种）；插件不声明 [pluginId] 之外的权限，宿主只写它自己那一条 config。
     */
    data class SetConfig(
        val pluginId: String,
        val key: String,
        val value: String = "",
        val toggle: Boolean = false,
    ) : SlotAction

    /**
     * 一键写会话人设：点一下就把当前会话的 session.persona 写成 [text]，不发消息、也不再要一次确认。
     *
     * [toggle] 为真时按「当前 persona 有没有内容」取反 —— 有就清空、空就写入 [text]，
     * 于是同一枚开关来回都能用，插件不必自己记状态；[text] 为空串表示只做清除。
     */
    data class SetPersona(val text: String, val toggle: Boolean = false) : SlotAction
}

/**
 * One thing a plugin puts in one slot.
 *
 * [content] lets an in-APK plugin draw a real Compose row; a packaged plugin
 * leaves it null and gets a generated row built from [label]/[subtitle]/[icon].
 */
data class SlotContribution(
    val slot: SlotId,
    val id: String,
    val label: String,
    val subtitle: String = "",
    val icon: String? = null,
    val pluginId: String = "",
    val action: SlotAction? = null,
    val content: (@Composable () -> Unit)? = null,
    /**
     * 声明式 UI（需求 7）。非空时宿主用 PluginUiHost 按主题 tokens 翻译节点树，
     * [content] 被忽略；为空则继续走 [content] 直画，旧插件零改动。
     */
    val ui: PluginUiSpec? = null,
)

/** Field kinds a packaged plugin may declare for its generated settings form. */
enum class ConfigFieldType { Text, Secret, Switch, Number, Select }

data class ConfigField(
    val key: String,
    val label: String,
    val type: ConfigFieldType = ConfigFieldType.Text,
    val default: String = "",
    val hint: String = "",
    val options: List<String> = emptyList(),
)
