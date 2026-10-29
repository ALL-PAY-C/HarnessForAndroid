package dev.dsh.mobile.feature.plugins

/**
 * 插件 Hub 页内二级导航的返回键归属（纯函数，便于单测）。
 *
 * 顺序 = 视觉层级：详情页 > 槽位详情 > 「插件设置」文件夹 > 交给外层导航。
 * 前三种必须在本页消费掉：一旦外泄，NavHost 会把整个 `plugins/hub` 目的地 pop 掉，
 * 用户按返回直接落到会话主页（「插件-插件管理里按返回跑了」的成因）。
 */
internal enum class HubBackStep {
    ClosePage,
    CloseSlot,
    CloseFolder,
    NotMine,
}

internal fun hubBackStep(pageOpen: Boolean, slotOpen: Boolean, folderOpen: Boolean): HubBackStep = when {
    pageOpen -> HubBackStep.ClosePage
    slotOpen -> HubBackStep.CloseSlot
    folderOpen -> HubBackStep.CloseFolder
    else -> HubBackStep.NotMine
}
