package dev.dsh.mobile.core.model

import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow view of the runtime host for UI modules that must not depend on :runtime-host.
 * Implemented in :app by adapting EmbeddedRuntimeHost.
 */
interface RuntimeControl {
    val state: StateFlow<RuntimeState>
    fun versions(): List<String>
    fun hasBundled(version: String): Boolean
    fun storageBytes(): Long
    suspend fun clearLogs()
    fun reconnect()
}
