package dev.dsh.mobile.pluginhost

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId

/**
 * 当前 [PluginHost]。插件 UI 的消费点要读注册表，但没有宿主参数，
 * 所以走 CompositionLocal；宿主没提供时退回 [PluginHostHolder]
 * （AppContainer 里 set 一次即可）。
 */
val LocalPluginHost = staticCompositionLocalOf<PluginHost?> { null }

/** 进程级兜底：CompositionLocal 没接线时槽位页也不至于白屏。 */
object PluginHostHolder {
    @Volatile
    var instance: PluginHost? = null
}

/**
 * 槽位开关（需求 4）：每个贡献单独启停，落在 SharedPreferences，
 * 键固定为 `dsh_plugin_slot_<slotId>_<contributionId>`。
 *
 * 开关本身落 SharedPreferences，另外维护一个 Compose 可观测的版本号：
 * 消费点（AppNav 的 composerSlots / 插件页的槽位预览）在组合期读 [revision]，
 * 用户一拨开关就重组，不需要重启界面。
 */
object SlotGate {

    private const val PREFS = "dsh_plugin_slots"

    /** 每次写入自增；组合期读它会登记依赖，写入后所有消费点立即重组。 */
    private val _revision = mutableIntStateOf(0)
    val revision: Int get() = _revision.intValue

    fun key(slot: SlotId, contributionId: String): String =
        "dsh_plugin_slot_${slot.name}_$contributionId"

    fun isEnabled(context: Context, slot: SlotId, contributionId: String): Boolean =
        runCatching {
            prefs(context).getBoolean(key(slot, contributionId), true)
        }.getOrDefault(true)

    fun setEnabled(context: Context, slot: SlotId, contributionId: String, enabled: Boolean) {
        runCatching {
            prefs(context).edit().putBoolean(key(slot, contributionId), enabled).apply()
        }
        // 无论写入成败都 +1：写失败时界面至少重新读一次真实值，不会停在旧状态。
        _revision.intValue++
    }

    /** 过滤掉被禁用的贡献；任何异常都当"全开"，绝不因为读配置失败而清空槽位。 */
    fun filter(
        context: Context,
        slot: SlotId,
        list: List<SlotContribution>,
    ): List<SlotContribution> = runCatching {
        // 组合期读一次版本号：槽位消费点在 Compose 里调用本函数即可获得重组依赖。
        _revision.intValue
        list.filter { isEnabled(context, slot, it.id) }
    }.getOrDefault(list)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}