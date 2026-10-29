package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId
import org.json.JSONArray
import org.json.JSONObject

data class SlotPlacement(val pluginId: String, val contributionId: String, val source: SlotId, val target: SlotId)

internal object SlotPlacements {
    fun decode(raw: String): List<SlotPlacement> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val source = SlotId.of(item.optString("source")) ?: return@mapNotNull null
            val target = SlotId.of(item.optString("target")) ?: return@mapNotNull null
            SlotPlacement(item.getString("plugin"), item.getString("component"), source, target)
        }.distinct()
    }.getOrDefault(emptyList())

    fun encode(items: List<SlotPlacement>): String = JSONArray(items.map { item ->
        JSONObject().put("plugin", item.pluginId).put("component", item.contributionId)
            .put("source", item.source.wire).put("target", item.target.wire)
    }).toString()

    fun select(items: List<SlotPlacement>, target: SlotId, candidates: List<SlotContribution>): List<SlotContribution> =
        items.filter { it.target == target }.mapNotNull { placement ->
            candidates.firstOrNull {
                it.pluginId == placement.pluginId && it.id == placement.contributionId && it.slot == placement.source
            }?.copy(slot = target)
        }

    /**
     * 在同一目标槽位内把某条摆放上移/下移一位（相邻交换），其它槽位的摆放保持原位不动。
     *
     * 以前的实现按「全局列表索引 + delta」挪，导致抽屉里混着 ComposerPlus 的条目时，
     * 上/下会跨槽位串位。这里先把目标槽位自己的条目按原顺序抽出来，只在它们内部移动，
     * 再按原索引写回，保证其它槽位的相对顺序完全不变。
     */
    fun move(items: List<SlotPlacement>, pluginId: String, contributionId: String, target: SlotId, delta: Int): List<SlotPlacement> {
        val result = items.toMutableList()
        val targetIndices = result.indices.filter { result[it].target == target }
        val from = targetIndices.indexOfFirst { result[it].pluginId == pluginId && result[it].contributionId == contributionId }
        if (from < 0) return items
        val to = (from + delta).coerceIn(0, targetIndices.size - 1)
        if (from == to) return items
        val moved = targetIndices.map { result[it] }.toMutableList()
        val item = moved.removeAt(from)
        moved.add(to, item)
        targetIndices.forEachIndexed { i, idx -> result[idx] = moved[i] }
        return result
    }
}
