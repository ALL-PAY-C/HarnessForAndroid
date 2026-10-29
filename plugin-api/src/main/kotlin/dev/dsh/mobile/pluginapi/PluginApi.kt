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
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    val capabilities: Set<PluginCapability> = emptySet(),
    val enabledByDefault: Boolean = true,
)

enum class PluginCapability {
    Theme, Layout, BubbleStyle, QuickCommand, SettingsItem, BottomTab,
    SessionRow, AgentCard, ComposerButton, MessageRenderer,
}

interface DshPlugin {
    val manifest: PluginManifest

    /** Called once when the plugin is loaded. Register slots here. */
    fun register(registry: PluginRegistry)
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
    // 6e：对外列表保持 List<@Composable> 不变（消费方 AppNav/SessionsScreen/AgentsScreen 无感），
    // 用平行 owner 列表记住每个 extra/card 属于哪个插件，clearPlugin 才能按 owner 摘掉。
    private val sessionRowExtraOwners = mutableListOf<Pair<String, SessionRowExtra>>()
    private val agentCardExtraOwners = mutableListOf<Pair<String, AgentCardExtra>>()
    val settingsItems = mutableListOf<SettingsItem>()
    val bottomTabs = mutableListOf<BottomTab>()
    val composerButtons = mutableListOf<ComposerButton>()
    val quickCommands = mutableListOf<QuickCommand>()
    val drawerEntries = mutableListOf<DrawerEntry>()
    val configPages = mutableListOf<PluginConfigPage>()
    val themes = mutableListOf<ThemeContribution>()
    val contributions = mutableListOf<SlotContribution>()

    fun messageRenderer(id: String, pluginId: String, render: MessageRenderer) {
        messageRenderers[id] = render
    }

    fun sessionRowExtra(pluginId: String, extra: SessionRowExtra) {
        sessionRowExtras += extra
        sessionRowExtraOwners += pluginId to extra
    }

    fun agentCard(pluginId: String, card: AgentCardExtra) {
        agentCardExtras += card
        agentCardExtraOwners += pluginId to card
    }

    fun settingsItem(item: SettingsItem) {
        settingsItems += item
    }

    fun bottomTab(tab: BottomTab) {
        bottomTabs += tab
    }

    fun composerButton(button: ComposerButton) {
        composerButtons += button
    }

    fun quickCommand(command: QuickCommand) {
        quickCommands += command
    }

    fun configPage(page: PluginConfigPage) {
        configPages.removeAll { it.id == page.id }
        configPages += page
    }

    fun drawerEntry(entry: DrawerEntry) {
        drawerEntries.removeAll { it.id == entry.id }
        drawerEntries += entry
    }

    fun theme(theme: ThemeContribution) {
        themes += theme
    }

    /** Puts [c] into its slot, in-place replacing any earlier contribution with the same id+slot+pluginId. */
    fun contribute(c: SlotContribution) {
        // 6d：同 slot 同 id 按 owner 去重 —— 两个插件可各自贡献同名 id，互不覆盖；
        // 就地替换保留注册顺序：重新贡献 = 更新原位，不把条目挪到列表末尾。
        val idx = contributions.indexOfFirst { it.id == c.id && it.slot == c.slot && it.pluginId == c.pluginId }
        if (idx >= 0) contributions[idx] = c else contributions += c
    }

    /** Everything addressed to [slot], in registration order. */
    fun inSlot(slot: SlotId): List<SlotContribution> = contributions.filter { it.slot == slot }

    fun clearPlugin(pluginId: String) {
        settingsItems.removeAll { it.pluginId == pluginId }
        bottomTabs.removeAll { it.pluginId == pluginId }
        composerButtons.removeAll { it.pluginId == pluginId }
        quickCommands.removeAll { it.pluginId == pluginId }
        drawerEntries.removeAll { it.pluginId == pluginId }
        configPages.removeAll { it.pluginId == pluginId }
        contributions.removeAll { it.pluginId == pluginId }
        messageRenderers.keys.filter { it.startsWith("$pluginId.") }.forEach { messageRenderers.remove(it) }
        // 6e：主题、会话行扩展、Agent 卡按 owner 清理（以前漏掉，禁用/卸载后残留）。
        themes.removeAll { it.pluginId == pluginId }
        sessionRowExtras.removeAll(sessionRowExtraOwners.filter { it.first == pluginId }.map { it.second })
        sessionRowExtraOwners.removeAll { it.first == pluginId }
        agentCardExtras.removeAll(agentCardExtraOwners.filter { it.first == pluginId }.map { it.second })
        agentCardExtraOwners.removeAll { it.first == pluginId }
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

/** A page a packaged plugin declares; the host renders it from [fields]. */
data class ConfigPage(
    val id: String,
    val title: String,
    val description: String = "",
    val fields: List<ConfigField> = emptyList(),
)
