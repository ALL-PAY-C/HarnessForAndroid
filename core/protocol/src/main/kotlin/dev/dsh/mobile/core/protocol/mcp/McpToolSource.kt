package dev.dsh.mobile.core.protocol.mcp

import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.protocol.tool.ToolCtx
import dev.dsh.mobile.core.protocol.tool.ToolExecution
import dev.dsh.mobile.core.protocol.tool.ToolSpec
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 已注入的 MCP 工具与其源服务器的映射（T9.10 P3）。
 *
 * 声明名对模型可见，[`serverId`] / [rawName] 只在本类内部用来把调用打回服务器 —— 名字被
 * 截断或去重改写过之后，还原不回原始名，所以必须留这一层。
 */
data class McpToolBinding(
    val declaredName: String,
    val serverId: String,
    /** tools/list 里的原始工具名。 */
    val rawName: String,
    /** 单次 tools/call 的预算（min(配置, 600s)）。 */
    val timeoutMs: Long,
)

/**
 * MCP → InProcess 工具表（T9.10 P3）。
 *
 * 一个 enabled 的服务器 = 它的 tools/list 每条摊成一个 [ToolSpec]：
 * 名字 `mcp__<server>__<tool>`，执行体把调用打回同一个 [McpClient]，结果转成一行文本。
 *
 * 失败语义（设计 §2.5）：连接失败 / 超时 / 工具报错都收敛成一行可读文本，绝不抛穿
 * [dev.dsh.mobile.core.protocol.ToolExecutor] —— 抛穿会被当成「整轮 turn crashed」，
 * 同一轮其它工具与正文一起消失。某个服务器连不上时它自己的工具整条不注入，别的照常。
 *
 * 线程模型：[refresh] 全表重算（持一把 Mutex），[snapshot] 读的是不可变快照，
 * 每轮工具表都在变，所以工具表本身以 [MutableStateFlow] 形式发布（配置变更即生效，不重启 transport）。
 */
