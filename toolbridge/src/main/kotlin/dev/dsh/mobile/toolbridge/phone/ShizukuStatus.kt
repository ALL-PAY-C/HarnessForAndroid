package dev.dsh.mobile.toolbridge.phone

/**
 * Shizuku 的四种状态。判定顺序固定：没装 > 没跑 > 没授权 > 可用，
 * 顺序本身就是用户的操作顺序，别打乱。
 *
 * 纯数据 + 纯函数：不碰 Android API，JVM 单测可直接构造。
 */
enum class ShizukuState { NotInstalled, NotRunning, NoPermission, Ready }

data class ShizukuStatus(
    val state: ShizukuState,
    val label: String,
    val detail: String,
) {

    val ready: Boolean get() = state == ShizukuState.Ready

    /** 用户下一步该做什么；已就绪返回空串（界面据此决定要不要显示按钮）。 */
    fun hint(): String = when (state) {
        ShizukuState.NotInstalled -> "可选：装 Shizuku 后免 root 也能用 shell uid 跑命令"
        ShizukuState.NotRunning -> "打开 Shizuku 应用并启动服务（无线调试或 root 启动）"
        ShizukuState.NoPermission -> "在 Shizuku 里给 DSH Mobile 授权"
        ShizukuState.Ready -> ""
    }

    companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"

        fun of(installed: Boolean, running: Boolean, granted: Boolean): ShizukuStatus = when {
            !installed -> ShizukuStatus(ShizukuState.NotInstalled, "未安装", "未检测到 Shizuku")
            !running -> ShizukuStatus(ShizukuState.NotRunning, "未运行", "Shizuku 服务未启动")
            !granted -> ShizukuStatus(ShizukuState.NoPermission, "未授权", "等待 Shizuku 授权")
            else -> ShizukuStatus(ShizukuState.Ready, "可用", "已授权，shell uid 执行")
        }
    }
}
