package dev.dsh.mobile.pluginhost

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId

class SlotPlacementStore(context: Context) {
    private val prefs = context.getSharedPreferences("dsh.plugin.placements", Context.MODE_PRIVATE)
    private val key = "items"
    private val revisionState = mutableIntStateOf(0)
    val revision: Int get() = revisionState.intValue

    fun all(): List<SlotPlacement> = SlotPlacements.decode(prefs.getString(key, "[]").orEmpty())
    fun available(candidates: List<SlotContribution>): List<SlotContribution> {
        val existing = all().map { it.pluginId to it.contributionId }.toSet()
        return candidates.filter { it.slot != SlotId.PluginPage && (it.pluginId to it.id) !in existing }
    }
    fun add(pluginId: String, contributionId: String, source: SlotId, target: SlotId) {
        val item = SlotPlacement(pluginId, contributionId, source, target)
        val current = all()
        if (item !in current) save(current + item)
    }
    fun remove(pluginId: String, contributionId: String, target: SlotId) =
        save(all().filterNot { it.pluginId == pluginId && it.contributionId == contributionId && it.target == target })
    fun move(pluginId: String, contributionId: String, target: SlotId, delta: Int) {
        val current = all()
        val moved = SlotPlacements.move(current, pluginId, contributionId, target, delta)
        if (moved != current) save(moved)
    }
    fun removePlugin(pluginId: String) = save(all().filterNot { it.pluginId == pluginId })
    fun visible(target: SlotId, candidates: List<SlotContribution>): List<SlotContribution> =
        SlotPlacements.select(all(), target, candidates)
    private fun save(items: List<SlotPlacement>) {
        prefs.edit().putString(key, SlotPlacements.encode(items)).apply()
        revisionState.intValue++
    }
}
