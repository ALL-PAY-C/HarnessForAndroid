package dev.dsh.mobile.pluginhost.builtin

import dev.dsh.mobile.pluginapi.DshPlugin

/**
 * App 内置的 Kotlin 插件清单（B3-0）。
 *
 * 装配点把这份列表和 HtmlPlugin 一起交给 PluginHost.loadAll，之后就全部走宿主已有链路：
 * 插件页的行、开关、设置项、后台拉起都不再为「内置插件」单开一条路。
 *
 * 这里用各插件自己的首选端口常量，不探测也不落盘：这份 val 会被设置页直接读 manifest 渲染表单，
 * 组合期不能 bind 端口。真要起网关时用 builtinPluginsWith 注入宿主分配器。
 */
val builtinPlugins: List<DshPlugin> = listOf(
    LocalGatewayBuiltinPlugin(),
    StreamGatewayBuiltinPlugin(),
)

/**
 * 带端口分配器的内置插件（装配点用这个）。
 *
 * 两个插件各报各的首选端口，端口互不撞由 LoopbackPortRegistry 的已发放集合保证：
 * 分配的探测条件是「不在 issued 且能 bind」，所以谁先拿到算谁的，后一个自动顺延 ——
 * 这里不需要、也不许再做「互相避让」的算术：那只是把并发窗口缩小，没解决重复发放。
 *
 * portOf(id, preferred) 由宿主实现成 PluginHost.loopbackPort(id, preferred)：端口一次分配、
 * 落盘复用，重载拿到的还是同一个值。
 */
fun builtinPluginsWith(portOf: (String, Int) -> Int): List<DshPlugin> = listOf(
    LocalGatewayBuiltinPlugin { id -> portOf(id, LocalGatewayBuiltinPlugin.PREFERRED_PORT) },
    StreamGatewayBuiltinPlugin { id -> portOf(id, StreamGatewayBuiltinPlugin.PREFERRED_PORT) },
)
