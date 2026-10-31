package dev.dsh.mobile.plugin.html.diagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * DiagramHtml / PlantUml 是纯 JVM 逻辑（不碰 Android），所以能直接在单测里断言生成的 HTML。
 * 设备上跑得通与否靠实测，这里只锁三件会静默坏掉的事：
 * 1) kind 必须引到对的那份离线库；2) 源码里的 </textarea>/<script> 不能提前闭合标签；
 * 3) 未知 kind 必须降级成源码而不是空页。
 */
class DiagramHtmlTest {

    @Test
    fun modeOf_maps_aliases_and_falls_back_to_source() {
        assertEquals(DiagramHtml.Mode.GRAPHVIZ, DiagramHtml.modeOf("dot"))
        assertEquals(DiagramHtml.Mode.GRAPHVIZ, DiagramHtml.modeOf("GraphViz"))
        assertEquals(DiagramHtml.Mode.SVG, DiagramHtml.modeOf(" svg "))
        assertEquals(DiagramHtml.Mode.CHARTJS, DiagramHtml.modeOf("chart.js"))
        assertEquals(DiagramHtml.Mode.PLANTUML, DiagramHtml.modeOf("plantuml"))
        // 不执行的三种一律走源码卡
        assertEquals(DiagramHtml.Mode.SOURCE, DiagramHtml.modeOf("tikz"))
        assertEquals(DiagramHtml.Mode.SOURCE, DiagramHtml.modeOf("python"))
        assertEquals(DiagramHtml.Mode.SOURCE, DiagramHtml.modeOf("ascii"))
        assertEquals(DiagramHtml.Mode.SOURCE, DiagramHtml.modeOf("从来没见过的类型"))
    }

    @Test
    fun build_references_the_matching_offline_library() {
        val mermaid = DiagramHtml.build("mermaid", "graph TD;A-->B;", dark = false)
        assertTrue(mermaid.contains("src=\"mermaid.min.js\""))
        assertTrue(mermaid.contains("m.run("))

        val graphviz = DiagramHtml.build("graphviz", "digraph{a->b}", dark = true)
        assertTrue(graphviz.contains("src=\"viz-global.js\""))
        // 降级链路两段都得在页面里，否则 wasm 失败后没人接
        assertTrue(graphviz.contains("full.render.js"))
        assertTrue(graphviz.contains("new V()"))

        val echarts = DiagramHtml.build("echarts", "{\"series\":[]}", dark = false)
        assertTrue(echarts.contains("src=\"echarts.min.js\""))
        // 高度写死 260px：echarts 需要有尺寸的容器才画得出东西
        assertTrue(echarts.contains("height:260px"))
        assertTrue(echarts.contains("new Function"))

        val chartjs = DiagramHtml.build("chartjs", "{type:'line'}", dark = false)
        assertTrue(chartjs.contains("src=\"chart.umd.min.js\""))
        assertTrue(chartjs.contains("new Chart("))

        // 内联 SVG 不引任何库，只做 DOMParser 清洗 + 自适应
        val svg = DiagramHtml.build("svg", "<svg viewBox='0 0 10 10'/>", dark = false)
        assertFalse(svg.contains("<script src="))
        assertTrue(svg.contains("DOMParser"))
        assertTrue(svg.contains("querySelectorAll('script,foreignObject')"))
    }

    @Test
    fun svg_page_fills_viewport_and_reports_aspect_ratio() {
        val html = DiagramHtml.build("svg", "<svg viewBox='0 0 240 120'/>", dark = false)
        // 容器必须贴满视口：绝对定位拿确定的像素高度，不依赖 html/body/#wrap 的 height:100% 链
        // （那条链在 WebView 里会让内层 <svg> 算成 0 高：卡片高度对、内容一个像素不画）
        assertTrue(html.contains("html,body{height:100%;}"))
        assertTrue(html.contains("#wrap{position:absolute;left:0;top:0;right:0;bottom:0;padding:0;}"))
        assertTrue(html.contains("#out{width:100%;height:100%;}"))
        // 内层 <svg> 的尺寸由 JS 按实测容器写成像素，CSS 只保证 block 排版
        assertTrue(html.contains("#out svg{display:block;}"))
        // 不能再让 WebView 自己按 height:auto 猜高度
        assertFalse(html.contains("#out svg{display:block;width:100%;height:auto;}"))
        // 比例上报：viewBox 优先 → 根节点 width/height → 4:3 兜底
        assertTrue(html.contains("var ratio=AR(svg);"))
        assertTrue(html.contains("D('fit:'+ratio)"))
        assertTrue(html.contains("if(vw>0&&vh>0)return vh/vw;"))
        assertTrue(html.contains("if(aw>0&&ah>0)return ah/aw;"))
        assertTrue(html.contains("return 0.75;"))
        // FITPX 用实测容器写像素宽高，并在窗口/容器变化时重算；FIT 的 fill 分支不碰尺寸
        assertTrue(html.contains("var FITPX=function(sv,r){"))
        assertTrue(html.contains("sv.setAttribute('width',w);sv.setAttribute('height',h);"))
        assertTrue(html.contains("window.addEventListener('resize',lay);"))
        assertTrue(html.contains("new ResizeObserver(lay)"))
        assertTrue(html.contains("sv.style.maxWidth='none';"))
        assertTrue(html.contains("sv.style.height='auto';"))
    }

