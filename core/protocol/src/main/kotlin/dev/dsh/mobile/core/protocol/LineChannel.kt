package dev.dsh.mobile.core.protocol

import kotlinx.coroutines.flow.Flow

/**
 * Newline-delimited text duplex channel. WebSocket and an embedded Node process
 * both satisfy this, so the ACP layer is transport-agnostic.
 */
interface LineChannel {
    /** Opens the channel; throws when unreachable. */
    suspend fun open()
    /** Sends one line (no trailing newline required). */
    suspend fun send(line: String)
    /** Inbound lines. Completes when the channel closes. */
    val incoming: Flow<String>
    suspend fun close()
    val description: String
}
