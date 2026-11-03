package dev.dsh.mobile.pluginapi

/**
 * 插件 API 契约的版本与协商规则。
 *
 * 一个版本号只表达一件事：**宿主能认出多少槽位、多少动作、多少个宿主函数**。
 * 清单不写 apiVersion 的插件按 1 处理，所以老插件零改动可加载；写了个比宿主新的
 * 版本就意味着它用到了本端没有的东西，此时按 [Compat.Rejected] 拒载而不是冒险跑。
 */

/** 本端实现的插件 API 版本。加槽位/动作/宿主函数时必须同步 +1 并更新 docs/PLUGINS.md。 */
const val PLUGIN_API_VERSION: Int = 1

/**
 * Rhino 宿主 ABI：脚本里 `dsh.ctx` 上这 12 个函数就是全部可调用面。
 *
 * 与 [PLUGIN_API_VERSION] 同一份契约 —— 增删任何一个都要升版本，否则插件会按
 * 「宿主有这个东西」写代码，装到旧宿主上就开始崩。
 */
val PLUGIN_HOST_FUNCTIONS: Set<String> = linkedSetOf(
    "log", "sessionId", "config", "setConfig", "setSessionPersona", "model",
    "registerContribution", "command", "composerButton", "renderer", "http", "prompt",
)

/**
 * 版本协商的三态结果。
 *
 * [Compatible] 全量加载；[Degraded] 能加载但清单里有本端认不出的项（槽位/动作/超配额），
 * 那些项被忽略并把原因列出来；[Rejected] 完全不能加载，宿主连 register() 都不该调。
 */
sealed interface Compat {
    /** 清单声明的每一项都落在本版能力内。 */
    data object Compatible : Compat

    /** 可加载，但 [reasons] 里逐条列出被忽略/被拒的项（未知槽位、未知动作、超配额）。 */
    data class Degraded(val reasons: List<String>) : Compat

    /** 拒载；[reason] 是给用户看的一句话。 */
    data class Rejected(val reason: String) : Compat

    /** 能否进注册表：Degraded 可以，Rejected 不行。 */
    val accepted: Boolean get() = this !is Rejected

    /** 插件管理页直接显示的一行摘要。 */
    fun summary(): String = when (this) {
        Compatible -> "与插件 API v" + PLUGIN_API_VERSION + " 完全兼容"
        is Degraded -> "部分能力未生效（" + reasons.size + "）：" + reasons.joinToString("；")
        is Rejected -> "拒绝加载：" + reason
    }
}

/**
 * 一个插件在清单里声明了什么。
 *
 * [slots] / [actions] 传原始 wire 名和原始 kind，不做过翻译 —— 认不出的才是要报给用户的，
 * 提前翻译掉就再也说不清「哪一项没生效」。
 */
data class PluginDeclaration(
    val apiVersion: Int? = null,
    val minHostApi: Int? = null,
    val slots: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
)

/** 协商器：纯函数，不做 I/O，便于单测与在 register() 之前调用。 */
object PluginCompat {

    /** 本端认得的槽位 wire 名。 */
    val KNOWN_SLOTS: Set<String> = SlotId.entries.map { it.wire }.toSet()

    /** 本端认得的动作 kind（含六个 SetConfig/SetPersona 同义写法）；空 kind 等同于 prompt。 */
    val KNOWN_ACTIONS: Set<String> = setOf(
        "prompt", "http", "link", "page", "custom",
        "setconfig", "set_config", "config", "toggle",
        "setpersona", "set_persona", "persona",
    )

    fun isKnownSlot(wire: String): Boolean = wire.trim() in KNOWN_SLOTS

    /** 认不出的 kind 会被忽略（不是降级成 prompt —— 那等于替插件乱发消息）。 */
    fun isKnownAction(kind: String): Boolean = kind.isBlank() || kind.trim().lowercase() in KNOWN_ACTIONS

