package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * In-process channel: talks ACP to a locally spawned runtime over its stdio.
 * No sockets, no ports -> nothing to conflict with, no reconnect cost.
 * The process itself is owned by :runtime-host; this class only speaks lines.
 */
class ProcessLineChannel(
    private val command: List<String>,
    private val workDir: String?,
    private val env: Map<String, String>,
    private val scope: CoroutineScope,
) : LineChannel {

    private val buffer = Channel<String>(Channel.UNLIMITED)
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null

    override val incoming: Flow<String> = buffer.receiveAsFlow()
    override val description: String get() = "proc:${command.firstOrNull()?.substringAfterLast('/') ?: "node"}"

    override suspend fun open() {
        val pb = ProcessBuilder(command)
        if (workDir != null) pb.directory(java.io.File(workDir))
        pb.environment().putAll(env)
        pb.redirectErrorStream(false)
        val p = pb.start()
        process = p
        writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        scope.launch(Dispatchers.IO) {
            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { buffer.trySend(it) }
            }
            buffer.close()
        }
        scope.launch(Dispatchers.IO) {
            BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { AppLog.d("[runtime] $it") }
            }
        }
        AppLog.i("runtime process started: ${command.firstOrNull()}")
    }

    override suspend fun send(line: String) {
        val w = writer ?: throw IllegalStateException("运行时未启动")
        w.write(line)
        w.write("\n")
        w.flush()
    }

    override suspend fun close() {
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        process = null
        writer = null
        buffer.close()
    }
}
