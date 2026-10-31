package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.common.AppLog
import java.io.File

/** 一份落盘输出：路径、总行数、总字节——三者构成回读 locator。 */
data class SpillLocator(val path: String, val totalLines: Int, val totalBytes: Long)

/**
 * 大工具输出落盘（对齐桌面 dsh-spill：溢出 → 落盘 → 返回可检索定位符 → 按需读回）。
 *
 * 以前 ToolExecutor 直接把超长输出截成 6000 字符扔进模型上下文，剩下的永远拿不回来；
 * 读大文件/长日志/构建输出一踩就丢。现在：
 * - 不超过 [threshold] 的输出原样返回；
 * - 超了写到 `tools-output/<turnId>/<callId>.txt`，正文换成「头 [headLines] 行 + 一行 locator + 尾 [tailLines] 行」；
 * - 模型用 read_output 工具按 offset/limit 读回全文。
 *
 * ponytail: 落盘目录由宿主注入（AppContainer 传 filesDir/tools-output），拿不到目录就退回纯截断，
 * 不因为写盘失败把整轮对话炸掉。
 */
class ToolOutputSpill(
    private val rootProvider: () -> File? = { null },
    private val threshold: Int = 6_000,
    private val headLines: Int = 40,
    private val tailLines: Int = 40,
) {
    /** 是否真的能落盘：拿不到私有目录时上层退回截断路径。 */
    fun enabled(): Boolean = rootDir() != null

    /** 落盘根目录（不存在就建）。取不到返回 null。 */
    fun rootDir(): File? = runCatching {
        val dir = rootProvider() ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        dir
    }.getOrElse {
        AppLog.w("tools-output 目录不可用：" + it.message)
        null
    }

    /**
     * 主入口：超阈值就落盘并返回「头 + locator + 尾」，否则原样返回。
     * [turnId] 为空时落到 `tools-output/_noturn/`（子代理/无回合场景）。
     */
    fun present(body: String, turnId: String?, callId: String): String {
        if (body.length <= threshold) return body
        val locator = spill(body, turnId, callId) ?: return body.truncateForTool(threshold)
        val lines = body.split('\n')
        val head = lines.take(headLines)
        val tail = if (lines.size > headLines + tailLines) lines.takeLast(tailLines) else emptyList()
        val droppedHead = if (tail.isEmpty()) lines.size - head.size else lines.size - head.size - tail.size
        return buildString {
            append(head.joinToString("\n"))
            if (droppedHead > 0) append("\n…（中间省略 " + droppedHead + " 行）")
            if (tail.isNotEmpty()) append("\n").append(tail.joinToString("\n"))
            append("\n")
            append(locatorLine(locator))
        }
    }

    /** 写盘：同一 (turnId, callId) 重复写会覆盖，重放同一调用不会堆文件。 */
    fun spill(body: String, turnId: String?, callId: String): SpillLocator? {
        val root = rootDir() ?: return null
        val turn = sanitize(turnId?.takeIf { it.isNotBlank() } ?: "_noturn")
        val call = sanitize(callId.ifBlank { "call" })
        return runCatching {
            val dir = File(root, turn).apply { if (!isDirectory) mkdirs() }
            val file = File(dir, call + ".txt")
            file.writeText(body, Charsets.UTF_8)
            SpillLocator(file.absolutePath, body.count { it == '\n' } + 1, file.length())
        }.getOrElse {
            AppLog.w("工具输出落盘失败：" + it.message)
            null
        }
    }

    /**
     * read_output 的实现：按行 offset/limit 读回落盘文件。
     * 只允许读 [rootDir] 之下的文件——locator 是模型给的，不能变成任意文件读取。
     */
    fun read(path: String, offset: Int = 0, limit: Int = 200, withHeader: Boolean = true): String {
        val root = rootDir() ?: return "工具输出落盘目录不可用，无法回读：" + path
        val file = File(path)
        val target = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        val base = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        if (!target.path.startsWith(base.path + File.separator) && target.path != base.path) {
            return "拒绝回读：「" + path + "」不在工具输出目录内"
        }
        if (!file.isFile) return "文件不存在：" + path
        val all = runCatching { file.readLines() }.getOrElse { return "读取失败：" + it.message }
        val start = offset.coerceIn(0, all.size)
        val take = limit.coerceIn(1, 5_000)
        val slice = all.drop(start).take(take)
        val header = if (withHeader) {
            // 越界（offset 超过总行数）报 0-0，而不是把 offset 当成已返回区间。
            val from = if (slice.isEmpty()) 0 else start + 1
            val to = if (slice.isEmpty()) 0 else start + slice.size
            "（" + file.absolutePath + " 共 " + all.size + " 行 / " + file.length() + " 字节，本次返回第 " + from + "-" + to + " 行）\n"
        } else ""
        return header + slice.joinToString("\n")
    }

    private fun locatorLine(locator: SpillLocator): String =
        "[工具输出已落盘] path=" + locator.path + " totalLines=" + locator.totalLines +
            " totalBytes=" + locator.totalBytes +
            " —— 读回：read_output {\"path\":\"" + locator.path + "\",\"offset\":0,\"limit\":200}"

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64).ifBlank { "_" }
}
