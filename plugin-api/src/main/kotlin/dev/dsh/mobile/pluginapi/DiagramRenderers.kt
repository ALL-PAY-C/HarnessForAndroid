package dev.dsh.mobile.pluginapi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 图形/图表围栏的渲染槽（```svg / ```mermaid / ```dot / ```echarts …）。
 *
 * 与 [HtmlBlockRenderers] 同构：宿主 Markdown 渲染器（:render）只在装了渲染插件时
 * 才把围栏交给 [render]，没装就退回源码卡。气泡、头像、工具卡仍归宿主。
 *
 * kind 由宿主侧的 DiagramKinds 归一（小写、无歧义），插件按 kind 决定用哪个库。
 * 已发布插件若用不到派生能力，可忽略；[generation] 保证首帧之后注册也能即时生效。
 */
object DiagramRenderers {

    /** 宿主在 Composable 里读它，插件注册那一下会触发重组（源码卡 → 图形卡）。 */
    var generation by mutableIntStateOf(0)
        private set

    /** kind 是归一后的类型（svg/mermaid/graphviz/plantuml/echarts/chartjs/tikz/python/ascii/html）。 */
    @Volatile
    var render: (@Composable (kind: String, source: String) -> Unit)? = null
        private set

    val available: Boolean get() = render != null

    fun install(block: @Composable (kind: String, source: String) -> Unit) {
        render = block
        generation++
    }

    fun clear() {
        render = null
        generation++
    }
}