class McpToolSource(
    /** 建客户端的接缝：单测塞假客户端，生产用 [HttpMcpClient]。 */
    private val factory: (McpServerConfig) -> McpClient = { HttpMcpClient(it) },
    /** 图片落盘目录；null = 不落盘（此时只回一行说明）。 */
    private val imageDir: () -> File? = { null },
) {

    private data class Entry(
        val cfg: McpServerConfig,
        val client: McpClient,
        val tools: List<McpToolDef>,
    )

    private val lock = Mutex()

    private val _specs = MutableStateFlow<List<ToolSpec>>(emptyList())

    /** 当前工具表：宿主每轮从它取（变更即生效）。 */
    val specs: StateFlow<List<ToolSpec>> = _specs.asStateFlow()

    private val _bindings = MutableStateFlow<Map<String, McpToolBinding>>(emptyMap())

    private val _status = MutableStateFlow<Map<String, McpError?>>(emptyMap())

    /** 每个服务器最近一次连接结果：null = 正常，非 null = 该服务器当前不可用（UI 状态点读它）。 */
    val status: StateFlow<Map<String, McpError?>> = _status.asStateFlow()

    @Volatile
    private var entries: Map<String, Entry> = emptyMap()

    /**
     * 已注入服务器的配置（按 id）。空闲回收只断连接、保留工具声明，所以要留一份配置
     * 供 [ensureConnected] 按需重连；[close] 时随之清空。
     */
    @Volatile
    private var configById: Map<String, McpServerConfig> = emptyMap()

    /** 最近一次真实工具调用时刻，用于空闲回收。 */
    @Volatile
    private var lastCallAt: Long = System.currentTimeMillis()

    /** 工具表快照（非挂起，工具循环每轮直接调）。 */
    fun snapshot(): List<ToolSpec> = _specs.value

    /** 逐工具超时预算：MCP 工具取 min(配置的 toolCallTimeoutMs, 600s)，其余工具返回 null。 */
    fun timeoutOf(name: String): Long? = _bindings.value[name]?.timeoutMs

    /**
     * 按当前配置重算工具表：新服务器连一次（握手 + tools/list，分页由客户端处理），
     * 配置没变的服务器复用已连的客户端，被删/被停的关闭连接。
     *
     * 任何单个服务器的异常都在这里被吃掉并记进 [status]，不向上抛。
     */
    suspend fun refresh(configs: List<McpServerConfig>) = lock.withLock {
        val next = LinkedHashMap<String, Entry>()
        val status = LinkedHashMap<String, McpError?>()
        val previous = entries

        configs.forEach { cfg ->
            // STDIO 只能由引擎 spawn（Android 10+ 禁 exec 私有目录），这里不假装能连。
            if (cfg.transport == McpTransportKind.Stdio) {
                status[cfg.id] = McpError.UnsupportedTransport
                return@forEach
            }
            if (!cfg.enabled) {
                status[cfg.id] = McpError.Disabled
                return@forEach
            }
            val reused = previous[cfg.id]?.takeIf { sameTarget(it.cfg, cfg) }
            val client = reused?.client ?: factory(cfg)
            val tools = reused?.tools ?: fetch(client, cfg, status)
            if (tools == null) return@forEach
            next[cfg.id] = Entry(cfg.copy(enabled = true), client, tools)
            status[cfg.id] = null
        }

        // 被删 / 被停 / 换了地址的服务器：旧的连接要关，否则泄漏且后台还在发心跳。
        previous.forEach { (id, entry) ->
            if (next[id]?.client !== entry.client) runCatching { entry.client.close() }
        }
        entries = next
        configById = next.values.associate { it.cfg.id to it.cfg }

        val bindings = LinkedHashMap<String, McpToolBinding>()
        val used = HashSet<String>()
        val specs = ArrayList<ToolSpec>()
        next.values.forEach { entry ->
            entry.tools.forEach { def ->
                // 「后到加 _2」：按配置顺序入表，先到者占住名字。
                val declared = uniqueName(mcpDeclaredName(entry.cfg.name, def.name), used)
                used += declared
                val budget = mcpTimeoutBudget(entry.cfg.toolCallTimeoutMs)
                bindings[declared] = McpToolBinding(declared, entry.cfg.id, def.name, budget)
                specs += ToolSpec(
                    name = declared,
                    // 档位由服务器配置决定（默认可回滚写）：MCP 工具的副作用边界只有配置者知道。
                    sensitivity = entry.cfg.sensitivity,
                    // 副作用未知：失败重试一次 = 可能把写动作打第二遍。
                    noRetry = true,
                    declaration = { _ -> declarationOf(declared, def) },
                    execution = ToolExecution.Runner { args, _ -> call(declared, args) },
                )
            }
        }

        _bindings.value = bindings
        _specs.value = specs
        _status.value = status
    }

    /**
     * 执行一次 MCP 工具调用，恒返回可读文本（成功正文 / isError 文本 / 一行错误说明）。
     *
     * 工具循环不感知 MCP：[ToolExecution.Runner] 与单测都走这一条路径。
     */
    internal suspend fun call(declaredName: String, args: JsonObject): String {
        lastCallAt = System.currentTimeMillis()
        val binding = _bindings.value[declaredName]
            ?: return "MCP 工具不可用：" + declaredName + "（该服务器当前未连接）"
        var entry = entries[binding.serverId]
        if (entry == null) {
            // 空闲回收 / 上次刷新失败之后：工具声明还在表里，连接已断 —— 按需重连一次再打。
            val failure = ensureConnected(binding.serverId)
            if (failure != null) {
                return "MCP 工具不可用：" + declaredName + "（重连失败：" + failure.describe() + "）"
            }
            entry = entries[binding.serverId]
        }
        val client = entry?.client
            ?: return "MCP 工具不可用：" + declaredName + "（该服务器当前未连接）"
        return try {
            render(declaredName, client.callTool(binding.rawName, args, binding.timeoutMs))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val error = (t as? McpException)?.error
                ?: McpError.Protocol(t.message ?: t.javaClass.simpleName)
            AppLog.w("mcp 工具 " + declaredName + " 调用失败：" + error.describe())
            "MCP 工具调用失败：" + error.describe()
        }
    }

    /**
     * 空闲回收：超过 [idleMs] 没有任何工具调用就断开全部连接，但**保留工具声明**。
     *
     * 工具表是模型看见的那一面：清空它等于让 mcp__* 静默消失，而且配置不变就不会再刷新 ——
     * 只能重启 App 或去设置里改一次配置才恢复。所以这里只断连接、把状态标成可读的
     * 「空闲已断开」，下次 [call] 时按需重连（[ensureConnected]）。
     */
    suspend fun recycleIdle(idleMs: Long): Boolean = lock.withLock {
        if (System.currentTimeMillis() - lastCallAt < idleMs) return@withLock false
        val dropped = entries
        if (dropped.isEmpty()) return@withLock false
        dropped.values.forEach { runCatching { it.client.close() } }
        entries = emptyMap()
        _status.value = dropped.keys.associateWith { McpError.Network(IDLE_DISCONNECTED) }
        true
    }

    /** 断开全部连接并清空工具表（transport 销毁时调）。 */
    suspend fun close() = lock.withLock {
        entries.values.forEach { runCatching { it.client.close() } }
        entries = emptyMap()
        configById = emptyMap()
        _bindings.value = emptyMap()
        _specs.value = emptyList()
        _status.value = emptyMap()
    }

    /**
     * 确保某服务器有活连接：已连直接返回 null；否则按配置重连一次（持锁，避免并发重复建连）。
     *
     * 返回 null = 可用，非 null = 失败原因（已写进 [status]，不抛穿）。
     */
    private suspend fun ensureConnected(serverId: String): McpError? = lock.withLock {
        if (entries[serverId] != null) return@withLock null
        val cfg = configById[serverId] ?: return@withLock McpError.Disabled
        val status = LinkedHashMap(_status.value)
        val client = factory(cfg)
        val tools = fetch(client, cfg, status)
        if (tools == null) {
            _status.value = status
            return@withLock status[serverId] ?: McpError.Network("重连失败")
        }
        entries = entries + (serverId to Entry(cfg, client, tools))
        status[serverId] = null
        _status.value = status
        null
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    private suspend fun fetch(
        client: McpClient,
        cfg: McpServerConfig,
        status: MutableMap<String, McpError?>,
    ): List<McpToolDef>? = try {
        client.connect()
        client.listTools()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        val error = (t as? McpException)?.error
            ?: McpError.Handshake(t.message ?: t.javaClass.simpleName)
        AppLog.w("mcp 服务器 " + cfg.name + " 连接失败：" + error.describe())
        status[cfg.id] = error
        runCatching { client.close() }
        null
    }

    private fun sameTarget(a: McpServerConfig, b: McpServerConfig): Boolean =
        a.transport == b.transport && a.url == b.url && a.headers == b.headers

    private fun render(declaredName: String, result: McpCallResult): String {
        val lines = ArrayList<String>()
        val text = result.text.trim()
        if (result.isError) {
            // isError 也是「结果」，不是异常：原样回给模型，它按错误文本自己决定要不要换个做法。
            lines += "MCP 工具返回错误：" + text.ifBlank { "（服务器没有给错误描述）" }
        } else if (text.isNotEmpty()) {
            lines += text
        }
        result.images.forEachIndexed { index, image ->
            lines += imageLine(declaredName, image, index)
        }
        if (lines.isEmpty()) lines += "（MCP 工具没有返回内容）"
        return lines.joinToString("\n")
    }

    /** 图片走文件：base64 塞回文本会撑爆上下文，模型只需要知道路径 + 能用 fs_read/看图工具读。 */
    private fun imageLine(declaredName: String, image: McpImage, index: Int): String {
        val dir = imageDir() ?: return "[image] （未落盘：宿主没有给工作区目录）"
        return try {
            if (!dir.isDirectory) dir.mkdirs()
            val ext = when (image.mimeType.lowercase()) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "png"
            }
            val file = File(dir, declaredName + "-" + System.currentTimeMillis() + "-" + index + "." + ext)
            file.writeBytes(java.util.Base64.getDecoder().decode(image.base64))
            "[image] " + file.absolutePath
        } catch (t: Throwable) {
            AppLog.w("mcp 图片落盘失败：" + (t.message ?: t.javaClass.simpleName))
            "[image] （落盘失败：" + (t.message ?: t.javaClass.simpleName) + "）"
        }
    }

    private fun declarationOf(declaredName: String, def: McpToolDef): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", declaredName)
            put("description", descriptionOf(def))
            put("parameters", def.inputSchema)
        }
    }

    private fun descriptionOf(def: McpToolDef): String {
        val base = def.description.ifBlank { "MCP 工具 " + def.name }
        return if (def.destructive) base + "（该工具会改动服务器侧数据）" else base
    }
}

