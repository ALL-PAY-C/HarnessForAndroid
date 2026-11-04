package dev.dsh.mobile.core.model.context

import kotlinx.serialization.Serializable

/**
 * 温层来源：LOCAL = 本机压缩生成；PLUGIN = 插件检查点（<compacted-summary>）解析而来。
 * [name] 就是落盘值，改文案不改 id，存量行不会失效。
 */
@Serializable
enum class WarmSource {
    LOCAL,
    PLUGIN,
}

/** PINNED.keyFiles 一项：路径 + 行号 + 备注。行号 0 表示整文件。 */
@Serializable
data class KeyFile(
    val path: String = "",
    val line: Int = 0,
    val note: String = "",
) {
    /** 规范化 key：路径小写、行号归一 —— 合并去重按它比，不按原文比。 */
    fun key(): String = path.trim().lowercase() + ":" + line.coerceAtLeast(0)

    /** 渲染成 `path:line`（行号 0 只给路径），有备注时接在后面。 */
    fun render(): String {
        val head = if (line > 0) path.trim() + ":" + line else path.trim()
        return if (note.isBlank()) head else head + " —— " + note.trim()
    }
}

/** LOCKED.pitfalls 一项：症状 / 原因 / 修法。合并按 symptom 前 40 字去重。 */
@Serializable
data class Pitfall(
    val symptom: String = "",
    val cause: String = "",
    val fix: String = "",
) {
    fun key(): String = symptom.trim().take(40).lowercase()

    fun render(): String {
        val head = symptom.trim()
        val tail = listOf(
            cause.trim().takeIf { it.isNotEmpty() }?.let { "原因：" + it },
            fix.trim().takeIf { it.isNotEmpty() }?.let { "修法：" + it },
        ).filterNotNull()
        return if (tail.isEmpty()) head else head + "（" + tail.joinToString("；") + "）"
    }
}

/** LOCKED.rejectedOptions 与 PINNED.decisions 共用一条：结论 + 理由。 */
@Serializable
data class WarmNote(
    val text: String = "",
    val reason: String = "",
) {
    fun key(): String = text.trim().lowercase()

    fun render(): String =
        if (reason.isBlank()) text.trim() else text.trim() + "（理由：" + reason.trim() + "）"
}

/**
 * 伪无限上下文的温层（T9.8 S1 只做数据层）。
 *
 * 三级保护写在字段分组里，不写进类型：LOCKED（primaryGoal / hardConstraints /
 * rejectedOptions / pitfalls）永不被后续压缩覆盖，只允许显式 supersede；PINNED
 * （keyFiles / decisions）可折叠不可删；SOFT（openTodos / currentWork / nextStep）
 * 直接以新值替换。合并语义留给 S2 的 merge，本文件只有纯渲染与判空。
 *
 * 住在 core:model：core:protocol 要注入它、core:data 要落库、design 可能展示它，
 * 三方都 api 依赖 core:model。
 *
 * [coveredUntilMessageId] 是去重水位（messages.rowid，0 = 还没覆盖任何消息）：
 * 本次 dropped 的最大 rowid ≤ 它时压缩直接返回 null（S3 使用）。
 * 全部字段带默认值 —— 老数据、缺字段的 JSON 都能解出来。
 */
@Serializable
data class WarmLayer(
    // LOCKED
    val primaryGoal: String = "",
    val hardConstraints: List<String> = emptyList(),
    val rejectedOptions: List<WarmNote> = emptyList(),
    val pitfalls: List<Pitfall> = emptyList(),
    // PINNED
    val keyFiles: List<KeyFile> = emptyList(),
    val decisions: List<WarmNote> = emptyList(),
    // SOFT
    val openTodos: List<String> = emptyList(),
    val currentWork: String = "",
    val nextStep: String = "",
    // 元数据
    val coveredUntilMessageId: Long = 0L,
    val warmVersion: Int = 1,
    val source: WarmSource = WarmSource.LOCAL,
    val updatedAt: Long = 0L,
) {
    /** 没有任何内容：只有水位 / 版本 / 时间戳不算有温层。 */
    fun isEmpty(): Boolean =
        primaryGoal.isBlank() &&
            hardConstraints.none { it.isNotBlank() } &&
            rejectedOptions.none { it.text.isNotBlank() } &&
            pitfalls.none { it.symptom.isNotBlank() || it.fix.isNotBlank() } &&
            keyFiles.none { it.path.isNotBlank() } &&
            decisions.none { it.text.isNotBlank() } &&
            openTodos.none { it.isNotBlank() } &&
            currentWork.isBlank() &&
            nextStep.isBlank()

    /**
     * 渲染成提示词块：LOCKED → PINNED → SOFT，空段整段跳过（不留「硬约束：」空标题）。
     * 纯函数、无 IO、不读时钟，所以能在 JVM 单测里逐字断言顺序。
     */
    fun render(): String {
        if (isEmpty()) return ""
        val out = ArrayList<String>(12)
        out += "## 温层"
        if (primaryGoal.isNotBlank()) out += "主要目标：" + primaryGoal.trim()
        bullets("硬约束", hardConstraints)?.let { out += it }
        notes("已否决", rejectedOptions)?.let { out += it }
        pitfallLines(pitfalls)?.let { out += it }
        keyFileLines(keyFiles)?.let { out += it }
        notes("决策", decisions)?.let { out += it }
        bullets("未完成", openTodos)?.let { out += it }
        if (currentWork.isNotBlank()) out += "当前工作：" + currentWork.trim()
        if (nextStep.isNotBlank()) out += "下一步：" + nextStep.trim()
        return out.joinToString("\n")
    }

    private fun bullets(title: String, items: List<String>): String? {
        val rows = items.map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null
        return title + "：\n" + rows.joinToString("\n") { "- " + it }
    }

    private fun notes(title: String, items: List<WarmNote>): String? {
        val rows = items.filter { it.text.isNotBlank() }.map { it.render() }
        if (rows.isEmpty()) return null
        return title + "：\n" + rows.joinToString("\n") { "- " + it }
    }

    private fun pitfallLines(items: List<Pitfall>): String? {
        val rows = items.filter { it.symptom.isNotBlank() || it.fix.isNotBlank() }.map { it.render() }
        if (rows.isEmpty()) return null
        return "踩坑：\n" + rows.joinToString("\n") { "- " + it }
    }

    private fun keyFileLines(items: List<KeyFile>): String? {
        val rows = items.filter { it.path.isNotBlank() }.map { it.render() }
        if (rows.isEmpty()) return null
        return "关键文件：\n" + rows.joinToString("\n") { "- " + it }
    }

    companion object {
        /** 空温层（懒回填用）：无内容、水位 0、来源 LOCAL。 */
        val EMPTY: WarmLayer = WarmLayer()
    }
}
