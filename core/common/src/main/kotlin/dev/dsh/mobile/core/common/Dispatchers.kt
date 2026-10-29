package dev.dsh.mobile.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus

/**
 * Single place that owns every coroutine scope in the app.
 * ViewModels/Repositories never create their own scopes.
 */
object AppDispatchers {
    @Volatile var io: CoroutineDispatcher = Dispatchers.IO
    @Volatile var main: CoroutineDispatcher = Dispatchers.Main.immediate
    @Volatile var default: CoroutineDispatcher = Dispatchers.Default
}

/** Application-scoped supervisor scope: one crash never cancels sibling work. */
object AppScope {
    private val job = SupervisorJob()
    @Volatile var scope: CoroutineScope = CoroutineScope(job + AppDispatchers.default)
}
