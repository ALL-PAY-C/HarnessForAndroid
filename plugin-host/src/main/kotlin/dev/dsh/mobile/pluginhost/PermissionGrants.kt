package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.PluginPermission

/**
 * 插件权限授予的持久化口。
 *
 * 抽成接口而不是让 [PluginHost] 直接吃 [PluginStore]：PluginStore 要 Context，而「声明了但没授予
 * 就不 register」是纯逻辑，JVM 单测得能注入内存实现。PluginStore 自己实现它，app 侧零改动
 * （PluginHost 的 [PluginHost.grants] 缺省就回落到 store）。
 *
 * 存的是 [dev.dsh.mobile.pluginapi.PluginPermission.name]，不是枚举序号：序号会随枚举增删漂移，
 * 存量授权会静默变成另一项权限。
 */
/**
 * plugin.json 的 `permissions` 名 → 枚举；认不出的丢掉。
 *
 * 丢掉而不是报错：宿主比插件老时，插件写的新权限名不该让整包加载失败 —— 少一项授权判定
 * 比装不上安全，而 [PluginManifest.permissions] 为空集时行为就是「不需要授权」。
 */
internal fun PluginManifestFile.declaredPermissions(): Set<PluginPermission> =
    permissions.mapNotNullTo(LinkedHashSet()) { PluginPermission.ofName(it) }

interface PermissionGrants {
    /** 该插件已授予的权限名；没授过就是空集。 */
    fun permissions(id: String): Set<String>

    /** 覆盖写：传空集即撤销全部。 */
    fun grant(id: String, permissions: Set<String>)
}
