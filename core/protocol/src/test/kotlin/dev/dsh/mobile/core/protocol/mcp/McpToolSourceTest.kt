package dev.dsh.mobile.core.protocol.mcp

import dev.dsh.mobile.core.protocol.ToolExecutor
import dev.dsh.mobile.core.protocol.ToolOutputSpill
import dev.dsh.mobile.core.protocol.ToolSensitivity
import dev.dsh.mobile.core.protocol.tool.ToolCtx
import dev.dsh.mobile.core.protocol.tool.ToolRegistry
import dev.dsh.mobile.core.protocol.web.WebToolkit
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.10-P3：MCP 工具进 InProcess 工具表。
 *
 * 全部走假 [McpClient]：不建端口、不发真实网络请求、不碰 Android 运行时。
 */
class McpToolSourceTest {

    private val cfg = McpServerConfig(
        id = "s1",
        name = "Demo Server",
        transport = McpTransportKind.Http,
        url = "https://example.com/mcp",
        toolCallTimeoutMs = 120_000L,
    )

    private val toolCtx = ToolCtx(
        shellExecChannel = { _, _ -> "" },
        fsExec = { _, _ -> "" },
        channelListExec = { "" },
        bgExec = { _, _ -> "" },
        phoneExec = { "" },
        skillExec = { "" },
        spill = ToolOutputSpill(),
        shellHelp = { "" },
        web = WebToolkit(),
    )

    // ── 命名空间与去重 ────────────────────────────────────────────────────────

    @Test
    fun declaresNamespacedToolsWithDeclarationAndNoRetry() = runTest {
        val source = sourceOf(FakeMcpClient(tools = listOf(tool("read_file", "读文件"), tool("delete_file"))))
        source.refresh(listOf(cfg))

        val specs = source.snapshot()
        assertEquals(
            listOf("mcp__demo_server__read_file", "mcp__demo_server__delete_file"),
            specs.map { it.name },
        )
        val first = specs.first()
        // 副作用边界只有服务器配置者知道：失败重试一次 = 可能把写动作打第二遍。
        assertTrue(first.noRetry)
        assertEquals(ToolSensitivity.ReversibleWrite, first.sensitivity)
        assertNotNull(first.handler)

        val fn = first.declaration(toolCtx)["function"]!!.jsonObject
        assertEquals("mcp__demo_server__read_file", fn["name"]!!.jsonPrimitive.content)
        assertEquals("读文件", fn["description"]!!.jsonPrimitive.content)
        assertTrue(fn["parameters"] is JsonObject)
    }

    @Test
    fun duplicateToolNamesGetSuffix() = runTest {
        val source = McpToolSource(factory = { FakeMcpClient(tools = listOf(tool("ping"))) })
        source.refresh(listOf(cfg.copy(id = "a", name = "demo"), cfg.copy(id = "b", name = "demo")))

        assertEquals(
            listOf("mcp__demo__ping", "mcp__demo__ping_2"),
            source.snapshot().map { it.name },
        )
    }

    @Test
    fun longToolNameIsTruncatedWithHashSuffix() = runTest {
        val longName = "very_" + "x".repeat(90)
        val source = sourceOf(FakeMcpClient(tools = listOf(tool(longName))))
        source.refresh(listOf(cfg))

        val declared = source.snapshot().single().name
        assertTrue(declared.length <= MCP_TOOL_NAME_MAX)
        assertTrue(declared.startsWith("mcp__demo_server__"))
        // 截断后补 8 位 sha1，避免两条长名撞成同一个声明名。
        assertTrue(Regex("_[0-9a-f]{8}$").containsMatchIn(declared))
    }

    @Test
    fun sanitizesServerNameIntoFunctionSafePrefix() = runTest {
        assertEquals("mcp__a_b__tool", mcpDeclaredName("A B", "tool"))
        assertEquals("mcp__server__tool", mcpDeclaredName("", "tool"))
    }

    // ── 注入边界 ──────────────────────────────────────────────────────────────

