package dev.dsh.mobile.feature.plugins

/**
 * 提示词注入开关的确认闸。
 *
 * 不认插件 id：任何插件只要在设置项里声明了 key = [PROMPT_ENABLED_KEY] 的开关，就归这道闸管。
 * 开关默认开（插件自己的 default=true），但「由关变开」这一步要先让用户看过一次说明：
 * 确认态按插件 id 记在该插件自己的 config 里（[PROMPT_CONFIRMED_KEY]，逗号分隔），
 * 把开关关掉就把自己的 id 摘掉，下次再开重新弹。这里只有纯逻辑，对话框在 PluginSettingsScreen 里画。
 */

/** 开关落点：plugin.json config[] 里那条 switch。 */
const val PROMPT_ENABLED_KEY = "prompt.enabled"

/** 确认态落点：一行逗号分隔的插件 id，走宿主既有 config 写通道（B2 之后是加密路径），不另开 prefs。 */
const val PROMPT_CONFIRMED_KEY = "config.prompt.confirmed"

/**
 * 旧版确认态的后缀：早期按插件名把确认态写成一个专用键。
 * 迁移只按后缀扫，不认具体名字 —— 旧数据读得走，代码里也不必留任何插件名。
 */
private const val LEGACY_CONFIRMED_SUFFIX = ".confirmed"

/** 一次开关点击要落盘的键值。空列表 = 一个字都不写（取消走这条）。 */
sealed interface PromptToggleEffect {
    data class Write(val entries: List<Pair<String, String>>) : PromptToggleEffect

    /** 由关变开、这台设备还没确认过：不写任何东西，先弹确认。 */
    data object AskConfirm : PromptToggleEffect
}

/** 读到的确认态 + 读到旧写法时要顺手落盘的迁移写入。 */
data class PromptConfirmation(
    val ids: Set<String>,
    /** 迁移写入：新键写 id、旧键写空串（空串即「没有」，与 switchOn 的语义一致）。 */
    val migrate: List<Pair<String, String>> = emptyList(),
)

/** 逗号分隔 → 集合。空串、多余逗号、空白都吃掉。 */
fun confirmedIds(raw: String?): Set<String> =
    raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/** 集合 → 逗号分隔。排序后落盘，重复迁移不会改字节。 */
fun encodeConfirmedIds(ids: Set<String>): String = ids.sorted().joinToString(",")

/**
 * 读确认态，顺便算迁移。三态都走这里：
 * - 新键有值：以新键为准；旧键若还留着 true，顺手清掉（只清，不改集合）。
 * - 新键没有、旧键是 true：认作「这个插件已确认」，新键写本插件 id，旧键清空。
 * - 两者都没有：未确认，不写盘。
 *
 * 幂等：迁完新键有值、旧键是空串，再读一次得到的 migrate 为空。
 */
fun readConfirmation(pluginId: String, values: Map<String, String>): PromptConfirmation {
    val current = confirmedIds(values[PROMPT_CONFIRMED_KEY])
    val legacyKeys = values.keys.filter {
        it != PROMPT_CONFIRMED_KEY && it.endsWith(LEGACY_CONFIRMED_SUFFIX) && switchOn(values[it].orEmpty())
    }
    val ids = if (current.isEmpty() && legacyKeys.isNotEmpty()) setOf(pluginId) else current
    val writes = buildList {
        if (ids != current) add(PROMPT_CONFIRMED_KEY to encodeConfirmedIds(ids))
        legacyKeys.forEach { add(it to "") }
    }
    return PromptConfirmation(ids, writes)
}

/** 只拦 key = 注入开关的那一条；哪个插件都拦，不看 id。 */
fun isPromptToggle(key: String): Boolean = key == PROMPT_ENABLED_KEY

/**
 * 开关点击 → 要写的键值。
 *
 * - 不是注入开关：返回 null，调用方按原逻辑直写。
 * - 关：先落 enabled=false，再把自己的 id 从确认集合里摘掉（安全阀：下次再开要重新弹）。
 * - 开且已确认：直接开，顺带把 id 写回集合（幂等）。
 * - 开且未确认：什么都不写，弹确认。
 */
fun promptToggleEffect(
    pluginId: String,
    key: String,
    next: Boolean,
    confirmedIds: Set<String>,
): PromptToggleEffect? = when {
    !isPromptToggle(key) -> null
    !next -> PromptToggleEffect.Write(
        listOf(key to "false", PROMPT_CONFIRMED_KEY to encodeConfirmedIds(confirmedIds - pluginId)),
    )
    pluginId in confirmedIds -> PromptToggleEffect.Write(
        listOf(key to "true", PROMPT_CONFIRMED_KEY to encodeConfirmedIds(confirmedIds + pluginId)),
    )
    else -> PromptToggleEffect.AskConfirm
}

/** 用户点了「确认启用」：开关开 + 本插件 id 记进确认集合。 */
fun promptConfirmAccepted(pluginId: String, confirmedIds: Set<String>): PromptToggleEffect =
    PromptToggleEffect.Write(
        listOf(PROMPT_ENABLED_KEY to "true", PROMPT_CONFIRMED_KEY to encodeConfirmedIds(confirmedIds + pluginId)),
    )

/** 用户点了「取消」：开关回弹到关，配置一个字都不动。 */
fun promptConfirmCancelled(): PromptToggleEffect = PromptToggleEffect.Write(emptyList())

/**
 * 交给开关行的闸门：确认态由设置页持有（写盘后列表快照不重组），行只负责问和落。
 * [confirmBody] 是插件 manifest 的 description，对话框正文原样复述它，不自己编政策口吻。
 */
class PromptGate(
    val pluginId: String,
    val confirmBody: String,
    val ids: () -> Set<String>,
    /** 开关落定后的确认集合：开=已记 id，关=已摘掉。关掉不摘的话，同一次页面停留里再开就漏问了。 */
    val onToggled: (Set<String>) -> Unit,
    /** 多键落盘：确认一次要同时写开关和确认集合，单键 onSave 撑不住。 */
    val write: (key: String, value: String) -> Unit,
)
