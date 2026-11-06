package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.runtimehost.NodeEngine
import dev.dsh.mobile.runtimehost.NodeEngineException
import dev.dsh.mobile.runtimehost.NodeEngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 任务 66：真机冷启动暴露的两个竞态。
 *
 * A（03:09:33）：node 侧 plugins.load 明明成功，宿主同一时刻抛 `[transport] 引擎未连接` → 卡片显示
 * 「加载失败」，用户得点一次重试。这里钉住「只对 transport 有界重试，重试成功落 Applied 且不写失败回执」。
 * B（03:10:17）：每回写一个端口就全量重注册，两个网关插件之间露出 0.8 s 的混合态 → 批次只 flush 一次。
 */
class NodeBackedPluginTransportRetryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * 可编排的引擎：第 1..n 次 `plugins.load` 按 [failures] 抛异常，之后成功。
     * [urlFor] 决定 rpc status 回哪个端口，用来区分两个网关插件。
     */
    private class FlakyNodeEngine(
        private val failures: List<Throwable> = emptyList(),
        private val urlFor: (String) -> String = { "http://127.0.0.1:7788" },
    ) : NodeEngine {
        var loadCalls = 0

        override val state: StateFlow<NodeEngineState> = MutableStateFlow(NodeEngineState.Stopped)

        override suspend fun ensureStarted(pluginDirs: List<File>): Result<Int> = Result.success(4321)

        override suspend fun call(method: String, params: JSONObject): JSONObject {
            if (method != "plugins.load") return JSONObject()
            loadCalls++
            failures.getOrNull(loadCalls - 1)?.let { throw it }
            return JSONObject().put("name", "np").put("version", "1.0.0")
        }

        override suspend fun rpc(path: String, endpoint: String, payload: JSONObject): JSONObject =
            JSONObject().put("url", urlFor(path))

        override suspend fun stop() {}
        override fun isRunning(): Boolean = false
    }

    private fun nodePlugin(id: String, dir: File) =
        NodeBackedPlugin(PluginManifestFile(id = id, name = id, version = "1"), dir)

    // ------------------------------------------------------------ 竞态 A：transport 重试

    @Test
    fun `transport 失败一次后重试成功落 Applied`() = runBlocking {
        val plugin = nodePlugin("pl.gateway.local", tmp.newFolder())
        val engine = FlakyNodeEngine(listOf(NodeEngineException("transport", "引擎未连接")))
        val host = PluginHost()
        host.load(plugin)

        NodeBackedPlugin.loadNodePlugins(host, engine, listOf(plugin))

        assertEquals("1 次失败 + 1 次重试成功", 2, engine.loadCalls)
        assertTrue("重试成功要落已应用：" + plugin.status.value, plugin.status.value is NodePluginStatus.Applied)
        assertNull("重试成功不许把失败写进插件管理行（noteLoadResult 收到 null）", host.plugins.value.single().loadError)
    }

    @Test
    fun `load 阶段失败不重试`() = runBlocking {
        val plugin = nodePlugin("pl.gateway.local", tmp.newFolder())
        val engine = FlakyNodeEngine(listOf(NodeEngineException("load", "package.json 缺失")))
        val host = PluginHost()
        host.load(plugin)

        NodeBackedPlugin.loadNodePlugins(host, engine, listOf(plugin))

        assertEquals("非 transport 异常重试只是把同一个错重演一遍", 1, engine.loadCalls)
        val failed = plugin.status.value as NodePluginStatus.Failed
        assertEquals("load", failed.stage)
        assertTrue("失败回执要带上 stage：" + host.plugins.value.single().loadError, host.plugins.value.single().loadError.orEmpty().startsWith("[load]"))
    }

    @Test
    fun `transport 连续失败只重试两次就落 Failed`() = runBlocking {
        val plugin = nodePlugin("pl.gateway.local", tmp.newFolder())
        val engine = FlakyNodeEngine(
            listOf(
                NodeEngineException("transport", "引擎未连接"),
                NodeEngineException("transport", "引擎未连接"),
                NodeEngineException("transport", "引擎未连接"),
                NodeEngineException("transport", "引擎未连接"),
            ),
        )
        val host = PluginHost()
        host.load(plugin)

        NodeBackedPlugin.loadNodePlugins(host, engine, listOf(plugin))

        assertEquals("1 次首发 + 2 次重试 = 3，必须收敛", 3, engine.loadCalls)
        val failed = plugin.status.value as NodePluginStatus.Failed
        assertEquals("transport", failed.stage)
    }

    @Test
    fun `transport 判据只看 stage 与引擎未连接`() {
        assertTrue(NodeBackedPlugin.isTransportFailure(NodeEngineException("transport", "任意消息")))
        assertTrue(NodeBackedPlugin.isTransportFailure(RuntimeException("分帧超时：[transport] 引擎未连接")))
        assertTrue("老引擎包不带 stage 时靠消息兜底", NodeBackedPlugin.isTransportFailure(RuntimeException("引擎未连接")))
        assertEquals(false, NodeBackedPlugin.isTransportFailure(NodeEngineException("load", "清单解析失败")))
        assertEquals(false, NodeBackedPlugin.isTransportFailure(NodeEngineException("rpc", "no gateway")))
    }

    // ------------------------------------------------------------ 竞态 B：批次只重注册一次

    @Test
    fun `两插件回写后只触发一次全量注册`() = runBlocking {
        val dirs = listOf(tmp.newFolder(), tmp.newFolder())
        dirs.forEach { it.resolve(NodeBackedPlugin.UI_PAGE_FILE).writeText("<b>x</b>") }
        val plugins = listOf(
            nodePlugin("pl.gateway.local", dirs[0]),
            nodePlugin("pl.gateway.stream", dirs[1]),
        )
        val engine = FlakyNodeEngine(urlFor = { path ->
            if (path == "/gateway.local") "http://127.0.0.1:7071" else "http://127.0.0.1:7072"
        })
        val host = PluginHost()
        plugins.forEach { host.load(it) }

        val written = mutableListOf<Pair<String, String>>()
        var flushes = 0
        val batch = GatewayUrlBatch(record = { id, url -> written += id to url }, flush = { flushes++ })
        try {
            NodeBackedPlugin.loadNodePlugins(host, engine, plugins, onGatewayUrl = batch::accept)
        } finally {
            batch.close()
        }

        assertEquals(
            listOf("pl.gateway.local" to "http://127.0.0.1:7071", "pl.gateway.stream" to "http://127.0.0.1:7072"),
            written,
        )
        assertEquals("两个插件的端口要一次性全部落盘，全量注册只许 1 次", 1, flushes)
    }

    @Test
    fun `没有端口回写的批次不额外注册`() {
        var flushes = 0
        val batch = GatewayUrlBatch(record = { _, _ -> }, flush = { flushes++ })
        batch.close()
        assertEquals("一个端口都没回写就不该多打一条 MODEL_SOURCES", 0, flushes)

        batch.accept("pl.gateway.local", "http://127.0.0.1:7071")
        batch.close()
        batch.close()
        assertEquals("flush 幂等：重入 close 不再注册第二次", 1, flushes)
    }
}
