package dev.dsh.mobile.app.nav

/**
 * 系统返回键的归属（纯函数，便于单测）。
 *
 * [tabRoutes] = 底部 tab 的根路由（会话 / Agent / 插件 / 设置 + 插件贡献的底部 tab）。
 * 不在其中的路由都是二级页：返回键必须 popBackStack 回上一页，
 * 绝不穿透到会话主页（startDestination）。
 */
enum class BackTarget {
    /** 页内二级导航先吃（详情页 / 文件夹 / 槽位详情）。 */
    InPage,

    /** 导航栈上是二级页：popBackStack。 */
    PopRoute,

    /** tab 根或首屏：交给 NavHost / 系统默认处理。 */
    System,
}

fun backTarget(route: String?, tabRoutes: Collection<String>, inPageDepth: Int = 0): BackTarget = when {
    inPageDepth > 0 -> BackTarget.InPage
    route.isNullOrBlank() -> BackTarget.System
    route in tabRoutes -> BackTarget.System
    else -> BackTarget.PopRoute
}
