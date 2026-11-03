package dev.dsh.mobile.toolbridge.phone

import org.json.JSONObject

/**
 * wait_for 的等待条件。四种都由读侧已有能力判定，不需要新的系统 API：
 * 选择器出现/消失看节点集，文本出现看 text/contentDesc，代次变化看 PhoneTreeGenerations。
 */
sealed interface PhoneWaitCond {
    data class NodePresent(val selector: PhoneSelector) : PhoneWaitCond

    data class NodeAbsent(val selector: PhoneSelector) : PhoneWaitCond

    data class TextPresent(val textContains: String) : PhoneWaitCond

    data class PackageIs(val packageName: String) : PhoneWaitCond

    data object GenerationChanged : PhoneWaitCond
}

data class PhoneWaitRequest(val cond: PhoneWaitCond, val timeoutMs: Long, val pollMs: Long)

/**
 * wait_for 的参数解析与条件判定。
 *
 * 判定是纯函数（节点集 + 前台包名 + 代次进来，布尔出去），所以「条件到底怎么算满足」
 * 能在 JVM 单测里逐条验；真正的等待循环与超时在 PhoneBridge 里。
 */
object PhoneWait {
    const val MIN_TIMEOUT_MS = 100L
    const val DEFAULT_TIMEOUT_MS = 3_000L
    const val MAX_TIMEOUT_MS = 15_000L

    const val MIN_POLL_MS = 100L
    const val DEFAULT_POLL_MS = 250L
    const val MAX_POLL_MS = 2_000L

    /**
     * timeoutMs/pollMs 挂在 action 顶层（与 cond 平级），超范围直接夹到边界。
     * cond 缺 type 或 type 不认识就回 null —— 动作侧据此回 E_BAD_ARGS，不猜意图。
     */
    fun parse(obj: JSONObject): PhoneWaitRequest? {
        val raw = obj.opt("cond") ?: return null
        if (raw !is JSONObject) return null
        val cond = parseCond(raw) ?: return null
        val timeout = obj.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val poll = obj.optLong("pollMs", DEFAULT_POLL_MS).coerceIn(MIN_POLL_MS, MAX_POLL_MS)
        return PhoneWaitRequest(cond, timeout, poll)
    }

    /** cond 的单一解析入口；type 别名兼容 tree_changed（等同 generation_changed）。 */
    fun parseCond(cond: JSONObject): PhoneWaitCond? {
        val type = cond.optString("type", "").trim()
        return when (type) {
            "node_present", "node_absent" -> {
                val raw = cond.opt("selector")
                if (raw !is JSONObject) return null
                val selector = PhoneSelector.parse(raw) ?: return null
                if (selector.isEmpty) return null
                if (type == "node_present") PhoneWaitCond.NodePresent(selector) else PhoneWaitCond.NodeAbsent(selector)
            }
            "text_present" -> {
                val text = cond.optString("textContains", cond.optString("text", "")).trim()
                if (text.isEmpty()) return null
                PhoneWaitCond.TextPresent(text)
            }
            "package_is" -> {
                val pkg = cond.optString("package", "").trim()
                if (pkg.isEmpty()) return null
                PhoneWaitCond.PackageIs(pkg)
            }
            "generation_changed", "tree_changed" -> PhoneWaitCond.GenerationChanged
            else -> null
        }
    }

    /**
     * 条件是否已满足。[sinceGeneration] 是开始等待那一刻的代次，只有 GenerationChanged 用得到。
     * 文本匹配不分大小写：模型手里往往是小写关键词。
     */
    fun satisfied(
        cond: PhoneWaitCond,
        nodes: List<PhoneNode>,
        foregroundPackage: String?,
        generation: Long,
        sinceGeneration: Long,
    ): Boolean = when (cond) {
        is PhoneWaitCond.NodePresent -> cond.selector.select(nodes).isNotEmpty()
        is PhoneWaitCond.NodeAbsent -> cond.selector.select(nodes).isEmpty()
        is PhoneWaitCond.TextPresent -> nodes.any {
            it.text.contains(cond.textContains, ignoreCase = true) ||
                it.contentDesc.contains(cond.textContains, ignoreCase = true)
        }
        is PhoneWaitCond.PackageIs -> foregroundPackage == cond.packageName
        PhoneWaitCond.GenerationChanged -> generation != sinceGeneration
    }

    /** 满足条件时命中的第一个节点（给 matchedNodeId），没有就 null。 */
    fun matchNode(cond: PhoneWaitCond, nodes: List<PhoneNode>): PhoneNode? = when (cond) {
        is PhoneWaitCond.NodePresent -> cond.selector.first(nodes)
        is PhoneWaitCond.TextPresent -> nodes.firstOrNull {
            it.text.contains(cond.textContains, ignoreCase = true) ||
                it.contentDesc.contains(cond.textContains, ignoreCase = true)
        }
        else -> null
    }

    /** 超时文案里的条件摘要，别把整坨 JSON 丢给模型。 */
    fun describe(cond: PhoneWaitCond): String = when (cond) {
        is PhoneWaitCond.NodePresent -> "节点出现 " + describeSelector(cond.selector)
        is PhoneWaitCond.NodeAbsent -> "节点消失 " + describeSelector(cond.selector)
        is PhoneWaitCond.TextPresent -> "文本出现「" + cond.textContains + "」"
        is PhoneWaitCond.PackageIs -> "前台切到 " + cond.packageName
        PhoneWaitCond.GenerationChanged -> "界面发生变化"
    }

    private fun describeSelector(selector: PhoneSelector): String {
        val parts = ArrayList<String>(4)
        if (selector.text != null) parts.add("text=" + selector.text)
        if (selector.textContains != null) parts.add("textContains=" + selector.textContains)
        if (selector.desc != null) parts.add("desc=" + selector.desc)
        if (selector.viewId != null) parts.add("viewId=" + selector.viewId)
        if (selector.viewIdSuffix != null) parts.add("viewIdSuffix=" + selector.viewIdSuffix)
        if (selector.className != null) parts.add("className=" + selector.className)
        if (selector.packageName != null) parts.add("package=" + selector.packageName)
        if (selector.index != null) parts.add("index=" + selector.index)
        if (parts.isEmpty()) parts.add("(空选择器)")
        return parts.joinToString(",")
    }
}
