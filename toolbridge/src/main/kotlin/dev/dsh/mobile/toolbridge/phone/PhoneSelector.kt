package dev.dsh.mobile.toolbridge.phone

import org.json.JSONObject

/**
 * 「找节点」的唯一入口。
 *
 * 旧路径只能按 text/contentDescription 做 includes 匹配（PhoneBridge.tapByQuery），表达不了
 * viewId、bounds、可点性这些真正能定位的东西；T4.2 的动作需要与读侧同一套判定，
 * 所以解析与匹配都收在这里，按选择器描述节点而不是回传字符串。
 *
 * 字段之间是 AND；[index] 数的是匹配结果里的第几个（遍历顺序 = 兄弟顺序）。
 */
data class PhoneSelector(
    /** text 全等。要模糊用 [textContains]。 */
    val text: String? = null,
    val textContains: String? = null,
    /** contentDescription 全等。 */
    val desc: String? = null,
    /** 完整 resource-id，如 com.android.settings:id/search。 */
    val viewId: String? = null,
    /** 只给 id 段（冒号后那截）。 */
    val viewIdSuffix: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val index: Int? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val scrollable: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = text == null && textContains == null && desc == null && viewId == null &&
            viewIdSuffix == null && className == null && packageName == null && index == null &&
            clickable == null && editable == null && scrollable == null

    fun matches(node: PhoneNode): Boolean {
        if (text != null && node.text != text) return false
        // 中文没有大小写，ignoreCase 只是让英文界面的 Text/EDIT 也能命中；要全等仍走 text。
        if (textContains != null && !node.text.contains(textContains, ignoreCase = true)) return false
        if (desc != null && node.contentDesc != desc) return false
        if (viewId != null && node.viewId != viewId) return false
        // 资源 id 形如 pkg:id/name：单取冒号后那截会得到 "id/name"，还要再剥一次斜杠。
        if (viewIdSuffix != null &&
            node.viewId.substringAfterLast(':').substringAfterLast('/') != viewIdSuffix
        ) return false
        if (className != null && node.className != className) return false
        if (packageName != null && node.packageName != packageName) return false
        if (clickable != null && node.clickable != clickable) return false
        if (editable != null && node.editable != editable) return false
        if (scrollable != null && node.scrollable != scrollable) return false
        return true
    }

    /** 命中集合：[index] 为空时返回全部，否则只返回第 index 个（越界即空）。 */
    fun select(nodes: List<PhoneNode>): List<PhoneNode> {
        val hit = if (isEmpty) nodes else nodes.filter { matches(it) }
        val i = index ?: return hit
        return listOfNotNull(hit.getOrNull(i))
    }

    /** 第一个命中节点；给 T4.2 的「单目标」动作用。 */
    fun first(nodes: List<PhoneNode>): PhoneNode? = select(nodes).firstOrNull()

    companion object {
        /** 解析 filter 对象；传 null 视为「不过滤」。字段缺失即不参与匹配。 */
        fun parse(obj: JSONObject?): PhoneSelector? {
            if (obj == null) return null
            fun str(key: String): String? =
                if (obj.has(key) && !obj.isNull(key)) obj.optString(key).takeIf { it.isNotEmpty() } else null
            fun bool(key: String): Boolean? = if (obj.has(key) && !obj.isNull(key)) obj.optBoolean(key) else null
            return PhoneSelector(
                text = str("text"),
                textContains = str("textContains"),
                desc = str("desc"),
                viewId = str("viewId"),
                viewIdSuffix = str("viewIdSuffix"),
                className = str("className"),
                packageName = str("package"),
                index = if (obj.has("index") && !obj.isNull("index")) obj.optInt("index") else null,
                clickable = bool("clickable"),
                editable = bool("editable"),
                scrollable = bool("scrollable"),
            )
        }
    }
}
