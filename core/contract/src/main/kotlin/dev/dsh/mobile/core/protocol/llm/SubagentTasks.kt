package dev.dsh.mobile.core.protocol.llm

import dev.dsh.mobile.core.protocol.DshJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 一次委派最多收这么多条子任务：再多就不是「拆活」而是刷池。 */
internal const val SUBAGENT_MAX_TASKS = 8

/** 子代理结论里显式给出的一个子任务：标题 + 说明。 */
data class SubagentTaskSpec(val title: String, val detail: String)

/**
 * 从子代理的结论文本里抠任务清单（T5.3）。
 *
 * 模型两种写法都见过：末尾一段 JSON 数组（`[{"title":..,"detail":..}]` 或 `["任务"]`），
 * 或者 `- 任务：<标题>｜<说明>` 这类列表。宽容解析、失败返回空表 —— 结论文本本身
 * 由调用方原样带回去，解析失败只表示「这轮没派活」，绝不吞掉结论、绝不抛异常。
 */
fun parseSubagentTasks(text: String): List<SubagentTaskSpec> {
    if (text.isBlank()) return emptyList()
    return fromJsonArray(text).ifEmpty { fromBulletList(text) }.take(SUBAGENT_MAX_TASKS)
}

/** 取文本里第一段 `[` … 最后一段 `]` 当 JSON 数组试一次；形状不对就是空表。 */
private fun fromJsonArray(text: String): List<SubagentTaskSpec> {
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    val array = runCatching { DshJson.parseToJsonElement(text.substring(start, end + 1)) as? JsonArray }
        .getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        when (element) {
            is JsonObject -> {
                val title = element.str("title").ifBlank { element.str("task") }.ifBlank { element.str("name") }
                val detail = element.str("detail").ifBlank { element.str("description") }.ifBlank { element.str("prompt") }
                if (title.isBlank() && detail.isBlank()) null
                else SubagentTaskSpec((title.ifBlank { detail }).take(80), detail.ifBlank { title })
            }
            is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { SubagentTaskSpec(it.take(80), it) }
            else -> null
        }
    }
}

/** `- 任务：标题｜说明` / `* 子任务: 标题 | 说明`；标题与说明之间允许 ｜ | —— -- 。 */
private val TASK_LINE = Regex("^\\s*[-*·]\\s*(?:子?任务|task)\\s*[：:]\\s*(.+)$", RegexOption.IGNORE_CASE)

private val TITLE_SPLIT = Regex("\\s*(?:｜|\\||——|--)\\s*")

private fun fromBulletList(text: String): List<SubagentTaskSpec> =
    text.lineSequence().mapNotNull { line ->
        val body = TASK_LINE.find(line)?.groupValues?.get(1)?.trim()?.trimEnd('。', '.', ' ') ?: return@mapNotNull null
        if (body.isEmpty()) return@mapNotNull null
        val parts = body.split(TITLE_SPLIT, limit = 2)
        val title = parts[0].trim().take(80)
        if (title.isEmpty()) null
        else SubagentTaskSpec(title, parts.getOrNull(1)?.trim().orEmpty().ifBlank { title })
    }.toList()

/** 宽松取字符串：字段缺失 / 非字符串一律空串。 */
private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
