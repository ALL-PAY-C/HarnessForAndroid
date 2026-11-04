package dev.dsh.mobile.core.model.diag

enum class BootState { Normal, SafeMode }

/**
 * 启动守卫：只回答「这次启动算第几次连续失败」。
 * 判定是否真的失败（掉电/用户手杀/升级）不在这里做，那需要崩溃物证，属于 BootGuard 的职责。
 */
class SafeMode(
    private val store: DiagStore,
    private val clock: () -> Long,
    private val threshold: Int = 2,
) {

    fun beginBoot(): BootState {
        val attempt = (store.get(ATTEMPT_KEY)?.toIntOrNull() ?: 0) + 1
        store.put(ATTEMPT_KEY, attempt.toString())
        store.put(ATTEMPT_AT_KEY, clock().toString())
        // 安全模式是粘性的：进入后即使本次启动看起来正常也不自动解除，否则崩溃循环会永远在两个状态间抖动。
        if (attempt > threshold) {
            store.put(SAFE_MODE_KEY, "1")
            return BootState.SafeMode
        }
        return BootState.Normal
    }

    fun markBootOk() {
        store.put(ATTEMPT_KEY, "0")
        store.put(OK_AT_KEY, clock().toString())
    }

    fun isSafeMode(): Boolean = store.get(SAFE_MODE_KEY) == "1"

    fun exitSafeMode() {
        store.put(SAFE_MODE_KEY, null)
    }

    private companion object {
        const val ATTEMPT_KEY = "boot.attempt"
        const val ATTEMPT_AT_KEY = "boot.attempt.at"
        const val OK_AT_KEY = "boot.ok.at"
        const val SAFE_MODE_KEY = "boot.safeMode"
    }
}
