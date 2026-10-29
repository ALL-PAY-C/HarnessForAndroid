package dev.dsh.mobile.core.model

/**
 * Runtime + connection state machine.
 * UI renders this verbatim; it never inspects the transport implementation.
 */
sealed interface RuntimeState {
    /** Runtime binary/assets being prepared (extract, chmod, version switch). */
    data class Starting(val detail: String = "") : RuntimeState
    /** Transport handshake done, ACP initialize acknowledged. */
    data class Ready(val transport: TransportKind, val detail: String = "") : RuntimeState
    data class Reconnecting(val attempt: Int, val detail: String = "") : RuntimeState
    data class Failed(val reason: String, val retryable: Boolean = true) : RuntimeState
    /** No runtime bundled and no endpoint configured. */
    data object NotConfigured : RuntimeState
}

enum class TransportKind { InProcess, Loopback, Remote }

/** Where the runtime lives; persisted by the settings layer. */
data class Endpoint(
    val id: String,
    val name: String,
    val kind: TransportKind,
    val url: String = "",
    val token: String = "",
    val cwd: String = "",
    val heartbeatMs: Long = 15_000L,
    val autoConnect: Boolean = true,
) {
}