    @Test
    fun disabledServerIsNotInjected() = runTest {
        var built = 0
        val source = McpToolSource(factory = { built++; FakeMcpClient(tools = listOf(tool("ping"))) })
        source.refresh(listOf(cfg.copy(enabled = false)))

        assertTrue(source.snapshot().isEmpty())
        assertEquals(McpError.Disabled, source.status.value["s1"])
        // 停用的服务器连客户端都不该建：设置页关掉就等于彻底不碰。
        assertEquals(0, built)
    }

    @Test
    fun handshakeFailureOnlyDropsThatServer() = runTest {
        val broken = FakeMcpClient(failure = McpError.Network("connect timed out"))
        val healthy = FakeMcpClient(tools = listOf(tool("ok_tool")))
        val source = McpToolSource(factory = { if (it.id == "broken") broken else healthy })
        source.refresh(listOf(cfg.copy(id = "broken", name = "broken"), cfg.copy(id = "ok", name = "ok")))

        assertEquals(listOf("mcp__ok__ok_tool"), source.snapshot().map { it.name })
        assertTrue(source.status.value["broken"] is McpError.Network)
        assertNull(source.status.value["ok"])
        // 连不上的客户端要收掉，否则后台一路挂着。
        assertEquals(1, broken.closes)
    }

    @Test
    fun refreshClosesRemovedServerAndClearsTable() = runTest {
        val fake = FakeMcpClient(tools = listOf(tool("ping")))
        val source = sourceOf(fake)
        source.refresh(listOf(cfg))
        assertEquals(1, source.snapshot().size)

        source.refresh(emptyList())
        assertTrue(source.snapshot().isEmpty())
        assertNull(source.timeoutOf("mcp__demo_server__ping"))
        assertEquals(1, fake.closes)
    }

    // ── 结果回填 ──────────────────────────────────────────────────────────────

    @Test
    fun callToolBackfillsText() = runTest {
        val fake = FakeMcpClient(tools = listOf(tool("echo")), result = { _, _, _ -> McpCallResult("hello") })
        val source = sourceOf(fake)
        source.refresh(listOf(cfg))

        val out = source.call("mcp__demo_server__echo", buildJsonObject { put("q", "hi") })

        assertEquals("hello", out)
        // 回打服务器用的是原始名与原始参数，不是声明名。
        assertEquals("echo", fake.calls.single().name)
        assertEquals("hi", fake.calls.single().args["q"]!!.jsonPrimitive.content)
    }

    @Test
    fun isErrorBackfillsErrorText() = runTest {
        val source = sourceOf(
            FakeMcpClient(tools = listOf(tool("boom")), result = { _, _, _ -> McpCallResult("boom", isError = true) }),
        )
        source.refresh(listOf(cfg))

        val out = source.call("mcp__demo_server__boom", JsonObject(emptyMap()))
        assertTrue(out.contains("boom"))
        assertTrue(out.startsWith("MCP 工具返回错误："))
    }

    @Test
    fun callFailureBecomesOneReadableLine() = runTest {
        val source = sourceOf(
            FakeMcpClient(tools = listOf(tool("x")), result = { _, _, _ -> throw McpException(McpError.Timeout) }),
        )
        source.refresh(listOf(cfg))

        val out = source.call("mcp__demo_server__x", JsonObject(emptyMap()))
        assertTrue(out.startsWith("MCP 工具调用失败："))
        assertTrue(out.contains("超时"))
    }

    @Test
    fun imageIsWrittenToDiskAndReportedAsPath() = runTest {
        val dir = Files.createTempDirectory("mcp-image").toFile()
        val source = McpToolSource(
            factory = {
                FakeMcpClient(
                    tools = listOf(tool("shot")),
                    result = { _, _, _ -> McpCallResult("done", images = listOf(McpImage("image/png", "QUJD"))) },
                )
            },
            imageDir = { dir },
        )
        source.refresh(listOf(cfg))

        val out = source.call("mcp__demo_server__shot", JsonObject(emptyMap()))
        val path = out.lines().first { it.startsWith("[image] ") }.removePrefix("[image] ")
        val file = File(path)
        assertTrue(file.isFile)
        // base64 不进文本：写盘后只回一行路径。
        assertEquals("ABC", file.readText())
        assertFalse(out.contains("QUJD"))
        assertTrue(out.startsWith("done"))
    }

