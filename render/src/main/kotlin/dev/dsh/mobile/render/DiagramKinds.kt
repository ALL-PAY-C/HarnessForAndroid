package dev.dsh.mobile.render

/**
 * 围栏语言 -> 图形 kind 的归一表。
 *
 * 为什么不直接拿 lang 当 kind 用：同一种图在 Markdown 里有多种合法写法
 * （```dot / ```graphviz / ```gv、```mermaid / ```mmd），而插件侧只需要认一套
 * 稳定的小写标识。归一放在宿主（:render）这一层，插件就不必各自维护别名表；
 * 反过来，宿主也只输出 kind 字符串，不认识具体库。
 *
 * 只做字符串归一，不碰 Compose，因此是纯 JVM 可单测的。
 */
object DiagramKinds {

    /** 别名表：key 是归一后的 lang，value 是给插件的 kind。 */
    private val ALIASES: Map<String, String> = buildMap {
        put("svg", "svg")

        put("mermaid", "mermaid")
        put("mmd", "mermaid")

        put("dot", "graphviz")
        put("graphviz", "graphviz")
        put("gv", "graphviz")

        put("plantuml", "plantuml")
        put("puml", "plantuml")

        put("html", "html")
        put("htm", "html")
        put("xhtml", "html")

        put("echarts", "echarts")
        put("echarts-json", "echarts")

        put("chart", "chartjs")
        put("chartjs", "chartjs")
        put("chart.js", "chartjs")

        put("tikz", "tikz")
        put("tex-tikz", "tikz")

        put("python", "python")
        put("py", "python")
        put("python3", "python")
        put("matplotlib", "python")

        put("ascii", "ascii")
        put("asciiart", "ascii")
        put("ascii-art", "ascii")
        put("diagram-text", "ascii")
    }

    /**
     * lang 归一后查表。归一规则：trim、去掉前导点、lowercase。
     *
     * 去前导点是为了吃下 ``` .mermaid 这种写法（Markdown 里常见，语言名前面带个点）。
     * 空串返回 null，表示"不是图形围栏"，调用方退回普通代码块。
     */
    fun of(lang: String): String? {
        val normalized = lang.trim().removePrefix(".").lowercase()
        if (normalized.isEmpty()) return null
        return ALIASES[normalized]
    }
}
