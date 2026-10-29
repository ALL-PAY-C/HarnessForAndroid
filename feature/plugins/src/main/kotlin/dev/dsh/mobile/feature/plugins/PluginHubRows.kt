package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.pluginapi.SlotContribution

/**
 * 插件中心列表的拼装逻辑。刻意放在独立文件、且不 import 任何 Compose 类型：
 * 这样纯 JVM 单测能只加载这几个函数，不用把 Compose/Android 运行时拖进 classpath。
 */

/** 列表里的一行：要么指向 [pageKeys] 的一页，要么指向 [slotKeys] 的一条贡献。 */
internal data class HubRow(val key: String, val pageIndex: Int, val slotIndex: Int)

/**
 * 配置页在前、槽位页在后；各自按 key 去重，重复 id 只留第一次出现的位置。
 * 顺序必须稳定——插件每次热加载都会重算这张表，抖动会让列表跳。
 */
internal fun mergeHubRows(pageKeys: List<String>, slotKeys: List<String>): List<HubRow> {
    val seen = HashSet<String>(pageKeys.size + slotKeys.size)
    val out = ArrayList<HubRow>(pageKeys.size + slotKeys.size)
    for (i in pageKeys.indices) {
        val key = "page:" + pageKeys[i]
        if (seen.add(key)) out += HubRow(key, i, -1)
    }
    for (i in slotKeys.indices) {
        val key = "slot:" + slotKeys[i]
        if (seen.add(key)) out += HubRow(key, -1, i)
    }
    return out
}

/**
 * 槽位行的 key。同一个插件常在多个槽位里用同一个 id（比如既放 PluginPage 又放
 * Settings），只按 id 去重会把它们压成一行，所以带上槽位线名。
 */
internal fun slotRowKey(contribution: SlotContribution): String =
    contribution.slot.wire + ":" + contribution.id

/**
 * 把几个槽位的贡献并成一张列表，调用方按「越靠前越先显示」的顺序传入。
 *
 * 只拼接、不去重：去重交给 [mergeHubRows]（key 带槽位线名），因为 Drawer /
 * Settings / SessionRow 的既有入口是 registry.xxxEntries，这里那份是**额外**
 * 多出来的，两边并存、互不顶掉。
 *
 * 公开（非 internal）是因为 :app 的调用点要自己拼这张表；其余拼装函数只在
 * 本模块用，仍然是 internal。
 */
fun mergeHubSlots(vararg lists: List<SlotContribution>): List<SlotContribution> =
    lists.flatMap { it }
