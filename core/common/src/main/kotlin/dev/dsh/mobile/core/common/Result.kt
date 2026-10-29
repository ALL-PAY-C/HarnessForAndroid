package dev.dsh.mobile.core.common

import kotlinx.coroutines.CancellationException

/** Minimal result type so protocol/data layers never throw into the UI. */
sealed interface DshResult<out T> {
    data class Ok<T>(val value: T) : DshResult<T>
    data class Err(val message: String, val cause: Throwable? = null) : DshResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): String? = (this as? Err)?.message
}

inline fun <T> runCatchingDsh(block: () -> T): DshResult<T> =
    try {
        DshResult.Ok(block())
    } catch (c: CancellationException) {
        // 协程取消是控制流，不是错误：吞掉会把「正常取消」变成 Err，
        // 取消不再向上传播、协程无法正确结束，后续 onFailure 里的日志/UI 逻辑还照常执行。
        throw c
    } catch (t: Throwable) {
        DshResult.Err(t.message ?: t::class.java.simpleName, t)
    }
