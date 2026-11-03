package dev.dsh.mobile.toolbridge.phone

import org.json.JSONObject

/**
 * 动作的定位输入：nodeId（带快照代次）、选择器，或都没给。
 *
 * 与读侧共用 [PhoneSelector]：动作和 phone_tree 用的必须是同一套匹配判定，
 * 否则「树上看到的」和「点下去的」会不是同一个节点。
 */
sealed interface PhoneTargetSpec {
    /** 没给定位（旧参数形态：坐标 / query / 聚焦节点）。 */
    data object None : PhoneTargetSpec

    /** nodeId = "<snapshotId>:<seq>"，执行前必须比对当前代次。 */
    data class ByNodeId(val nodeId: String, val snapshotId: Long, val seq: Int) : PhoneTargetSpec

    data class BySelector(val selector: PhoneSelector) : PhoneTargetSpec

    /** 给了 target 但解不出东西（不是对象、nodeId 格式错、空选择器）。 */
    data object Invalid : PhoneTargetSpec
}

/** 定位结果。Ambiguous 是「多命中且没给 index」，调用方必须回 E_NODE_AMBIGUOUS 且不派发。 */
sealed interface PhoneResolveResult {
    data class Hit(val index: Int, val node: PhoneNode) : PhoneResolveResult

    data class Ambiguous(val count: Int, val candidates: List<PhoneNode>) : PhoneResolveResult

    data object NotFound : PhoneResolveResult

    data object StaleSnapshot : PhoneResolveResult
}

/**
 * 定位判定的唯一真源。
 *
 * 三种结局都在这里定：命中 / 歧义 / 跨代失效。纯 Kotlin（除 [parse] 吃 JSONObject），
 * 所以歧义与跨代这两条最容易写错的判定能在 JVM 单测里直接验。
 */
object PhoneTarget {

    /** 点不动时向上找可点祖先的最大层数，与旧的 tapByQuery（6 层）保持一致。 */
    const val CLICK_HOPS = 6

    /** 滚不动时向上找可滚容器的层数：列表行到 RecyclerView 常常超过 6 层。 */
    const val SCROLL_HOPS = 12

    /**
     * 解析定位参数，优先级：顶层 nodeId > target > filter > selector。
     *
     * target 是对象时，带 nodeId 就是身份定位，否则整体当选择器；target 是字符串时按 nodeId 解。
     * filter 与 phone_tree 同名同义，方便「读一次树、带 filter 复用同一序号」。
     */
    fun parse(obj: JSONObject): PhoneTargetSpec {
        val direct = obj.optString("nodeId", "").trim()
        if (direct.isNotEmpty()) return byNodeId(direct)

        val target = obj.opt("target")
        if (target != null && target != JSONObject.NULL) {
            if (target is String) return byNodeId(target.trim())
            if (target !is JSONObject) return PhoneTargetSpec.Invalid
            val inTarget = target.optString("nodeId", "").trim()
            if (inTarget.isNotEmpty()) return byNodeId(inTarget)
            val sel = PhoneSelector.parse(target) ?: return PhoneTargetSpec.Invalid
            return if (sel.isEmpty) PhoneTargetSpec.Invalid else PhoneTargetSpec.BySelector(sel)
        }

        for (key in KEY_ALIASES) {
            val raw = obj.opt(key)
            if (raw == null || raw == JSONObject.NULL) continue
            if (raw !is JSONObject) return PhoneTargetSpec.Invalid
            val sel = PhoneSelector.parse(raw) ?: return PhoneTargetSpec.Invalid
            return if (sel.isEmpty) PhoneTargetSpec.Invalid else PhoneTargetSpec.BySelector(sel)
        }
        return PhoneTargetSpec.None
    }

    /**
     * 按当前快照判定定位结果。
     *
     * nodeId 的跨代判定放在最前面：代次不符时一个节点都不查，保证「跨代不执行」。
     */
    fun resolve(spec: PhoneTargetSpec, nodes: List<PhoneNode>, currentGeneration: Long): PhoneResolveResult =
        when (spec) {
            is PhoneTargetSpec.ByNodeId -> {
                if (spec.snapshotId != currentGeneration) {
                    PhoneResolveResult.StaleSnapshot
                } else {
                    val i = nodes.indexOfFirst { it.seq == spec.seq }
                    if (i < 0) PhoneResolveResult.NotFound else PhoneResolveResult.Hit(i, nodes[i])
                }
            }
            is PhoneTargetSpec.BySelector -> {
                val hits = narrow(spec.selector, spec.selector.select(nodes), nodes)
                when {
                    hits.isEmpty() -> PhoneResolveResult.NotFound
                    hits.size > 1 -> PhoneResolveResult.Ambiguous(hits.size, hits.take(3))
                    else -> {
                        val seq = hits[0].seq
                        val i = nodes.indexOfFirst { it.seq == seq }
                        if (i < 0) PhoneResolveResult.NotFound else PhoneResolveResult.Hit(i, nodes[i])
                    }
                }
            }
            PhoneTargetSpec.None, PhoneTargetSpec.Invalid -> PhoneResolveResult.NotFound
        }

