package dev.dsh.mobile.linux

/** 安装结果：要么给出可用安装，要么给出人话原因（绝不静默降级）。 */
sealed interface LinuxResult<out T> {
    data class Ok<T>(val value: T) : LinuxResult<T>
    data class Err(val reason: String, val cause: Throwable? = null) : LinuxResult<Nothing>
}

inline fun <T> LinuxResult<T>.getOrNull(): T? = (this as? LinuxResult.Ok)?.value
