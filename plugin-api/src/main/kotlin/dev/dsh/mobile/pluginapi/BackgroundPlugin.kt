package dev.dsh.mobile.pluginapi

import android.content.Context

/**
 * 需要在宿主进程里常驻一个本地服务的插件实现它（例如插件自带的本机网关）。
 *
 * 用户不会为了用一个模型先去插件页点一次「启动」，所以 [PluginHost] 在插件加载完成后
 * 调用一次 [ensureStarted]，让本地服务在 App 起来时就位；进程被杀后网关随之消失，
 * 下次启动会再拉一次。
 *
 * 约定：
 * - 必须幂等 —— 已经在跑就直接返回成功，不要重复拉起进程；
 * - 失败只影响插件自身能力，宿主照常工作，异常由宿主记进 AppLog；
 * - 不要在实现里阻塞太久，宿主在后台协程里调用，但启动超时会拖慢模型首包。
 */
interface BackgroundPlugin {
    suspend fun ensureStarted(context: Context): Result<Unit>
}
