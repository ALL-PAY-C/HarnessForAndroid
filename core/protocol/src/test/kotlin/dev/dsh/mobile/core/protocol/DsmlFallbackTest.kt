package dev.dsh.mobile.core.protocol

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文里混着的 DSML / XML 工具调用标记：解析成 [{name, arguments}]、从正文里剔掉、
 * 流式阶段就吞掉。上游把 tool_calls 当文本吐回来时，任务不能就这么停住。
 */
class DsmlFallbackTest {

    private fun argsOf(call: DsmlToolCall): Map<String, String> = runCatching {
        DshJson.parseToJsonElement(call.arguments).jsonObject
            .mapValues { (_, v) -> v.jsonPrimitive.content }
    }.getOrElse { emptyMap() }

    @Test
    fun doublePipeDsmlBlockBecomesOneToolCall() {
        val content = """
            我先看下目录。
            <｜｜DSML｜｜tool_calls>
            <｜｜DSML｜｜invoke name="shell">
            <｜｜DSML｜｜parameter name="command" string="true">ls /sdcard</｜｜DSML｜｜parameter>
            </｜｜DSML｜｜invoke>
            </｜｜DSML｜｜tool_calls>
        """.trimIndent()

        val (clean, calls) = stripDsmlToolCalls(content)

        assertEquals(1, calls.size)
        assertEquals("shell", calls[0].name)
        assertEquals("ls /sdcard", argsOf(calls[0])["command"])
        assertFalse(clean.contains("DSML"))
        assertTrue(clean.startsWith("我先看下目录"))
    }

    @Test
    fun singlePipeVariantParsesTheSameWay() {
        val content = """
            <|DSML|tool_calls>
            <|DSML|invoke name="shell">
            <|DSML|parameter name="command" string="true">df -h</|DSML|parameter>
            </|DSML|invoke>
            </|DSML|tool_calls>
        """.trimIndent()

        val (clean, calls) = stripDsmlToolCalls(content)

        assertEquals(1, calls.size)
        assertEquals("df -h", argsOf(calls[0])["command"])
        assertEquals("", clean)
    }

    @Test
    fun fullwidthSinglePipeVariantParses() {
        val content = """
            <｜DSML｜tool_calls>
            <｜DSML｜invoke name="shell">
            <｜DSML｜parameter name="command" string="true">whoami</｜DSML｜parameter>
            </｜DSML｜invoke>
            </｜DSML｜tool_calls>
        """.trimIndent()

        val (_, calls) = stripDsmlToolCalls(content)

        assertEquals(1, calls.size)
        assertEquals("whoami", argsOf(calls[0])["command"])
    }

    @Test
    fun bareInvokeWithoutWrapperStillParses() {
        val content = "先看看时间\n<|DSML|invoke name=date></|DSML|invoke>"

        val (clean, calls) = stripDsmlToolCalls(content)

        assertEquals(1, calls.size)
        assertEquals("date", calls[0].name)
        assertEquals("{}", calls[0].arguments)
        assertFalse(clean.contains("DSML"))
    }

    @Test
    fun toolCallJsonBlockBecomesAToolCall() {
        val content = "列出工作目录\n<tool_call>{\"name\":\"shell\",\"arguments\":{\"command\":\"pwd\"}}</tool_call>"

        val (clean, calls) = stripDsmlToolCalls(content)

        assertEquals(1, calls.size)
        assertEquals("shell", calls[0].name)
        assertEquals("pwd", argsOf(calls[0])["command"])
        assertFalse(clean.contains("tool_call"))
    }

    @Test
    fun functionCallWrapperWithOpenAiShapeIsAccepted() {
        val content = "<function_call>{\"function\":{\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":\\\"id\\\"}\"}}</function_call>"

        val (_, calls) = stripDsmlToolCalls(content)

        assertEquals(1, calls.size)
        assertEquals("shell", calls[0].name)
        assertEquals("id", argsOf(calls[0])["command"])
    }

    @Test
    fun multipleInvokesInOneBlockAllComeThrough() {
        val content = """
            <|DSML|tool_calls>
            <|DSML|invoke name="shell">
            <|DSML|parameter name="command" string="true">ls</|DSML|parameter>
            </|DSML|invoke>
            <|DSML|invoke name="shell">
            <|DSML|parameter name="command" string="true">pwd</|DSML|parameter>
            </|DSML|invoke>
            </|DSML|tool_calls>
        """.trimIndent()

        val (_, calls) = stripDsmlToolCalls(content)

        assertEquals(2, calls.size)
        assertEquals("ls", argsOf(calls[0])["command"])
        assertEquals("pwd", argsOf(calls[1])["command"])
    }

