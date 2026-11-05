package dev.dsh.mobile.feature.plugins

import androidx.lifecycle.ViewModel
import dev.dsh.mobile.pluginapi.PluginPermission
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginhost.ExternalPluginStore
import dev.dsh.mobile.pluginhost.LoadedPlugin
import dev.dsh.mobile.pluginhost.PluginHost
import kotlinx.coroutines.flow.StateFlow

class PluginsViewModel(
    /** 槽位预览与槽位管理页都要读注册表，故对外可见。 */
    val host: PluginHost,
    /** 外挂插件存储：卸载要清掉 APK 副本并记住「已移除」，否则下次 load() 会扫回来。 */
    private val externalStore: ExternalPluginStore? = null,
    /** 卸载后重新加载：由导航层注入 container.reloadPlugins()。 */
    private val onReload: () -> Unit = {},
) : ViewModel() {

    val plugins: StateFlow<List<LoadedPlugin>> = host.plugins

    /** 注册表变更版本号：贡献增删后触发重组。 */
    val revision: StateFlow<Int> = host.revision

    fun setEnabled(id: String, enabled: Boolean) = host.setEnabled(id, enabled)

    /** 授予插件权限：宿主立刻重算并按需 register，行内从「需授权」变「已启用」。 */
    fun grant(id: String, permissions: Set<PluginPermission>) = host.grantPermissions(id, permissions)

    /** 撤销授权：贡献立刻下架，行回到「需授权」；用户不必卸载重装才能收回权限。 */
    fun revoke(id: String, permissions: Set<PluginPermission>) = host.revokePermissions(id, permissions)

    /** 退出安全模式：插件页顶部的横幅点一下就走这里，否则被挡的插件没有恢复路径。 */
    fun clearSafeMode() = host.clearSafeMode()

    /** 卸载：清持久化条目 + 拉黑 id → 摘注册表 → 重新加载，三步缺一条就「刷新又回来」。
     *  返回结果供 UI 显示真实成败 —— 文件删除失败时绝不假装成功。 */
    fun uninstall(id: String): Result<Unit> = runCatching {
        host.uninstall(id)
        onReload()
    }

    fun reload() {
        host.plugins.value.forEach { p ->
            if (p.enabled) host.setEnabled(p.manifest.id, true)
        }
    }

    /**
     * 某个插件注册的全部槽位贡献（异常时给空表，界面不崩）。
     *
     * plugin.page 贡献会被 DrawerEntryBridge 再桥接一份到抽屉，两者 pluginId + id 相同，
     * 直接过滤会让详情里同一张卡片渲染两遍，所以按 id 去重。
     */
    fun contributionsOf(pluginId: String): List<SlotContribution> = runCatching {
        host.slots.contributions
            .filter { it.pluginId == pluginId }
            .distinctBy { it.id }
    }.getOrDefault(emptyList())
}
