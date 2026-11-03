package dev.dsh.mobile.render

import dev.dsh.mobile.pluginapi.RichContentRenderers

/**
 * 把 :render 的富内容实现装进 plugin-api 的槽位。
 *
 * :plugin-host 不再依赖 :render（见 T1.2）：宿主只持 [RichContentRenderers] 的接口，
 * 实现在这里由 UI 侧注入。调用点在 app 的 DshApplication.onCreate（主进程、建 UI 之前）。
 */
object RichContentInstaller {

    fun install() = RichContentRenderers.install(
        markdown = { text, style, modifier -> ChatContent(text = text, style = style, modifier = modifier) },
        htmlToAnnotated = { html, base -> HtmlRenderer.toAnnotated(html, base) },
    )
}
