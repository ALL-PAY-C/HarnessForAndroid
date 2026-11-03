package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginCapability
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId

/**
 * A plugin built entirely from its `plugin.json`.
 *
 * Data-only plugins cover the common case — a button in a slot, a small settings
 * form — without shipping code, so installing one is a reversible data change
 * and cannot crash the host by construction. Anything that needs real behaviour
 * still implements [DshPlugin] in Kotlin and is compiled into the APK.
 *
 * 给别的端写的插件经常自带一套界面描述（HTML 面板、小程序页面、桌面端 JSON UI）。
 * 这里不执行它们，而是把 `ui` 子树交给 [PluginUiJson] 翻译成宿主的
 * [dev.dsh.mobile.pluginapi.PluginUiSpec] —— 装上就自动长成宿主的样子。
 */
class DeclarativePlugin(
    val spec: PluginManifestFile,
    private val configValues: Map<String, String> = emptyMap(),
    private val onConfigChange: (key: String, value: String) -> Unit = { _, _ -> },
    /** 插件包落盘目录；给了就能读 package.json / HTML 面板（见 [DesktopUiAdapter]）。 */
    private val packageDir: java.io.File? = null,
) : DshPlugin {

    override val manifest: PluginManifest = PluginManifest(
        id = spec.id,
        name = spec.name,
        version = spec.version,
        description = spec.description,
        author = spec.author,
        capabilities = setOf(PluginCapability.QuickCommand),
        enabledByDefault = true,
        apiVersion = spec.apiVersion,
        minHostApi = spec.minHostApi,
    )

    override fun register(registry: PluginRegistry) {
        spec.contributions.forEach { contribution ->
            // 未知槽位不是异常：认不出就忽略它，并把原因记进协商结果（Degraded），
            // 插件管理页因此能说清「哪一条没生效」。
            val slot = SlotId.of(contribution.slot)
            if (slot == null) {
                registry.degrade(spec.id, "未知槽位 " + contribution.slot + "，已忽略（id=" + contribution.id + "）")
                return@forEach
            }
            val action = contribution.action?.toSlotAction()
            if (contribution.action != null && action == null) {
                registry.degrade(spec.id, "未知动作 " + contribution.action.kind + "，已忽略（id=" + contribution.id + "）")
            }
            // 超配额的贡献由 registry 直接拒收并把原因记进 rejections/compat，这里不必再判一次。
            registry.contribute(
                SlotContribution(
                    slot = slot,
                    id = contribution.id,
                    label = contribution.label,
                    subtitle = contribution.subtitle,
                    icon = contribution.icon,
                    pluginId = spec.id,
                    action = action,
                    content = null,
                    ui = contribution.ui?.let { PluginUiJson.spec(it) },
                ),
            )
        }

        // 插件自带的页面（桌面端 dsh.ui / package.json / HTML 面板）：翻译成功就额外占一个
        // plugin.page 槽位，用户点开插件能看到它；翻译不出来就什么都不加，绝不伪造界面。
        // plugin.json 自己声明了 plugin.page 贡献时以它为准，否则详情里会出现两张一样的页面卡。
        if (registry.contributions.any { it.pluginId == spec.id && it.slot == SlotId.PluginPage }) return
        val page = runCatching { DesktopUiAdapter.fromPlugin(spec, packageDir) }.getOrNull() ?: return
        registry.contribute(
            SlotContribution(
                slot = SlotId.PluginPage,
                id = spec.id + ".page",
                label = spec.name,
                subtitle = spec.description,
                pluginId = spec.id,
                action = SlotAction.Page(pageId = spec.id),
                ui = page,
            ),
        )
    }

    /** Value for the auto-generated form, falling back to the manifest default. */
    fun valueOf(key: String): String =
        configValues[key] ?: spec.config.firstOrNull { it.key == key }?.default.orEmpty()

    fun setValue(key: String, value: String) = onConfigChange(key, value)

    /**
     * action kind → 宿主动作。认不出的 kind 返回 null（调用方记 Degraded 并忽略），
     * **不再降级成 prompt** —— 那等于替插件把一条它没打算发的消息发出去。
     */
    private fun ActionSpec.toSlotAction(): SlotAction? =
        configAction(spec.id) ?: personaAction() ?: when (kind.trim().lowercase()) {
            "", "prompt" -> SlotAction.Prompt(text, submit = true)
            "http" -> SlotAction.Http(method = method, url = url, body = body)
            "link" -> SlotAction.Link(uri)
            "page" -> SlotAction.Page(pageId)
            "custom" -> SlotAction.Custom(custom)
            else -> null
        }
}