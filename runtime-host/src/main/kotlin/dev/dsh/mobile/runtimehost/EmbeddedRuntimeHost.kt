package dev.dsh.mobile.runtimehost

import android.content.Context
import android.os.Build
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.protocol.LineChannel
import dev.dsh.mobile.core.protocol.ProcessLineChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the embedded Node runtime that hosts the DSH engine on the phone.
 *
 * Layout (all under the app's private dir, survives reinstalls of the APK payload):
 *   files/runtime/<version>/node      executable (extracted from assets/runtime/<abi>/node)
 *   files/runtime/<version>/engine/   DSH engine bundle (assets/runtime/engine/)
 *   files/runtime/home/               HOME for the engine (sessions, logs, config)
 *
 * Assets are extracted once per version; later launches reuse them, so switching
 * versions is instant and rollback is just pointing at the previous directory.
 */
class EmbeddedRuntimeHost(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotConfigured)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    @Volatile private var current: LineChannel? = null

    val abi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    private fun rootDir(): File = File(context.filesDir, "runtime").apply { mkdirs() }
    private fun versionDir(version: String): File = File(rootDir(), version).apply { mkdirs() }
    fun homeDir(): File = File(rootDir(), "home").apply { mkdirs() }
    fun logDir(): File = File(rootDir(), "logs").apply { mkdirs() }

    /** Versions present in the APK payload, newest first. */
    fun bundledVersions(): List<String> = runCatching {
        val listed = context.assets.list("runtime")?.toList() ?: emptyList()
        listed.filter { it.startsWith("v") }.sortedDescending()
    }.getOrDefault(emptyList())

    /** True when a Node binary for this ABI is actually shipped in the APK. */
    fun hasBundledNode(version: String = "v1"): Boolean = runCatching {
        context.assets.open("runtime/$version/$abi/node").close()
        true
    }.getOrDefault(false)

    /**
     * Extracts assets for [version] if needed. Idempotent and cheap on warm starts
     * (a marker file records the extracted version).
     */
    suspend fun prepare(version: String = "v1"): File = withContext(Dispatchers.IO) {
        val dir = versionDir(version)
        val marker = File(dir, ".extracted")
        val node = File(dir, "node")
        if (marker.exists() && node.exists()) return@withContext dir

        _state.value = RuntimeState.Starting("解压运行时 $version")
        copyAssetDir("runtime/$version/$abi", dir)
        copyAssetDir("runtime/engine", File(dir, "engine"))
        if (node.exists()) {
            node.setExecutable(true, false)
        }
        marker.writeText("${System.currentTimeMillis()}")
        AppLog.i("runtime prepared: ${dir.absolutePath}")
        dir
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val children = runCatching { context.assets.list(assetPath) }.getOrNull() ?: return
        if (children.isEmpty()) {
            // Leaf: it's a file.
            runCatching {
                target.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
            }.onFailure { AppLog.w("asset copy failed $assetPath: ${it.message}") }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetDir("$assetPath/$child", File(target, child)) }
    }

    /**
     * Starts the engine and returns a line channel speaking ACP over its stdio.
     * No TCP port is opened: UI and runtime talk through pipes.
     */
    suspend fun start(version: String = "v1", cwd: String = "/"): LineChannel = withContext(Dispatchers.IO) {
        if (!hasBundledNode(version)) {
            _state.value = RuntimeState.Failed(
                "APK 内未找到 runtime/$version/$abi/node",
                retryable = false,
            )
            throw IllegalStateException("未内置 Node 运行时")
        }
        val dir = prepare(version)
        val node = File(dir, "node")
        val engine = File(dir, "engine/bin.js")
        if (!engine.exists()) {
            _state.value = RuntimeState.Failed("缺少 DSH 引擎（engine/bin.js）", retryable = false)
            throw IllegalStateException("缺少 DSH 引擎")
        }
        _state.value = RuntimeState.Starting("启动 Node 运行时")
        val channel = ProcessLineChannel(
            command = listOf(node.absolutePath, engine.absolutePath, "--profile", "acp"),
            workDir = cwd.takeIf { File(it).isDirectory } ?: homeDir().absolutePath,
            env = mapOf(
                "HOME" to homeDir().absolutePath,
                "TMPDIR" to context.cacheDir.absolutePath,
                "DSH_HOME" to homeDir().absolutePath,
                "NODE_OPTIONS" to "--max-old-space-size=512",
            ),
            scope = scope,
        )
        channel.open()
        current = channel
        _state.value = RuntimeState.Ready(dev.dsh.mobile.core.model.TransportKind.InProcess, "内置运行时 $version")
        channel
    }

    suspend fun stop() {
        runCatching { current?.close() }
        current = null
        _state.value = RuntimeState.NotConfigured
    }

    /** Storage used by runtime data/logs, for the settings cleanup entry. */
    fun storageBytes(): Long = rootDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        logDir().listFiles()?.forEach { it.delete() }
    }
}
