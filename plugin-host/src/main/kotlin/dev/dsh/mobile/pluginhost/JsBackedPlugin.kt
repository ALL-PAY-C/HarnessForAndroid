package dev.dsh.mobile.pluginhost

import androidx.compose.material3.Text
import dev.dsh.mobile.pluginapi.ComposerButton
import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginCapability
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.QuickCommand
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.PluginUiNode
import dev.dsh.mobile.pluginapi.PluginUiSpec
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId
import dev.dsh.mobile.pluginapi.UiBadgeTone
import dev.dsh.mobile.pluginapi.UiTextEmphasis

/**
 * 把一次 [JsRunResult] 接进宿主注册表。
 *
 * 与 [DeclarativePlugin] 的关系是「同一份清单 + 执行过一次的 JS」：
 * 清单里的 contributions 先注册，脚本注册的再覆盖（[PluginRegistry.contribute]
 * 对同 slot 同 id 是替换），所以插件仍然保留 plugin.json 里写的静态卡片。
 *
 * 端到端只碰三条既有通道，不发明新抽象：
 *  - contributions → [PluginRegistry.contribute]（ui 走 [PluginUiJson] 翻译）
 *  - command       → [PluginRegistry.quickCommand]
 *  - composerButton→ [PluginRegistry.composerButton]
 *  - renderer      → [PluginRegistry.messageRenderer]（模板 {{text}} 换正文）
 *  - prompt        → 一条 action=Prompt 的贡献，落在输入框「+」面板
 */
class JsBackedPlugin(
    val spec: PluginManifestFile,
    private val run: JsRunResult,
    private val configValues: Map<String, String> = emptyMap(),
    private val onConfigChange: (key: String, value: String) -> Unit = { _, _ -> },
    /** 包目录：桌面插件自带的界面描述（package.json 的 dsh.ui / HTML 面板）在这里读。 */
    private val packageDir: java.io.File? = null,
) : DshPlugin {

    override val manifest: PluginManifest = PluginManifest(
        id = spec.id,
        name = spec.name,
        version = spec.version,
        description = spec.description,
        author = spec.author,
        capabilities = buildSet {
            add(PluginCapability.QuickCommand)
            if (run.composerButtons.isNotEmpty()) add(PluginCapability.ComposerButton)
            if (run.renderers.isNotEmpty()) add(PluginCapability.MessageRenderer)
        },
        enabledByDefault = true,
    )

    override fun register(registry: PluginRegistry) {
        (spec.contributions + run.contributions).forEach { c ->
            c.toSlotContribution(spec.id)?.let { registry.contribute(it) }
        }

        // 插件自带的整页界面（dsh.ui / panel.html）→ 插件中心与左侧抽屉里的一页。
        // 与 DeclarativePlugin 走同一条 PluginPage 通道，宿主只认 ui，不认来源。
        val pageUi = runCatching { DesktopUiAdapter.fromPlugin(spec, packageDir) }.getOrNull()
            // 没有自带界面也要有入口：桌面插件的 UI 本体是 React 代码，本端翻译不了，
            // 但「装上了却哪儿都点不到」比一张信息卡更糟 —— 给一张如实的信息页。
            ?: infoPage()
        registry.contribute(
            SlotContribution(
                slot = SlotId.PluginPage,
                id = spec.id + ".page",
                label = spec.name,
                subtitle = spec.description.ifBlank { "插件页面" },
                pluginId = spec.id,
                action = SlotAction.Page(spec.id),
                ui = pageUi,
            ),
        )

        // ctx.prompt() 没有直接发送通道：挂成一条 Prompt 动作，用户点一下才发。
        run.prompts.forEachIndexed { i, text ->
            registry.contribute(
                SlotContribution(
                    slot = SlotId.ComposerPlus,
                    id = spec.id + ".prompt." + i,
                    label = text.take(24),
                    subtitle = "发送提示词",
                    pluginId = spec.id,
                    action = SlotAction.Prompt(text, submit = true),
                ),
            )
        }

        run.commands.forEach { c ->
            registry.quickCommand(QuickCommand(id = c.id, label = c.label, payload = c.payload, pluginId = spec.id))
        }

        run.composerButtons.forEach { b ->
            registry.composerButton(
                ComposerButton(
                    id = b.id,
                    label = b.label,
                    pluginId = spec.id,
                    // 输入框为空就填进去，否则追加 —— 与内建快捷短语的手感一致。
                    transform = { current -> if (current.isBlank()) b.text else current + b.text },
                ),
            )
        }

        run.renderers.forEach { r ->
            // clearPlugin 按 "$pluginId." 前缀清渲染器，所以 id 必须带插件前缀。
            val key = if (r.id.startsWith(spec.id + ".")) r.id else spec.id + "." + r.id
            registry.messageRenderer(key, spec.id) { message -> Text(r.render(message.text)) }
            // ponytail: matcher 只留作原文，宿主没有「按 matcher 自动选中渲染器」的通道；
            // 渲染器要靠消息的 rendererId 命中，这里不抢那个决定权。升级路径是在
            // PluginHost.MessageSlot 里按 matcher 兜底选一遍。
        }
    }

    fun valueOf(key: String): String =
        configValues[key] ?: spec.config.firstOrNull { it.key == key }?.default.orEmpty()

    fun setValue(key: String, value: String) = onConfigChange(key, value)

    private fun ContributionSpec.toSlotContribution(pluginId: String): SlotContribution? {
        val slot = SlotId.of(slot) ?: return null
        return SlotContribution(
            slot = slot,
            id = id,
            label = label,
            subtitle = subtitle,
            icon = icon,
            pluginId = pluginId,
            action = action?.toSlotAction(),
            content = null,
            ui = ui?.let { PluginUiJson.spec(it) },
        )
    }

    /**
     * 插件没有自带界面时的信息页：版本、作者、执行结果、注册了什么。
     *
     * ponytail: 只报事实，不假装是插件自己的界面。桌面插件真正的 React UI 在本端
     * 没有对应物（见 DesktopUiAdapter 的 KDoc）；等它有 dsh.ui / HTML 面板时自动改用那个。
     */
    private fun infoPage(): PluginUiSpec = PluginUiSpec(
        root = PluginUiNode.Column(
            gap = 10,
            children = buildList {
                add(PluginUiNode.Text(spec.name, emphasis = UiTextEmphasis.Title))
                if (spec.description.isNotBlank()) add(PluginUiNode.Text(spec.description))
                add(
                    PluginUiNode.Row(
                        gap = 8,
                        children = buildList {
                            add(PluginUiNode.Badge("v" + spec.version, UiBadgeTone.Info))
                            if (spec.author.isNotBlank()) add(PluginUiNode.Badge(spec.author, UiBadgeTone.Neutral))
                            add(PluginUiNode.Badge("JS 已执行", UiBadgeTone.Success))
                        },
                    ),
                )
                add(PluginUiNode.Divider)
                add(PluginUiNode.Section(title = "运行结果", children = listOf(
                    PluginUiNode.Text(
                        "入口 " + run.entryPath + "；贡献 " + run.contributions.size +
                            " / 命令 " + run.commands.size +
                            " / 输入框按钮 " + run.composerButtons.size +
                            " / 渲染器 " + run.renderers.size,
                    ),
                )))
                add(
                    PluginUiNode.Text(
                        "这个插件没有声明可翻译的界面（dsh.ui / HTML 面板）。桌面版的 React 界面在本端没有对应物，" +
                            "它的能力已经接进宿主：注册的槽位在对应位置出现，命令与按钮可在会话里用。",
                        emphasis = UiTextEmphasis.Caption,
                    ),
                )
            },
        ),
    )

    private fun ActionSpec.toSlotAction(): SlotAction = when (kind) {
        "http" -> SlotAction.Http(method = method, url = url, body = body)
        "link" -> SlotAction.Link(uri)
        "page" -> SlotAction.Page(pageId)
        "custom" -> SlotAction.Custom(custom)
        else -> SlotAction.Prompt(text, submit = true)
    }
}

