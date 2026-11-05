package dev.dsh.mobile.core.protocol.context

import dev.dsh.mobile.core.model.context.WarmLayer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.8-S3 装配单测：温层块的位置、空层逐字节不变、插件标记识别。
 *
 * 全部是纯函数（不联网、不碰 Room），所以能逐字断言提示词序列。
 */
class WarmAssemblyTest {

    private fun msg(role: String, text: String) = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    private fun text(m: JsonObject) = m["content"]!!.jsonPrimitive.content

    private fun prompt(messages: List<JsonObject>) = JsonArray(messages).toString()

    private val layer = WarmLayer(
        primaryGoal = "温层接进装配",
        hardConstraints = listOf("温层为空时提示词逐字节不变"),
        openTodos = listOf("S4 回捞"),
        currentWork = "写装配",
        nextStep = "跑四模块",
    )

    // ------------------------------------------------ 要求 1：空层 = 提示词逐字节不变

    @Test
    fun emptyWarmLayerKeepsPromptSequenceByteIdentical() {
        val before = listOf(
            msg("system", "sys+身份卡"),
            msg("user", "早"),
            msg("assistant", "好"),
            msg("user", "现在"),
        )
        val baseline = prompt(before)

        // null / EMPTY / 全空字段的层：三种「空」都必须原样返回同一个 list（连复制都不许）。
        assertSame(before, WarmAssembly.insert(before, null))
        assertSame(before, WarmAssembly.insert(before, ""))
        assertSame(before, WarmAssembly.insert(before, WarmAssembly.block(null)))
        assertSame(before, WarmAssembly.insert(before, WarmAssembly.block(WarmLayer.EMPTY)))
        assertSame(before, WarmAssembly.insert(before, WarmAssembly.block(WarmLayer())))
        assertEquals(baseline, prompt(WarmAssembly.insert(before, WarmAssembly.block(WarmLayer.EMPTY))))
    }

    // ------------------------------------------------ 要求 1：system → 温层 → 尾部/user

    @Test
    fun warmBlockSitsBetweenSystemAndHistory() {
        val messages = listOf(msg("system", "sys+身份卡"), msg("user", "早"), msg("user", "现在"))
        val block = WarmAssembly.block(layer)!!

        val out = WarmAssembly.insert(messages, block)

        assertEquals(4, out.size)
        assertEquals(
            listOf("system", "system", "user", "user"),
            out.map { it["role"]!!.jsonPrimitive.content },
        )
        // 首条 system（基础提示 + 身份卡）不动，温层紧随其后。
        assertEquals("sys+身份卡", text(out[0]))
        assertEquals(block, text(out[1]))
        // 温层块用插件同款包裹，首行是 ## 温层。
        assertTrue(block.startsWith(WarmAssembly.WARM_OPEN))
        assertTrue(block.contains("## 温层"))
        assertTrue(block.contains("温层接进装配"))
        assertTrue(block.endsWith(WarmAssembly.WARM_CLOSE))
        // 历史与尾部 user 原样跟在后面，顺序不变。
        assertEquals(listOf(msg("user", "早"), msg("user", "现在")), out.drop(2))
    }

    @Test
    fun warmBlockGoesFirstWhenNoSystemMessage() {
        val out = WarmAssembly.insert(listOf(msg("user", "只有用户")), WarmAssembly.block(layer))
        assertEquals(2, out.size)
        assertEquals("system", out[0]["role"]!!.jsonPrimitive.content)
        assertEquals(WarmAssembly.block(layer), text(out[0]))
        assertEquals(msg("user", "只有用户"), out[1])
    }

    // ------------------------------------------------ 要求 3：插件标记识别

    @Test
    fun pluginCheckpointIsDetectedAndOwnWarmBlockIsIgnored() {
        val own = WarmAssembly.block(layer)!!
        val plugin = "<compacted-summary>\n角色：队友\n任务：接温层\n</compacted-summary>"

        // 插件块比自己的温层块更新 → 取插件块。
        assertEquals(
            plugin,
            WarmAssembly.pluginCheckpoint(
                listOf(msg("system", "sys"), msg("system", own), msg("assistant", plugin), msg("user", "继续")),
            ),
        )
        // 只有自己写的块：不算插件已压（否则本地会把自研温层当插件内容读回来）。
        assertNull(WarmAssembly.pluginCheckpoint(listOf(msg("system", "sys"), msg("system", own))))
        // 断点标记也算插件压缩。
        assertEquals(
            "compact-checkpoint: 12 条已压",
            WarmAssembly.pluginCheckpoint(listOf(msg("assistant", "compact-checkpoint: 12 条已压"))),
        )
        // 只扫最近 20 条：更早的标记不再影响本轮装配。
        val long = ArrayList<JsonObject>()
        long += msg("assistant", plugin)
        repeat(25) { long += msg("user", "第 " + it + " 轮") }
        assertNull(WarmAssembly.pluginCheckpoint(long))
    }
}
