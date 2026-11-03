package dev.dsh.mobile.core.model

import kotlinx.serialization.Serializable

/**
 * 单会话身份卡：把「你是谁 / 职责 / 禁区 / 准则 / 口吻 / 示例」写成结构化字段，[render] 出提示词。
 *
 * 住在 core:model 而不是 core:data：design（UI 套件）要渲染这张卡，core:protocol 要注入它，
 * 两边都 api 依赖 core:model，放这里才不会让 UI 反向依赖数据层。
 *
 * 只有 [boundaries]（禁区，需被断言命中）与 [tone]（口吻，需被稳定引用）值得单独成字段，
 * 其余自由发挥的都塞 [freeform]，不逼用户填表。空字段不渲染、不产生空标题行。
 */
@Serializable
data class IdentityCard(
    /** 卡名（列表与渲染标题） */
    val name: String = "",
    /** 你是谁：一句话身份 */
    val identity: String = "",
    val duties: List<String> = emptyList(),
    /** 禁区：硬约束，优先级最高 */
    val boundaries: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
    val tone: String = "",
    val examples: List<String> = emptyList(),
    /** 自由文本主体，与上面字段叠加，渲染在最后 */
    val freeform: String = "",
) {
    /** 卡是不是空的：空卡视为「没有卡」，注入与展示都跳过。 */
    fun hasContent(): Boolean =
        listOf(name, identity, tone, freeform).any { it.isNotBlank() } ||
            listOf(duties, boundaries, rules, examples).any { list -> list.any { it.isNotBlank() } }

    /**
     * 渲染成提示词块。顺序固定：身份 → 职责 → 禁区 → 准则 → 口吻 → 示例 → 自由文本。
     * 纯函数、无 IO，空字段整段跳过（不留下「职责：」这种空标题）。
     */
    fun render(): String {
        val out = ArrayList<String>(8)
        out += if (name.isBlank()) "## 身份卡" else "## 身份卡：" + name.trim()
        if (identity.isNotBlank()) out += "你是：" + identity.trim()
        bullets("职责", duties)?.let { out += it }
        bullets("禁区", boundaries)?.let { out += it }
        bullets("准则", rules)?.let { out += it }
        if (tone.isNotBlank()) out += "口吻：" + tone.trim()
        bullets("示例", examples)?.let { out += it }
        if (freeform.isNotBlank()) out += freeform.trim()
        // 只有「身份卡：名字」这一行也算有内容；连名字都没有就是空卡。
        return if (out.size == 1 && name.isBlank()) "" else out.joinToString("\n")
    }

    private fun bullets(title: String, items: List<String>): String? {
        val rows = items.map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null
        return title + "：\n" + rows.joinToString("\n") { "- " + it }
    }
}
