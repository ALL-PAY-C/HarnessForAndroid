package dev.dsh.mobile.core.protocol.context

import dev.dsh.mobile.core.model.context.WarmLayer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 温层装配（T9.8 S3）：把温层渲染成一段可注入的 system 文本，并识别「插件已经压过历史」。
 *
 * 两条硬约束：
 * 1) 温层为空时 [insert] 原样返回入参（同一个 list 引用），提示词逐字节不变 —— 否则等于
 *    把所有会话的提示词都改了；
 * 2) 温层块用插件同样的 <compacted-summary> 包裹：插件与本地的压缩产物在历史里长得一样，
 *    下一轮 [pluginCheckpoint] 能认出来。认出自己写的块靠首行 "## 温层"（见 [isOwnBlock]）。
 */
internal object WarmAssembly {
    /** 压缩块的开口标记（与插件共用同一种包裹）。 */
    const val WARM_OPEN = "<compacted-summary>"

    /** 压缩块的闭合标记。 */
    const val WARM_CLOSE = "</compacted-summary>"

    /** 插件写进正文里的压缩断点标记。 */
    const val PLUGIN_MARKER = "compact-checkpoint"

    /** 装配前只扫最近这么多条消息：更早的压缩标记已经被后续轮次淹没了。 */
    const val SCAN_WINDOW = 20

    /** 自己写的温层块的首行（[WarmLayer.render] 固定输出）。 */
    private const val WARM_TITLE = "## 温层"

    /** 温层 → 注入文本；空层（null 或 [WarmLayer.isEmpty]）返回 null = 不注入。 */
    fun block(layer: WarmLayer?): String? {
        if (layer == null || layer.isEmpty()) return null
        return WARM_OPEN + "\n" + layer.render() + "\n" + WARM_CLOSE
    }

    /**
     * 把温层块插进消息序列：首条是 system 就插在 index 1（system 之后），否则插在 index 0。
     * [warmText] 为 null/空白时直接返回入参本身，不复制、不改动。
     */
    fun insert(messages: List<JsonObject>, warmText: String?): List<JsonObject> {
        if (warmText.isNullOrBlank()) return messages
        val at = if (roleOf(messages.firstOrNull()) == "system") 1 else 0
        val out = ArrayList<JsonObject>(messages.size + 1)
        out.addAll(messages.subList(0, at))
        out += buildJsonObject {
            put("role", "system")
            put("content", warmText)
        }
        out.addAll(messages.subList(at, messages.size))
        return out
    }

    /**
     * 最近 [SCAN_WINDOW] 条里最新的插件压缩标记正文；没有返回 null。
     *
     * 命中条件：正文含 [WARM_OPEN] 或 [PLUGIN_MARKER]，且不是自己写的温层块。
     * 从新往旧找：最新的 checkpoint 覆盖的历史最多。
     */
    fun pluginCheckpoint(messages: List<JsonObject>): String? {
        for (m in messages.takeLast(SCAN_WINDOW).asReversed()) {
            val text = (m["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (text.isBlank() || isOwnBlock(text)) continue
            if (text.contains(WARM_OPEN) || text.contains(PLUGIN_MARKER)) return text
        }
        return null
    }

    private fun isOwnBlock(text: String): Boolean =
        text.trimStart().startsWith(WARM_OPEN) && text.contains(WARM_TITLE)

    private fun roleOf(m: JsonObject?): String? =
        (m?.get("role") as? JsonPrimitive)?.contentOrNull
}