    /**
     * 多命中时的收敛：先把「同一行里的标题/副标题」折叠成一个目标，再优先 text 全等。
     *
     * 只说 textContains="Network" 时 Settings 会命中 3 个节点（行标题、行摘要、另一行的词），
     * 直接判歧义对模型是噪音 —— 折叠后只剩一个就点它；仍多个才回 E_NODE_AMBIGUOUS。
     */
    private fun narrow(sel: PhoneSelector, hits: List<PhoneNode>, nodes: List<PhoneNode>): List<PhoneNode> {
        if (hits.size <= 1) return hits
        val rows = LinkedHashMap<Int, PhoneNode>()
        for (h in hits) {
            val i = nodes.indexOfFirst { it.seq == h.seq }
            if (i < 0) continue
            val anchor = clickableAncestorIndex(nodes, i).let { if (it >= 0) it else i }
            rows.putIfAbsent(anchor, h)
        }
        if (rows.size == 1) return rows.values.toList()
        if (sel.text == null) {
            val fuzzy = sel.textContains ?: sel.desc
            if (fuzzy != null) {
                val exact = rows.values.filter { it.text.equals(fuzzy, ignoreCase = true) }
                if (exact.size == 1) return exact
            }
        }
        return rows.values.toList()
    }

    /**
     * 命中节点自身或最近可点祖先的下标；[CLICK_HOPS] 层内没有就回 -1。
     *
     * 列表行（ListView/RecyclerView 的子项）通常只有祖先才 clickable，
     * 不做这一步大量行都点不动 —— 这是旧 tapByQuery 里唯一必须保住的行为。
     */
    fun clickableAncestorIndex(nodes: List<PhoneNode>, startIndex: Int, maxHops: Int = CLICK_HOPS): Int {
        var i = startIndex
        var hops = 0
        while (i in nodes.indices && hops <= maxHops) {
            val n = nodes[i]
            if (n.clickable) return i
            i = n.parentIndex
            hops++
        }
        return -1
    }

    /** 命中节点自身或最近 scrollable=true 祖先的下标；都没有回 null（不可滚）。 */
    fun scrollableAncestorIndex(nodes: List<PhoneNode>, startIndex: Int, maxHops: Int = SCROLL_HOPS): Int? {
        var i = startIndex
        var hops = 0
        while (i in nodes.indices && hops <= maxHops) {
            val n = nodes[i]
            if (n.scrollable) return i
            i = n.parentIndex
            hops++
        }
        return null
    }

    /**
     * 全树指纹：判 scroll 是否真的滚动了（dispatched=true 不等于 advanced）。
     * 只用节点文本/描述/viewId/bounds/可点/选中，不看 seq —— 换一张表同内容也该等价。
     */
    fun treeFingerprint(nodes: List<PhoneNode>): Int {
        var h = 17
        for (n in nodes) {
            h = 31 * h + n.className.hashCode()
            h = 31 * h + n.text.hashCode()
            h = 31 * h + n.contentDesc.hashCode()
            h = 31 * h + n.viewId.hashCode()
            h = 31 * h + n.bounds.top
            h = 31 * h + n.bounds.bottom
            h = 31 * h + (if (n.clickable) 1 else 0)
            h = 31 * h + (if (n.checked) 2 else 0)
        }
        return h
    }

    private val KEY_ALIASES = listOf("filter", "selector")

    private fun byNodeId(raw: String): PhoneTargetSpec {
        val text = raw.trim()
        if (!text.contains(':')) return PhoneTargetSpec.Invalid
        val gen = text.substringBeforeLast(':').trim().toLongOrNull() ?: return PhoneTargetSpec.Invalid
        val seq = text.substringAfterLast(':').trim().toIntOrNull() ?: return PhoneTargetSpec.Invalid
        if (gen < 0L || seq < 0) return PhoneTargetSpec.Invalid
        return PhoneTargetSpec.ByNodeId(text, gen, seq)
    }
}
