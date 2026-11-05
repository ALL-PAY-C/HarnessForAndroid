package dev.dsh.mobile.core.platform

/** Minimal result type so protocol/data layers never throw into the UI. */
sealed interface DshResult<out T> {
    data class Ok<T>(val value: T) : DshResult<T>
    data class Err(val message: String, val cause: Throwable? = null) : DshResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): String? = (this as? Err)?.message
}