/**
 * 声明名：`mcp__<server>__<tool>`。
 *
 * 服务器名已被 [McpServerStore] 侧 sanitize 过一次，这里再兜一层（不信任外部配置）；
 * 整体超过 [MCP_TOOL_NAME_MAX] 时截断中段并补 8 位 sha1 —— 名字只是给模型看的，
 * 真正回打服务器靠 [McpToolBinding]。
 */
internal fun mcpDeclaredName(serverName: String, toolName: String): String {
    val prefix = "mcp__" + sanitizeServerToken(serverName) + "__"
    val safe = buildString {
        toolName.forEach { ch ->
            append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.') ch else '_')
        }
    }.ifBlank { "tool" }
    val full = prefix + safe
    if (full.length <= MCP_TOOL_NAME_MAX) return full
    val keep = (MCP_TOOL_NAME_MAX - prefix.length - 9).coerceAtLeast(1)
    return prefix + safe.take(keep) + "_" + sha1Hex(full).take(8)
}

/** 服务器名 → 名字安全片段：小写、非 [a-z0-9_-] → 下划线、去首尾下划线、最多 32 字符。 */
internal fun sanitizeServerToken(raw: String): String {
    val mapped = buildString {
        raw.lowercase().forEach { ch ->
            append(if ((ch in 'a'..'z') || (ch in '0'..'9') || ch == '_' || ch == '-') ch else '_')
        }
    }.trim('_')
    return mapped.take(32).ifBlank { "server" }
}

/** 单次调用预算：配置值与 600s 上限取小（下限 1s，避免 0 变成「立刻超时」）。 */
internal fun mcpTimeoutBudget(toolCallTimeoutMs: Long): Long =
    toolCallTimeoutMs.coerceIn(1_000L, MCP_MAX_CALL_TIMEOUT_MS)

private fun uniqueName(base: String, used: Set<String>): String {
    if (base !in used) return base
    var n = 2
    while (true) {
        val suffix = "_" + n
        val candidate =
            if (base.length + suffix.length <= MCP_TOOL_NAME_MAX) base + suffix
            else base.take(MCP_TOOL_NAME_MAX - suffix.length) + suffix
        if (candidate !in used) return candidate
        n++
    }
}

private fun sha1Hex(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { b ->
        (b.toInt() and 0xff).toString(16).padStart(2, '0')
    }

/** 工具名总长上限（模型侧函数名习惯值）。 */
internal const val MCP_TOOL_NAME_MAX = 64

/** 空闲回收后的状态文案：连接断了，工具声明还在，下次调用会重连。 */
internal const val IDLE_DISCONNECTED = "空闲已断开，将在下次调用时重连"
