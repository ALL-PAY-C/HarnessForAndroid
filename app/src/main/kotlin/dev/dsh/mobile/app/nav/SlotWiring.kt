package dev.dsh.mobile.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.app.AppContainer
import dev.dsh.mobile.design.SlotEditItem
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId

/**
 * 一个槽位在某个界面上的完整接线：渲染什么、还能加什么、加/删怎么落盘。
 *
 * 界面本身不认识 SlotPlacementStore，只拿到这份数据 + 两个回调，AppNav 统一负责
 * 「属于哪个槽位」这件事——同一条候选出现在别的界面时也不会串槽。
 */
class SlotWiring(
    val placed: List<SlotContribution>,
    val candidates: List<SlotEditItem>,
    val add: (SlotEditItem) -> Unit,
    val remove: (SlotEditItem) -> Unit,
)

/**
 * 按宿主 revision + 摆放 revision 取某个槽位的已放置项与候选。
 *
 * 装/卸插件（pluginHost.revision）和用户摆槽位（store.revision）都会让这份数据失效，
 * 两个 revision 都进 remember 的 key，界面因此立刻跟着变。
 */
@Composable
fun rememberSlotWiring(container: AppContainer, target: SlotId): SlotWiring {
    val context = LocalContext.current
    val pluginRevision by container.pluginHost.revision.collectAsStateWithLifecycle()
    val store = remember(context) { container.pluginHost.placements(context) }
    val placementRevision = store.revision
    val placed = remember(context, target, pluginRevision, placementRevision) {
        container.pluginHost.visibleSlots(context, target)
    }
    val candidates = remember(context, target, pluginRevision, placementRevision) {
        container.pluginHost.availableFor(context, target)
            .map { SlotEditItem(it.pluginId, it.id, it.label, it.subtitle) }
    }
    return SlotWiring(
        placed = placed,
        candidates = candidates,
        add = { item ->
            // source 必须写它原本声明的槽位：select() 用 (pluginId, id, source) 三元组回查，
            // 写成 target 的话摆进去也渲染不出来。
            val contribution = container.pluginHost.availableFor(context, target)
                .firstOrNull { it.pluginId == item.pluginId && it.id == item.contributionId }
            if (contribution != null) {
                store.add(contribution.pluginId, contribution.id, contribution.slot, target)
            }
        },
        remove = { item -> store.remove(item.pluginId, item.contributionId, target) },
    )
}