    @Test
    fun other_channels_keep_their_own_height_rules() {
        // 这两条的既有高度测量不能被 SVG 的改动带跑
        val mermaid = DiagramHtml.build("mermaid", "graph TD;A-->B;", dark = false)
        assertTrue(mermaid.contains("#out svg{max-width:100%;height:auto;}"))
        assertFalse(mermaid.contains("fit:"))

        val graphviz = DiagramHtml.build("graphviz", "digraph{a->b}", dark = false)
        assertTrue(graphviz.contains("#out svg{display:block;width:100%;height:auto;}"))
        assertFalse(graphviz.contains("fit:"))

        val echarts = DiagramHtml.build("echarts", "{\"series\":[]}", dark = false)
        assertTrue(echarts.contains("#out{width:100%;height:260px;}"))
        assertFalse(echarts.contains("fit:"))
    }

    @Test
    fun source_is_escaped_and_cannot_break_out_of_textarea() {
        val evil = "</textarea><script>alert(1)</script> & \"q\" 'a'"
        val html = DiagramHtml.build("mermaid", evil, dark = false)
        assertTrue(html.contains("&lt;/textarea&gt;"))
        assertFalse(html.contains("</textarea><script>"))
        // 整页只该有我们自己写的 script 标签，注入的那个必须只剩转义文本
        assertFalse(html.contains("<script>alert"))
        assertEquals(1, Regex("</textarea>").findAll(html).count())
        assertTrue(html.contains("&amp;"))
    }

    @Test
    fun unknown_kind_degrades_to_source_card() {
        val html = DiagramHtml.build("quantum-uml", "print('hi')", dark = true)
        assertFalse(html.contains("<script src="))
        assertTrue(html.contains("pre.textContent=S()"))
        // 源码仍在页面里（转义后），不是空页
        assertTrue(html.contains("print(&#39;hi&#39;)"))
        assertTrue(DiagramHtml.noteOf("tikz").contains("TikZ"))
        assertTrue(DiagramHtml.noteOf("python").contains("Python"))
        assertTrue(DiagramHtml.noteOf("ascii").isNotEmpty())
    }

    @Test
    fun dark_flag_switches_background_and_mermaid_theme() {
        val dark = DiagramHtml.build("mermaid", "graph TD;A-->B;", dark = true)
        assertTrue(dark.contains("theme:DARK?'dark':'default'"))
        assertTrue(dark.contains("var DARK=true"))
        assertTrue(dark.contains("#17181c"))
        val light = DiagramHtml.build("mermaid", "graph TD;A-->B;", dark = false)
        assertTrue(light.contains("var DARK=false"))
        assertTrue(light.contains("#ffffff"))
    }

    @Test
    fun plantuml_payload_is_zlib_deflate_base64url() {
        val source = "@startuml\nAlice -> Bob: 你好\n@enduml"
        val encoded = PlantUml.encode(source)
        // base64url：不能出现 + / =，否则 kroki 会 404
        assertFalse(encoded.contains("+"))
        assertFalse(encoded.contains("/"))
        assertFalse(encoded.contains("="))
        assertEquals(source, inflate(encoded))
        assertTrue(PlantUml.url(source).startsWith("https://kroki.io/plantuml/svg/"))
        // 同一份源码必须确定性编码（缓存/重试都靠它）
        assertEquals(encoded, PlantUml.encode(source))
    }

    private fun inflate(encoded: String): String {
        val raw = Base64.getUrlDecoder().decode(encoded)
        // zlib 格式（带 header）：kroki 服务端用的就是这个解压模式，raw deflate 会被解成乱码
        val inflater = Inflater(false)
        inflater.setInput(raw)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}
