package dev.dsh.mobile.plugin.html

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.plugin.html.diagram.DiagramView
import dev.dsh.mobile.pluginapi.DiagramRenderers
import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.HtmlBlockRenderers
import dev.dsh.mobile.pluginapi.PluginCapability
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginRegistry

/**
 * 插件入口。宿主用 PathClassLoader(apkPath, context.classLoader) +
 * 无参构造反射实例化，所以这个类必须 public 且有无参构造。
 */
class HtmlPlugin : DshPlugin {

    override val manifest: PluginManifest = PluginManifest(
        id = ID,
        name = "HTML 渲染",
        version = RenderScript.VERSION,
        description = "用离线 WebView 把消息里的 HTML / Markdown / LaTeX 渲染成网页视图",
        author = "DSH",
        capabilities = setOf(PluginCapability.MessageRenderer),
    )

    override fun register(registry: PluginRegistry) {
        registry.messageRenderer(RENDERER_ID, ID) { message -> HtmlMessageView(message) }

        // 宿主 Markdown 渲染器遇到的 html 代码围栏交给这里真渲染（气泡仍由宿主绘制）。
        HtmlBlockRenderers.install { html -> HtmlWebView(html = html) }

        // 图形围栏（mermaid / dot / echarts / chart.js / plantuml / ascii …）走同一套离线 WebView。
        DiagramRenderers.install { kind, source -> DiagramView(kind = kind, source = source) }

        // 不再往 ChatOverflow 塞「网页视图渲染」条目：这个插件已内置进 app，会话右侧边栏
        // 只留用户要的「特殊格式渲染」滑块；那条 custom 动作只是回报接管状态，宿主侧
        // HOST_CUSTOM_HANDLERS 里仍然保留，外部插件照旧可以用。
        HtmlPluginSelfCheck.run().forEach { line -> AppLog.i("dsh.html selfcheck: " + line) }
    }

    companion object {
        const val ID: String = "dsh.html"
        const val RENDERER_ID: String = "html.web"
    }
}
