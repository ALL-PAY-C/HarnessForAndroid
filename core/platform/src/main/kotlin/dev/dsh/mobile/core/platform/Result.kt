package dev.dsh.mobile.core.platform

import kotlinx.coroutines.CancellationException

// S1: DshResult 本体已上移 core:model（文件 core/model/.../core/platform/DshResult.kt，包名保持不变），
// 让 core:model 的契约类型能直接用 DshResult 而不反向依赖 core:platform。

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
