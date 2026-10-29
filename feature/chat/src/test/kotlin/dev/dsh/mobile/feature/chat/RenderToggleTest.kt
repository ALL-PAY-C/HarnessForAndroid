package dev.dsh.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 正文渲染路径的契约：关掉特殊格式渲染后必须逐字走源码，绝不出现空白消息。 */
class RenderToggleTest {

    private val isDoc: (String) -> Boolean = { it.startsWith("<!doctype html") }

    @Test
    fun `开着时整份 HTML 交 WebView`() {
        assertEquals(BodyRender.Html, bodyRender("<!doctype html><p>x</p>", true, null, false, isDoc))
    }

    @Test
    fun `开着时插件指定的 rendererId 直接生效`() {
        assertEquals(BodyRender.Html, bodyRender("普通文本", true, HTML_RENDERER_ID, false, isDoc))
    }

    @Test
    fun `开着时流式中的 HTML 仍走富文本`() {
        assertEquals(BodyRender.Rich, bodyRender("<!doctype html><p>x", true, null, true, isDoc))
    }

    @Test
    fun `关掉后一律原样显示源码`() {
        assertEquals(BodyRender.Source, bodyRender("<!doctype html><p>x</p>", false, null, false, isDoc))
        assertEquals(BodyRender.Source, bodyRender("\$x^2\$ 与 **粗体**", false, null, false, isDoc))
        assertEquals(
            BodyRender.Source,
            bodyRender("```kotlin\nval a = 1\n```", false, HTML_RENDERER_ID, true, isDoc),
        )
    }

    @Test
    fun `普通文本照旧走富文本`() {
        assertEquals(BodyRender.Rich, bodyRender("# 标题\n\n- 列表", true, null, false, isDoc))
    }
}
