package dev.dsh.mobile.core.protocol

/** Exponential backoff with a hard attempt ceiling. Pure logic -> unit tested. */
data class ReconnectPolicy(
    val enabled: Boolean = true,
    val maxAttempts: Int = 8,
    val baseDelayMs: Long = 500L,
    val maxDelayMs: Long = 20_000L,
) {
    fun delayFor(attempt: Int): Long {
        if (attempt <= 0) return baseDelayMs
        var d = baseDelayMs
        repeat(attempt - 1) { d *= 2 }
        return d.coerceAtMost(maxDelayMs)
    }
}