    /**
     * 判定三态。
     *
     * 顺序是硬的：版本先判（拒载就是拒载，不再谈降级），然后逐项对账槽位与动作。
     * 未知项一律 [Compat.Degraded] + 忽略，绝不抛异常 —— 一个插件写错名字不能带走宿主。
     */
    fun negotiate(decl: PluginDeclaration): Compat {
        val api = decl.apiVersion ?: 1
        if (api > PLUGIN_API_VERSION) {
            return Compat.Rejected("插件声明 apiVersion=" + api + "，本端只支持到 v" + PLUGIN_API_VERSION)
        }
        val min = decl.minHostApi ?: 0
        if (min > PLUGIN_API_VERSION) {
            return Compat.Rejected("插件要求宿主 API ≥ v" + min + "，本端是 v" + PLUGIN_API_VERSION)
        }
        val reasons = buildList {
            decl.slots.filterNot { isKnownSlot(it) }.distinct().forEach { add("未知槽位 " + it + "，已忽略") }
            decl.actions.filterNot { isKnownAction(it) }.distinct().forEach { add("未知动作 " + it + "，已忽略") }
        }
        return if (reasons.isEmpty()) Compat.Compatible else Compat.Degraded(reasons)
    }
}

/**
 * 硬配额：同一个插件每一类只放行这么多条，超出的那一条被拒并回传可读原因。
 *
 * 数字是有意压小的 —— 插件把启动器塞满输入框和菜单正是这个开关要防的事；
 * 真正的界面容量由宿主决定，插件不能靠「多注册几个」抢占。
 */
enum class PluginQuota(val key: String, val max: Int) {
    /**
     * 输入框「+」面板里的条目。
     *
     * 上限 4 而不是参考实现里的 2：ctx.prompt() 也落在这个槽位，一个插件常见是
     * 1~2 条贡献 + 1~2 条提示词。卡在 2 会把「老插件照旧可用」这条兼容线直接掐断。
     */
    ComposerActions("composer.actions", 4),

    /** 输入框下方的常驻按钮（插件往工具栏塞的整块按钮，与「+」面板条目分开计数）。 */
    ComposerButtons("composer.buttons", 2),

    /** 消息动作（聊天页顶栏菜单 + 会话行菜单）。 */
    MessageActions("message.actions", 2),

    /** 输入框上方那条常驻横条。 */
    ComposerStrip("composer.strip", 1),

    /** 插件中心里的整页面板。 */
    Panels("panels", 4),

    /** 消息渲染器（一条消息正文交给插件画）。 */
    Media("media", 2),

    /** 小控件：快捷指令。 */
    Micro("micro", 2),

    /** 覆盖/浮动区：会话列表贴边区、会话设置整页。 */
    Overlays("overlays", 2),
    ;

    companion object {
        fun of(key: String): PluginQuota? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * 槽位吃哪一类配额；null = 不限额。
 *
 * 抽屉、设置行、Agent 预设由宿主的清单页承载，条数不会挤压别人的界面，所以不设上限。
 */
val SlotId.quota: PluginQuota?
    get() = when (this) {
        SlotId.ComposerPlus -> PluginQuota.ComposerActions
        SlotId.ChatOverflow, SlotId.SessionRow -> PluginQuota.MessageActions
        SlotId.ChatBottom -> PluginQuota.ComposerStrip
        SlotId.PluginPage -> PluginQuota.Panels
        SlotId.SessionList, SlotId.ChatSettings -> PluginQuota.Overlays
        SlotId.Drawer, SlotId.Settings, SlotId.AgentPreset -> null
    }

/** 一次被拒的注册：界面照抄 [reason] 就能说清「哪一条没生效、为什么」。 */
data class RegistrationRejection(
    val pluginId: String,
    /** 槽位 wire 名或配额 key，便于按类聚合。 */
    val key: String,
    val reason: String,
)
