package dev.dsh.mobile.core.protocol.llm

import dev.dsh.mobile.core.model.ReasoningStyle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 思考强度档位 → thinking budget（tokens）。档位来自模型配置，未知档按 high 处理。 */
fun thinkingBudgetOf(effortRaw: String): Int = when (effortRaw.trim().lowercase()) {
    "low" -> 1024
    "medium" -> 4096
    "off" -> 0
    else -> 8192
}

/** 映射里的一条：参数名 → 值（数字按 Int 发，其余按字符串发；空串表示不发该字段）。 */
internal data class MappedParam(val name: String, val value: String)

/**
 * 解析 `档位=参数名:值` 的推理映射（M5）。
 *
 * 空行与 # 开头的行忽略；档位统一小写；值允许为空（表示该档位一个思考字段都不发）。
 * 认不出的行直接丢掉 —— 手写配置写错一行不该把整段映射连坐废掉。
 */
internal fun parseReasoningMap(raw: String): Map<String, List<MappedParam>> {
    if (raw.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, MutableList<MappedParam>>()
    raw.lineSequence().forEach { line ->
        val text = line.trim()
        if (text.isEmpty() || text.startsWith("#")) return@forEach
        val slot = text.substringBefore("=", "").trim().lowercase()
        if (slot.isEmpty()) return@forEach
        val body = text.substringAfter("=", "").trim()
        val bucket = out.getOrPut(slot) { mutableListOf() }
        if (body.isEmpty()) return@forEach
        val name = body.substringBefore(":", "").trim()
        if (name.isEmpty()) return@forEach
        bucket += MappedParam(name, body.substringAfter(":", "").trim())
    }
    return out
}

private fun kotlinx.serialization.json.JsonObjectBuilder.applyMapped(params: List<MappedParam>) {
    params.forEach { p ->
        if (p.value.isEmpty()) return@forEach
        val int = p.value.toIntOrNull()
        if (int != null) put(p.name, int) else put(p.name, p.value)
    }
}

/**
 * 请求体里的思考参数：**唯一一处**决定发什么。
 *
 * 调用方必须先 [dev.dsh.mobile.core.model.resolveReasoningStyle] 把 auto 消解掉；
 * [ReasoningStyle.none] 与漏传的 [ReasoningStyle.auto] 都返回空对象 —— 一个字段都不发。
 */
fun reasoningParams(style: ReasoningStyle, effortRaw: String, mapRaw: String = ""): JsonObject {
    val effort = effortRaw.trim().lowercase().ifBlank { "high" }
    // M5：显式映射优先。命中就按映射写，未命中/空 map 继续走下面的内置逻辑（行为不变）。
    val mapped = parseReasoningMap(mapRaw)[effort]
    if (mapped != null) return buildJsonObject { applyMapped(mapped) }
    return buildJsonObject {
        when (style) {
            // 不发任何字段。thinking:{type:disabled} 是「显式关掉」，语义不同，不能拿它顶 none。
            ReasoningStyle.none, ReasoningStyle.auto -> Unit
            ReasoningStyle.reasoningEffort -> {
                if (effort == "off") {
                    putJsonObject("thinking") { put("type", "disabled") }
                } else {
                    putJsonObject("thinking") { put("type", "enabled") }
                    put("reasoning_effort", effort)
                }
            }
            ReasoningStyle.thinkingToggle -> {
                putJsonObject("thinking") { put("type", "enabled") }
            }
            ReasoningStyle.thinkingBudget -> {
                if (effort == "off") {
                    putJsonObject("thinking") { put("type", "disabled") }
                } else {
                    putJsonObject("thinking") {
                        put("type", "enabled")
                        put("budget_tokens", thinkingBudgetOf(effort))
                    }
                }
            }
        }
    }
}