/**
 * JS 执行失败时的占位插件。
 *
 * 选它而不是「日志 + 跳过」的理由：跳过的话用户在插件列表里看不到任何东西，
 * 只会觉得「装是装上了但没反应」。占位插件正常登记进列表、register 里不做任何
 * 执行、只贡献一张错误卡，错误原文就在设置页里，插件本身零副作用。
 */
class JsBrokenPlugin(
    val spec: PluginManifestFile,
    val error: JsPluginError,
) : DshPlugin {

    override val manifest: PluginManifest = PluginManifest(
        id = spec.id,
        name = spec.name,
        version = spec.version,
        description = "JS 执行失败：" + error.message,
        author = spec.author,
        capabilities = emptySet(),
        enabledByDefault = true,
    )

    override fun register(registry: PluginRegistry) {
        registry.contribute(
            SlotContribution(
                slot = SlotId.Settings,
                id = spec.id + ".error",
                label = spec.name + " 加载失败",
                subtitle = "[" + error.stage + "] " + error.message,
                pluginId = spec.id,
                action = null,
            ),
        )
        // 抽屉 / 插件中心里也要有入口，否则用户只知道「装过」，找不到它在哪、为什么没生效。
        registry.contribute(
            SlotContribution(
                slot = SlotId.PluginPage,
                id = spec.id + ".page",
                label = spec.name,
                subtitle = "加载失败：" + error.stage,
                pluginId = spec.id,
                action = SlotAction.Page(spec.id),
                ui = PluginUiSpec(
                    root = PluginUiNode.Column(
                        gap = 10,
                        children = listOf(
                            PluginUiNode.Text(spec.name, emphasis = UiTextEmphasis.Title),
                            PluginUiNode.Row(
                                gap = 8,
                                children = listOf(
                                    PluginUiNode.Badge("v" + spec.version, UiBadgeTone.Neutral),
                                    PluginUiNode.Badge("加载失败", UiBadgeTone.Danger),
                                ),
                            ),
                            PluginUiNode.Section(
                                title = "失败阶段：" + error.stage,
                                children = listOf(
                                    PluginUiNode.Text(error.message),
                                    PluginUiNode.Text(
                                        "常见原因：插件是 ESM 包（import/export）或依赖 npm 外部模块，" +
                                            "本端运行时按 CommonJS 加载并转换常见 ESM 写法；仍失败时会显示上面的原文。",
                                        emphasis = UiTextEmphasis.Caption,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