    @Test
    fun parameterTypeAttributesPickTheRightJsonShape() {
        val content = """
            <|DSML|tool_calls>
            <|DSML|invoke name="phone">
            <|DSML|parameter name="x" string="false">42</|DSML|parameter>
            <|DSML|parameter name="opts" xsi:type="object">{"deep":true}</|DSML|parameter>
            <|DSML|parameter name="raw" string="true">{"deep":true}</|DSML|parameter>
            </|DSML|invoke>
            </|DSML|tool_calls>
        """.trimIndent()

        val (_, calls) = stripDsmlToolCalls(content)

        assertEquals(1, calls.size)
        assertTrue(calls[0].arguments, calls[0].arguments.contains("\"x\":42"))
        assertTrue(calls[0].arguments, calls[0].arguments.contains("\"opts\":{\"deep\":true}"))
        assertTrue(calls[0].arguments, calls[0].arguments.contains("\"raw\":\"{\\\"deep\\\":true}\""))
    }

    @Test
    fun plainTextWithoutMarkersIsUntouched() {
        val content = "正常回答：a < b，而且没有工具调用。"

        val (clean, calls) = stripDsmlToolCalls(content)

        assertEquals(content, clean)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun brokenMarkerKeepsTheOriginalText() {
        val brokenJson = "<tool_call>{not json}</tool_call>"
        val unclosed = "<|DSML|tool_calls><|DSML|invoke name=\"shell\">"

        val first = stripDsmlToolCalls(brokenJson)
        assertEquals(brokenJson, first.first)
        assertTrue(first.second.isEmpty())

        val second = stripDsmlToolCalls(unclosed)
        assertEquals(unclosed, second.first)
        assertTrue(second.second.isEmpty())
    }

    @Test
    fun toolCallJsonCarriesIdTypeAndFunction() {
        val obj = DsmlToolCall("shell", "{\"command\":\"ls\"}").toToolCallJson("fixed-id")

        assertEquals("fixed-id", obj["id"]!!.jsonPrimitive.content)
        assertEquals("function", obj["type"]!!.jsonPrimitive.content)
        assertEquals("shell", obj["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun streamFilterSwallowsMarkersSplitAcrossChunks() {
        val filter = DsmlStreamFilter()
        val out = StringBuilder()
        out.append(filter.feed("我先跑一下"))
        out.append(filter.feed("<｜｜DSM"))
        out.append(filter.feed("L｜｜tool_calls><｜｜DSML｜｜invoke name=\"shell\">"))
        out.append(filter.feed("<｜｜DSML｜｜parameter name=\"command\">ls</｜｜DSML｜｜parameter>"))
        out.append(filter.feed("</｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>"))
        out.append(filter.feed(" 好了"))
        out.append(filter.finish())

        assertEquals("我先跑一下 好了", out.toString())
    }

    /** 真机现场那种写法：标记名前面带空格、用 calls 而不是 tool_calls。 */
    private val spacedMarkup = "先看一下环境<｜｜DSML｜｜ calls>\n" +
        "<｜｜DSML｜｜ invoke name=\"shell\">\n" +
        "<｜｜DSML｜｜ parameter name=\"channel\" string=\"true\">app</｜｜DSML｜｜ parameter>\n" +
        "<｜｜DSML｜｜ parameter name=\"command\" string=\"true\">ls /sdcard</｜｜DSML｜｜ parameter>\n" +
        "<｜｜DSML｜｜ parameter name=\"reason\" string=\"true\">看看</｜｜DSML｜｜ parameter>\n" +
        "</｜｜DSML｜｜ invoke>\n" +
        "</｜｜DSML｜｜ calls>"

    @Test
    fun spacedTagNamesAndCallsAliasAreParsed() {
        val (text, calls) = stripDsmlToolCalls(spacedMarkup)
        assertEquals("先看一下环境", text)
        assertEquals(1, calls.size)
        assertEquals("shell", calls[0].name)
        val args = argsOf(calls[0])
        assertEquals("app", args["channel"])
        assertEquals("ls /sdcard", args["command"])
        assertEquals("看看", args["reason"])
    }

    @Test
    fun spacedMarkupIsSwallowedByStreamFilter() {
        val filter = DsmlStreamFilter()
        val out = StringBuilder()
        for (i in spacedMarkup.indices step 13) {
            out.append(filter.feed(spacedMarkup.substring(i, minOf(i + 13, spacedMarkup.length))))
        }
        out.append(filter.finish())
        assertEquals("先看一下环境", out.toString())
    }

    @Test
    fun bareSpacedInvokeWithoutWrapperWorks() {
        val raw = "<｜｜DSML｜｜ invoke name=\"fs_list\">" +
            "<｜｜DSML｜｜ parameter name=\"path\" string=\"true\">/sdcard</｜｜DSML｜｜ parameter>" +
            "</｜｜DSML｜｜ invoke>"
        val (text, calls) = stripDsmlToolCalls(raw)
        assertEquals("", text)
        assertEquals("fs_list", calls[0].name)
        assertEquals("/sdcard", argsOf(calls[0])["path"])
    }

    @Test
    fun streamFilterKeepsOrdinaryTextThatOnlyLooksLikeMarkup() {
        val filter = DsmlStreamFilter()
        val out = StringBuilder()
        out.append(filter.feed("if a < b and c <t"))
        out.append(filter.feed("ool> yes"))
        out.append(filter.finish())

        assertEquals("if a < b and c <tool> yes", out.toString())
    }
}
