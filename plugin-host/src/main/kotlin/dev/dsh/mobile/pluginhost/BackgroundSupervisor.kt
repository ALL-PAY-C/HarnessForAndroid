package dev.dsh.mobile.pluginhost

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 后台插件的守护循环（B1）。
 *
 * 只解决一个线上问题：[PluginHost.startBackgroundPlugins] 原来只在加载完调一次，
 * 网关没起来（端口被占、进程被杀、拉起超时）就再也没有第二次机会，用户看到的是
 * 「模型源一直在，连上就报拒绝」。
 *
 * 规则：
 * - 失败按 1s / 2s / 4s / 8s / 16s / 32s / 60s 封顶退避，不做快速重试（日志会被刷屏）；
 * - 成功一次立刻把退避归零，下一次失败重新从 1s 起；
 * - 成功之后不退出，改为每 [HEALTHY_PROBE_MS] 再调一次 —— `ensureStarted` 按接口约定
 *   必须幂等（[dev.dsh.mobile.pluginapi.BackgroundPlugin]），这次调用就是「服务还在不在」的探测；
 * - [isAllowed] 每轮都重新判定：插件被禁用 / 卸载 / 安全模式挡住时循环直接退出，
 *   宿主另有一条 cancel 路径保证协程不会挂在 delay 里。
 *
 * 抽成独立类而不是塞进 PluginHost：没有 Android 依赖，[sleep] 可注入，
 * 退避序列能在单测里逐项断言，不用等真实的一分钟。
 */
internal class BackgroundSupervisor(
    /** 门控：与首拉同一条判定（[shouldStartBackgroundPlugin]），语义一处定义。 */
    private val isAllowed: () -> Boolean,
    /** 真正的拉起动作；返回值里的 failure = 服务没就位。 */
    private val start: suspend () -> Result<Unit>,
    /** 每次失败的回执（宿主用它记日志 + onPluginCrash）。 */
    private val onFailure: (Throwable) -> Unit,
    /** 睡眠入口：默认真 delay，单测里换成记账的假实现。 */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val firstDelayMs: Long = FIRST_RETRY_MS,
    private val maxDelayMs: Long = MAX_RETRY_MS,
    private val probeIntervalMs: Long = HEALTHY_PROBE_MS,
) {

    suspend fun run() {
        var retryMs = firstDelayMs
        while (isAllowed()) {
            // 协程被 cancel 时 delay 会抛，走到这里再兜一次，避免 sleep 被换成非挂起实现后空转。
            currentCoroutineContext().ensureActive()
            val error = attempt()
            if (error == null) {
                retryMs = firstDelayMs
                sleep(probeIntervalMs)
            } else {
                onFailure(error)
                sleep(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(maxDelayMs)
            }
        }
    }

    /** 插件可能抛而不是返回 failure，两条都收敛成一个 Throwable，调用方只看「有没有错」。 */
    private suspend fun attempt(): Throwable? = try {
        start().exceptionOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        e
    }

    companion object {
        const val FIRST_RETRY_MS: Long = 1_000L
        const val MAX_RETRY_MS: Long = 60_000L

        /** 服务健康时的探测间隔：与退避封顶同一个量级，不做心跳风暴。 */
        const val HEALTHY_PROBE_MS: Long = 60_000L
    }
}
