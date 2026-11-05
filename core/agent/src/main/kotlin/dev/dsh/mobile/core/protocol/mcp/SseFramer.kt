package dev.dsh.mobile.core.protocol.mcp

/** 一条 SSE 事件。[event] 缺省是 "message"。 */
data class SseEvent(val event: String, val data: String, val id: String? = null)

/** [rest] 是还没凑成完整事件、必须拼到下一个 chunk 前面的尾巴。 */
data class SseFrameResult(val events: List<SseEvent>, val rest: String)

/**
 * SSE 分帧（纯函数）。
 *
 * 只做文本切分，不碰网络也不碰 JSON：这样跨 chunk 截断、CRLF、注释行这些
 * 最容易出错的地方可以在 JVM 单测里直接打。
 */
object SseFramer {

    fun frame(buffer: String): SseFrameResult {
        val events = mutableListOf<SseEvent>()
        var pos = 0
        var type = "message"
        var id: String? = null
        val data = mutableListOf<String>()
        var hasData = false

        while (true) {
            val (line, next) = readLine(buffer, pos) ?: break
            pos = next
            if (line.isEmpty()) {
                // 空行 = 事件边界；没有 data 字段的事件（只有 id/retry）按规范不派发。
                if (hasData) {
                    events += SseEvent(type, data.joinToString("\n"), id)
                    type = "message"
                    id = null
                    data.clear()
                    hasData = false
                }
                continue
            }
            if (line.startsWith(":")) continue // 注释行（心跳）
            val colon = line.indexOf(':')
            val field = if (colon >= 0) line.substring(0, colon) else line
            var value = if (colon >= 0) line.substring(colon + 1) else ""
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> type = value
                "id" -> id = value
                "data" -> {
                    data += value
                    hasData = true
                }
                // retry 与未知字段：P2 用不上，忽略即可。
                else -> Unit
            }
        }
        return SseFrameResult(events, buffer.substring(pos))
    }

    /**
     * 读一行并返回「行内容 + 下一行起点」；行尾不完整（末尾孤立 \r 可能是 \r\n 的前半）
     * 就返回 null，让调用方把这段留给下一个 chunk。
     */
    private fun readLine(buffer: String, from: Int): Pair<String, Int>? {
        var i = from
        while (i < buffer.length) {
            when (buffer[i]) {
                '\n' -> return buffer.substring(from, i) to i + 1
                '\r' -> {
                    if (i + 1 >= buffer.length) return null
                    val next = if (buffer[i + 1] == '\n') i + 2 else i + 1
                    return buffer.substring(from, i) to next
                }
                else -> i++
            }
        }
        return null
    }
}
