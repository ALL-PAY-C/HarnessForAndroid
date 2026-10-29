package dev.dsh.mobile.render

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginCapability
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginRegistry

/**
 * Registers the Markdown / LaTeX / HTML message renderer as a core plugin.
 *
 * The renderer only reads [dev.dsh.mobile.core.model.ChatMessage.text] and its
 * streaming flag, so it can be swapped in for any message without the host
 * knowing anything about Markdown.
 */
object RichTextPlugin : DshPlugin {

    const val PLUGIN_ID = "core.rich"
    const val RENDERER_ID = "core.rich"

    override val manifest: PluginManifest = PluginManifest(
        id = PLUGIN_ID,
        name = "富文本渲染器",
        version = "1.0.0",
        description = "Markdown / LaTeX / HTML",
        capabilities = setOf(PluginCapability.MessageRenderer),
    )

    override fun register(registry: PluginRegistry) {
        registry.messageRenderer(RENDERER_ID, PLUGIN_ID) { message ->
            ChatContent(
                text = message.text,
                modifier = Modifier.fillMaxWidth(),
                streaming = message.streaming,
            )
        }
    }
}
