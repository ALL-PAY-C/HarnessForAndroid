package dev.dsh.mobile.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 围栏分发：谁能走 HTML 通道，谁必须让给图形通道。
 *
 * svg 同时在 HTML_LANGS 和 DiagramKinds 里，唯一能把它交给图形插件画出来的
 * 就是 [usesHtmlFenceView] 里那一次排除；xml 保持走 HTML 通道不变。
 */
class FenceRoutingTest {

    @Test
    fun svgGoesToDiagramChannel() {
        assertFalse(usesHtmlFenceView("svg"))
        assertFalse(usesHtmlFenceView("SVG"))
        assertFalse(usesHtmlFenceView(" svg "))
        // 排除之后 DiagramKinds 才轮得到它，且有 kind 可发。
        assertTrue(DiagramKinds.of("svg") == "svg")
    }

    @Test
    fun xmlAndHtmlStayOnHtmlChannel() {
        assertTrue(usesHtmlFenceView("html"))
        assertTrue(usesHtmlFenceView("htm"))
        assertTrue(usesHtmlFenceView("xhtml"))
        assertTrue(usesHtmlFenceView("xml"))
    }

    @Test
    fun diagramLangsNeverTakeHtmlChannel() {
        assertFalse(usesHtmlFenceView("mermaid"))
        assertFalse(usesHtmlFenceView("dot"))
        assertFalse(usesHtmlFenceView(""))
    }
}
