package dev.dsh.mobile.core.protocol.llm

import java.util.UUID
import dev.dsh.mobile.core.protocol.DshJson
import dev.dsh.mobile.core.protocol.ToolExecResult
import dev.dsh.mobile.core.protocol.ToolOutputSpill
import dev.dsh.mobile.core.protocol.stripDsmlToolCalls
import dev.dsh.mobile.core.protocol.truncateForTool
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 子代理：最多 8 轮工具往返（主循环 64 轮，但子代理的结论必须能被父代理等到）。 */
internal const val SUBAGENT_MAX_ROUNDS = 8

/** 子代理总预算：180 秒。超时回可读错误，不会返回空串。 */
internal const val SUBAGENT_BUDGET_MS = 180_000L

/** 子代理工具白名单：不含 subagent，嵌套深度天然 1 层。 */
internal val SUBAGENT_TOOLS: Set<String> = setOf("shell", "fs_read", "fs_list", "fs_write", "fs_op", "phone")

/** 子代理角色说明，拼在会话系统提示词后面。 */
internal const val SUBAGENT_ROLE = "你是被派发的子代理，只完成交给你的子任务，直接给结论；你看不到主对话历史。"


/**
 * 宽松取字符串：null / JsonNull / 非字符串一律当空串（上游字段各家不统一）。
 *
 * 原来与 upstreamEmptyReason 同文件 private；子代理循环拆出后两处都要用，改成 module-internal。
 */
internal fun JsonElement?.strOrEmpty(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()


/** 一次委派的结果：[ok]=false 时 [text] 是可读错误，从不为空串。 */
internal data class SubagentOutcome(val ok: Boolean, val text: String, val steps: Int)

/**
 * 子代理循环：独立 messages、独立工具白名单、最多 [maxRounds] 轮、总预算 [budgetMs]。
 *
 * 为什么单独一个类：它不碰主对话的 messages、不发 TurnEnd、不弹权限卡，
 * 于是「假上游 + 假工具」就能在 JVM 单测里把整条委派路径跑通。
 */
internal class SubagentLoop(
    private val model: String,
    private val parentSystemPrompt: String,
    /** 一次上游往返（复用主代理的流式路径，含网关自举与引擎路由）。 */
    private val chat: suspend (JsonObject) -> JsonObject,
    /** 一次工具执行（复用主代理的 ToolExecutor）。 */
    private val runTool: suspend (String, JsonObject) -> ToolExecResult,
    /** 白名单工具表（不含 subagent）。 */
    private val tools: JsonArray,
    private val label: String = "子代理",
    private val maxRounds: Int = SUBAGENT_MAX_ROUNDS,
    private val budgetMs: Long = SUBAGENT_BUDGET_MS,
    /** 每走一步的进度回调：父代理把它翻成 ToolCallEvent 的 detail。 */
    private val onStep: (String) -> Unit = {},
    /** 大输出落盘（P0-4）：子代理读到大文件时同样只拿头尾 + locator。 */
    private val spill: ToolOutputSpill? = null,
) {
    suspend fun run(userPrompt: String): SubagentOutcome {
        val messages = mutableListOf<JsonObject>(
            buildJsonObject {
                put("role", "system")
                put("content", parentSystemPrompt.trim().ifBlank { "你是运行在用户 Android 手机上的助手。" } + "\n\n" + SUBAGENT_ROLE)
            },
            buildJsonObject {
                put("role", "user")
                put("content", userPrompt)
            },
        )
        var steps = 0
        var lastText = ""
        val finished = withTimeoutOrNull(budgetMs) {
            repeat(maxRounds) {
                val resp = chat(
                    buildJsonObject {
                        put("model", model)
                        put("messages", JsonArray(messages))
                        if (tools.isNotEmpty()) put("tools", tools)
                    },
                )
                val msg = (resp["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
                    ?.get("message") as? JsonObject
                val rawContent = msg?.get("content").strOrEmpty()
                val explicit = msg?.get("tool_calls")?.jsonArray
                val content: String
                val calls: JsonArray
                if (explicit == null || explicit.isEmpty()) {
                    // 与主循环同一套兜底：上游把原生工具标记当正文流回来。
                    val stripped = stripDsmlToolCalls(rawContent)
                    content = stripped.first
                    calls = JsonArray(stripped.second.map { it.toToolCallJson() })
                } else {
                    content = rawContent
                    calls = explicit
                }
                if (content.isNotBlank()) lastText = content
                if (calls.isEmpty()) return@withTimeoutOrNull true
                messages += buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                    put("tool_calls", calls)
                }
                for (tc in calls) {
                    val obj = tc.jsonObject
                    val id = obj["id"].strOrEmpty().ifBlank { UUID.randomUUID().toString() }
                    val fn = obj["function"] as? JsonObject
                    val name = fn?.get("name").strOrEmpty()
                    val argsRaw = fn?.get("arguments").strOrEmpty().ifBlank { "{}" }
                    val args = runCatching { DshJson.parseToJsonElement(argsRaw).jsonObject }
                        .getOrDefault(JsonObject(emptyMap()))
                    val brief = if (name == "shell") args["command"].strOrEmpty() else args.toString()
                    steps++
                    onStep(name + "：" + brief.take(120))
                    // 白名单二次校验：工具表里已经滤掉 subagent，模型硬写也执行不了（深度只有 1 层）。
                    val result = if (name in SUBAGENT_TOOLS) {
                        runTool(name, args)
                    } else {
                        ToolExecResult(ok = false, output = "", error = "子代理不能用工具：" + name)
                    }
                    messages += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", id)
                        // 子代理同样落盘：子任务里读大文件就不用把 6000 字硬截当上限了。
                        put("content", spill?.present(resultReply(result), null, "sub-" + id) ?: resultReply(result).truncateForTool())
                    }
                }
            }
            return@withTimeoutOrNull false
        }
        return when {
            finished == null -> SubagentOutcome(
                ok = false,
                text = "[子代理超时] 「" + label + "」跑了 " + (budgetMs / 1000) + " 秒没给结论，已中止；把任务拆小再派一次。",
                steps = steps,
            )
            lastText.isBlank() -> SubagentOutcome(
                ok = false,
                text = "[子代理无输出] 「" + label + "」用完 " + maxRounds + " 轮工具往返也没写下结论。",
                steps = steps,
            )
            else -> SubagentOutcome(ok = true, text = lastText, steps = steps)
        }
    }

    /** 工具结果回灌：失败也把错误交回去，子代理才有机会换个做法。 */
    private fun resultReply(r: ToolExecResult): String =
        if (r.ok) r.output else "[执行失败] " + (r.error?.takeIf { it.isNotBlank() } ?: "未知错误") + "\n" + r.output
}