    // ── 超时预算 ──────────────────────────────────────────────────────────────

    @Test
    fun timeoutBudgetIsMinOfConfigAndCap() = runTest {
        val capped = FakeMcpClient(tools = listOf(tool("slow")), result = { _, _, _ -> McpCallResult("ok") })
        val source = sourceOf(capped)
        source.refresh(listOf(cfg.copy(toolCallTimeoutMs = 900_000L)))
        assertEquals(600_000L, source.timeoutOf("mcp__demo_server__slow"))

        source.call("mcp__demo_server__slow", JsonObject(emptyMap()))
        assertEquals(600_000L, capped.calls.single().timeoutMs)

        source.refresh(listOf(cfg.copy(toolCallTimeoutMs = 120_000L)))
        assertEquals(120_000L, source.timeoutOf("mcp__demo_server__slow"))
        assertEquals(null, source.timeoutOf("shell"))
    }

    // ── 与注册表 / 执行器接线 ─────────────────────────────────────────────────

    @Test
    fun registryAndExecutorSeeInjectedToolsAndSkipRetry() = runTest {
        val fake = FakeMcpClient(tools = listOf(tool("echo")), result = { _, _, _ -> McpCallResult("hello") })
        val source = sourceOf(fake)
        val registry = ToolRegistry(toolCtx, extra = { source.snapshot() })
        val executor = ToolExecutor(
            executors = { registry.handlers() },
            noRetryTools = { registry.noRetryNames() },
            timeoutOf = { name -> source.timeoutOf(name) },
        )
        val name = "mcp__demo_server__echo"

        // 还没连上：工具不存在，报「未知工具」而不是崩。
        assertFalse(executor.run(name, JsonObject(emptyMap())).ok)

        source.refresh(listOf(cfg))
        assertTrue(registry.names().contains(name))
        assertEquals(name, registry.get(name)?.name)
        assertTrue(registry.noRetryNames().contains(name))
        assertTrue(registry.declarations().toString().contains(name))

        val result = executor.run(name, JsonObject(emptyMap()))
        assertTrue(result.ok)
        assertEquals("hello", result.output)
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun unknownToolMessageStaysVerbatim() = runTest {
        val executors: Map<String, suspend (JsonObject) -> String> = mapOf("shell" to { _ -> "ok" })
        val result = ToolExecutor(executors).run("brand_new_tool", JsonObject(emptyMap()))

        assertEquals("Unknown tool: brand_new_tool (registered: shell)", result.error)
    }

    // ── 空闲回收：只断连接，工具声明不能消失（静默失效回归） ─────────────────────

    @Test
    fun recycleIdleKeepsDeclarationsAndBudget() = runTest {
        val fake = FakeMcpClient(tools = listOf(tool("echo")), result = { _, _, _ -> McpCallResult("hello") })
        val source = sourceOf(fake)
        val name = "mcp__demo_server__echo"
        source.refresh(listOf(cfg))
        assertTrue(source.snapshot().any { it.name == name })

        assertTrue(source.recycleIdle(0L))

        assertEquals(1, fake.closes)
        // 工具表是模型看见的那一面：断开不能让它消失。
        assertTrue(source.snapshot().any { it.name == name })
        assertEquals(120_000L, source.timeoutOf(name))
        assertTrue(source.status.value[cfg.id] is McpError.Network)
    }

    @Test
    fun callAfterRecycleReconnectsAndSucceeds() = runTest {
        val clients = mutableListOf<FakeMcpClient>()
        val source = McpToolSource(factory = {
            FakeMcpClient(tools = listOf(tool("echo")), result = { _, _, _ -> McpCallResult("hello") })
                .also { clients += it }
        })
        source.refresh(listOf(cfg))
        source.recycleIdle(0L)
        assertEquals(1, clients.size)

        val out = source.call("mcp__demo_server__echo", JsonObject(emptyMap()))

        assertEquals("hello", out)
        assertEquals(2, clients.size)          // factory 又调了一次 = 真的按需重连
        assertEquals(1, clients[1].connects)   // 只握手一次，没重复建连
        assertEquals(1, clients[1].calls.size)
        assertNull(source.status.value[cfg.id]) // 重连成功后清掉「空闲已断开」
    }

    @Test
    fun reconnectFailureReturnsOneLineAndKeepsDeclarations() = runTest {
        var built = 0
        val source = McpToolSource(factory = {
            built++
            if (built == 1) {
                FakeMcpClient(tools = listOf(tool("echo")), result = { _, _, _ -> McpCallResult("hello") })
            } else {
                FakeMcpClient(failure = McpError.Network("connect timed out"))
            }
        })
        source.refresh(listOf(cfg))
        source.recycleIdle(0L)

        // 不抛穿：工具循环拿到一行文本继续跑。
        val out = source.call("mcp__demo_server__echo", JsonObject(emptyMap()))

        assertTrue(out.startsWith("MCP 工具不可用："))
        assertTrue(out.contains("重连失败"))
        assertTrue(out.contains("connect timed out"))
        assertTrue(source.snapshot().any { it.name == "mcp__demo_server__echo" })
        assertEquals(120_000L, source.timeoutOf("mcp__demo_server__echo"))
    }

    @Test
    fun closeClearsTable() = runTest {
        val source = sourceOf(FakeMcpClient(tools = listOf(tool("echo"))))
        source.refresh(listOf(cfg))
        assertTrue(source.snapshot().isNotEmpty())

        source.close()

        assertTrue(source.snapshot().isEmpty())
        assertNull(source.timeoutOf("mcp__demo_server__echo"))
    }

    @Test
    fun concurrentCallsAfterRecycleBuildOneClient() = runTest {
        val clients = mutableListOf<FakeMcpClient>()
        val source = McpToolSource(factory = {
            FakeMcpClient(tools = listOf(tool("echo")), result = { _, _, _ -> McpCallResult("hello") })
                .also { clients += it }
        })
        source.refresh(listOf(cfg))
        source.recycleIdle(0L)

        coroutineScope {
            repeat(3) { launch { source.call("mcp__demo_server__echo", JsonObject(emptyMap())) } }
        }

        assertEquals(2, clients.size) // 首连 + 一次重连；锁内二次检查防并发重复建连
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    private fun sourceOf(client: McpClient): McpToolSource = McpToolSource(factory = { client })

    private fun tool(name: String, description: String = "") = McpToolDef(
        name = name,
        description = description,
        inputSchema = buildJsonObject { put("type", "object") },
        readOnly = false,
        destructive = false,
    )

    private class FakeMcpClient(
        private val tools: List<McpToolDef> = emptyList(),
        private val failure: McpError? = null,
        private val result: suspend (String, JsonObject, Long) -> McpCallResult =
            { _, _, _ -> McpCallResult("ok") },
    ) : McpClient {

        data class Call(val name: String, val args: JsonObject, val timeoutMs: Long)

        override val state = MutableStateFlow<McpClientState>(McpClientState.Idle)
        val calls = mutableListOf<Call>()
        var connects = 0
        var closes = 0

        override suspend fun connect(): McpHandshake {
            connects++
            failure?.let {
                state.value = McpClientState.Failed(it)
                throw McpException(it)
            }
            state.value = McpClientState.Ready(tools.size)
            return McpHandshake("2025-06-18", "fake", "1.0", JsonObject(emptyMap()))
        }

        override suspend fun listTools(): List<McpToolDef> = tools

        override suspend fun callTool(name: String, args: JsonObject, timeoutMs: Long): McpCallResult {
            calls += Call(name, args, timeoutMs)
            return result(name, args, timeoutMs)
        }

        override suspend fun ping() = Unit

        override suspend fun close() {
            closes++
            state.value = McpClientState.Idle
        }
    }
}
