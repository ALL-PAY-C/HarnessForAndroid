package dev.dsh.mobile.feature.plugins

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.design.R
import dev.dsh.mobile.pluginapi.PluginPermission

/**
 * 权限词表的显示层映射（T9.25）。
 *
 * 枚举只带名字（数据层不夹文案），文案在这里按项查 design 资源：切系统语言后重新渲染即生效，
 * JVM 单测也不需要 Context。when 是穷尽的，加一项权限漏了分支就编译不过。
 *
 * 放在 feature:plugins 而不是 design：枚举在 plugin-api，design 没有（也不该为文案）
 * 依赖 plugin-api，而模块依赖表不在本任务可改范围内。
 */
@Composable
fun PluginPermission.title(): String = stringResource(
    when (this) {
        PluginPermission.Shell -> R.string.plugins_perm_shell_title
        PluginPermission.PhoneControl -> R.string.plugins_perm_phone_control_title
        PluginPermission.Privileged -> R.string.plugins_perm_privileged_title
        PluginPermission.Workspace -> R.string.plugins_perm_workspace_title
    },
)

/** 一句话说清「这个插件要干什么」，授权对话框里逐条列在标题下面。 */
@Composable
fun PluginPermission.hint(): String = stringResource(
    when (this) {
        PluginPermission.Shell -> R.string.plugins_perm_shell_hint
        PluginPermission.PhoneControl -> R.string.plugins_perm_phone_control_hint
        PluginPermission.Privileged -> R.string.plugins_perm_privileged_hint
        PluginPermission.Workspace -> R.string.plugins_perm_workspace_hint
    },
)
