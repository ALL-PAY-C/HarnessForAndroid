package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.pluginapi.DrawerEntry
import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId

const val DRAWER_ENTRY_ID_PREFIX = "drawer-entry:"

/**
 * 原生 DrawerEntry 的桥接 id，用**长度前缀**编码，对任意 (pluginId, entryId) 单射：
 *   `drawer-entry:<len(pluginId)>:<pluginId><len(entryId)>:<entryId>`
 * 长度字段显式划界，所以 pluginId / entryId 里即使含冒号也不会产生歧义
 * （(pluginId="a:b", entryId="c") 与 (pluginId="a", entryId="b:c") 的 id 必然不同）。
 * 原生 [DrawerEntry.id] 保持不变。
 */
fun drawerEntryBridgeId(pluginId: String, entryId: String): String =
    DRAWER_ENTRY_ID_PREFIX + pluginId.length + ":" + pluginId + entryId.length + ":" + entryId

/** 原生 [DrawerEntry] → [SlotContribution]：源槽位固定 [SlotId.Drawer]，id 取给定桥接 id。 */
fun DrawerEntry.toSlotContribution(bridgeId: String = drawerEntryBridgeId(pluginId, id)): SlotContribution =
    SlotContribution(
        slot = SlotId.Drawer,
        id = bridgeId,
        label = label,
        subtitle = subtitle,
        icon = null,
        pluginId = pluginId,
        action = null,
        content = content,
        ui = null,
    )

/**
 * 唯一事实源：给每个原生 DrawerEntry 算桥接 id，并对声明式占用做**真实消解**——
 * 若某声明式贡献已经占了 `(pluginId, 桥接id)`，就确定性追加 `~1`、`~2`… 直到不再冲突。
 * 返回 (桥接id, 原生DrawerEntry) 对，顺序与 [PluginRegistry.drawerEntries] 一致。
 * [PluginRegistry.bridgedCandidates] 与 [PluginRegistry.drawerEntryResolver] 都从这里派生，
 * 保证候选池与解析器对同一注册表状态算出同一套 id。
 */
private fun bridgedIdAndEntries(registry: PluginRegistry): List<Pair<String, DrawerEntry>> {
    val used = registry.contributions.map { it.pluginId to it.id }.toMutableSet()
    val result = mutableListOf<Pair<String, DrawerEntry>>()
    for (entry in registry.drawerEntries) {
        val base = drawerEntryBridgeId(entry.pluginId, entry.id)
        var cid = base
        var n = 0
        while ((entry.pluginId to cid) in used) {
            n++
            cid = base + "~" + n
        }
        used += entry.pluginId to cid
        result += cid to entry
    }
    return result
}

/** 候选池 = 声明式槽位贡献 + 碰撞消解后的原生 DrawerEntry 桥接项。 */
fun PluginRegistry.bridgedCandidates(): List<SlotContribution> {
    val bridged = bridgedIdAndEntries(this)
    return contributions + bridged.map { (cid, entry) -> entry.toSlotContribution(cid) }
}

/**
 * 桥接解析器：与 [bridgedCandidates] 共用同一 [bridgedIdAndEntries] 映射，判别与渲染两端一致。
 *
 * [resolve] 校验「id 命中 + pluginId 一致 + slot==Drawer」三重身份，**绝不只按 id 反查**：
 *  - 声明式刻意伪造完整桥接 id：同 owner 会触发碰撞消解（桥接项拿到 ~n）；异 owner 会被
 *    pluginId 校验拒掉；
 *  - 冒号歧义由长度前缀编码消除；
 *  - [dev.dsh.mobile.pluginhost.SlotPlacements.select] 会把候选 `.copy(slot = target)`，桥接项
 *    只允许 target=Drawer，所以选中后 `slot` 仍是 Drawer，本校验在摆放前后都成立。
 */
class DrawerEntryResolver private constructor(
    private val byBridgeId: Map<String, DrawerEntry>,
) {
    fun isBridged(c: SlotContribution): Boolean = resolve(c) != null

    fun resolve(c: SlotContribution): DrawerEntry? {
        val entry = byBridgeId[c.id] ?: return null
        return if (entry.pluginId == c.pluginId && c.slot == SlotId.Drawer) entry else null
    }

    companion object {
        fun of(registry: PluginRegistry): DrawerEntryResolver =
            DrawerEntryResolver(bridgedIdAndEntries(registry).toMap())
    }
}

fun PluginRegistry.drawerEntryResolver(): DrawerEntryResolver = DrawerEntryResolver.of(this)
