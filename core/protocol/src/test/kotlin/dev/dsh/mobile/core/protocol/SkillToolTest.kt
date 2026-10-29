package dev.dsh.mobile.core.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * skill 工具：工具表声明、以及「调用真的走到宿主注入的通道」。
 *
 * 设备上通道背后是 /skill-manager read，单测里没有引擎，所以注一个假通道：
 * 只要参数原样到达、返回文本原样回到模型，接线就是对的。
 */
class SkillToolTest {

    @Test
    fun skillIsExposedWithNameRequired() {
        val fn = toolsTable().single { nameOf(it) == "skill" }["function"]!!.jsonObject
        val params = fn["parameters"]!!.jsonObject
        assertEquals(listOf("name"), params["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(params["properties"]!!.jsonObject.keys.contains("name"))
        // 说明里必须点出「先读再按技能执行」，否则模型会凭技能名瞎猜做法。
        val help = fn["description"]!!.jsonPrimitive.content
        assertTrue(help, help.contains("SKILL.md"))
        assertTrue(help, help.contains("先调用本工具"))
    }

    @Test
    fun skillCallReachesInjectedExec() = runBlocking {
        val seen = mutableListOf<JsonObject>()
        val transport = transport { args ->
            seen += args
            "---\nname: alpha\n---\n按技能内容执行"
        }

        val result = transport.toolRunner.run("skill", buildJsonObject { put("name", "alpha") })

        assertTrue(result.error, result.ok)
        assertEquals(1, seen.size)
        assertEquals("alpha", seen.single()["name"]!!.jsonPrimitive.content)
        assertTrue(result.output, result.output.contains("按技能内容执行"))
    }

    @Test
    fun skillExecFailureComesBackReadable() = runBlocking {
        val transport = transport { throw IllegalStateException("未知技能: beta") }

        val result = transport.toolRunner.run("skill", buildJsonObject { put("name", "beta") })

        // 失败要落成 ok=false + 可读原因：模型下一轮靠这句话决定换个名字还是放弃。
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty(), result.error.orEmpty().contains("未知技能: beta"))
    }

    @Test
    fun withoutInjectionSkillReportsNotWired() = runBlocking {
        val result = transportNoSkill().toolRunner.run("skill", buildJsonObject { put("name", "alpha") })

        // 没接线时给一句人话，而不是空串或异常堆栈。
        assertTrue(result.output, result.output.contains("技能未接入"))
    }

    private fun nameOf(element: JsonElement): String? =
        ((element as? JsonObject)?.get("function") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull

    private fun toolsTable() = transportNoSkill().toolsJson().map { it.jsonObject }

    /** 完全不注入 skillExec：验证构造参数缺省时也有一句人话，不是空串或崩在 NPE 上。 */
    private fun transportNoSkill() = LocalEngineTransport(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        configProvider = { null },
        historyProvider = { emptyList() },
        shellExec = { "" },
    )

    private fun transport(skillExec: suspend (JsonObject) -> String) = LocalEngineTransport(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        configProvider = { null },
        historyProvider = { emptyList() },
        shellExec = { "" },
        skillExec = skillExec,
    )
}
