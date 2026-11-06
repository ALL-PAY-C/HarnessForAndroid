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
 * Node 插件自带 WebUI：加载成功后 gateway 端口回写。
 *
 * 断言落在「回写回调拿到什么、rpc 打在哪条路径」，不碰 Compose：卡片是数据，
 * 渲染是另一层的事；自带页面走全屏 WebView（见 GatewayWebUi），不再塞进卡片。
 */
class NodeBackedPluginUiTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun nodePlugin(id: String, dir: File) =
        NodeBackedPlugin(PluginManifestFile(id = id, name = id, version = "1"), dir)

    // ---------------------------------------------------------------- 端口回写

    private class FakeNodeEngine(
        private val rpcHandler: (path: String, endpoint: String) -> JSONObject = { _, _ -> JSONObject() },
    ) : NodeEngine {
        val rpcCalls = mutableListOf<Pair<String, String>>()
        override val state: StateFlow<NodeEngineState> = MutableStateFlow(NodeEngineState.Stopped)

        override suspend fun ensureStarted(pluginDirs: List<File>): Result<Int> = Result.success(4321)

        override suspend fun call(method: String, params: JSONObject): JSONObject =
            JSONObject().put("name", "np").put("version", "1.0.0")

        override suspend fun rpc(path: String, endpoint: String, payload: JSONObject): JSONObject {
            rpcCalls += path to endpoint
            return rpcHandler(path, endpoint)
        }

        override suspend fun stop() {}
        override fun isRunning(): Boolean = false
    }

    @Test
    fun `加载后回写 gateway 端口`() = runBlocking {
        val dir = tmp.newFolder()
        dir.resolve(NodeBackedPlugin.UI_PAGE_FILE).writeText("<b>x</b>")
        val engine = FakeNodeEngine { _, _ -> JSONObject().put("url", "http://127.0.0.1:7788") }
        val plugin = nodePlugin("pl.gateway.local", dir)
        val host = PluginHost()
        host.load(plugin)
        val written = mutableListOf<Pair<String, String>>()

        NodeBackedPlugin.loadNodePlugins(host, engine, listOf(plugin), onGatewayUrl = { id, url -> written += id to url })

        assertEquals("路径按 id 派生：pl.gateway.local → /gateway.local", listOf("/gateway.local" to "status"), engine.rpcCalls)
        assertEquals(listOf("pl.gateway.local" to "http://127.0.0.1:7788"), written)
        assertTrue(plugin.status.value is NodePluginStatus.Applied)
    }

    @Test
    fun `gateway 取不到时不影响加载成功态`() = runBlocking {
        val dir = tmp.newFolder()
        dir.resolve(NodeBackedPlugin.UI_PAGE_FILE).writeText("<b>x</b>")
        val engine = FakeNodeEngine { _, _ -> throw NodeEngineException("rpc", "no gateway") }
        val plugin = nodePlugin("pl.gateway.stream", dir)
        val host = PluginHost()
        host.load(plugin)
        var called = 0

        NodeBackedPlugin.loadNodePlugins(host, engine, listOf(plugin), onGatewayUrl = { _, _ -> called++ })

        assertEquals("rpc 失败不许回调", 0, called)
        assertTrue("插件照样是已应用", plugin.status.value is NodePluginStatus.Applied)
    }

    @Test
    fun `没有注入回调时不打 gateway rpc`() = runBlocking {
        val dir = tmp.newFolder()
        dir.resolve(NodeBackedPlugin.UI_PAGE_FILE).writeText("<b>x</b>")
        val engine = FakeNodeEngine { _, _ -> JSONObject().put("url", "http://127.0.0.1:7788") }
        val plugin = nodePlugin("pl.gateway.stream", dir)
        val host = PluginHost()
        host.load(plugin)

        NodeBackedPlugin.loadNodePlugins(host, engine, listOf(plugin))

        assertTrue("默认 null 就一次 rpc 都不打", engine.rpcCalls.isEmpty())
    }

    @Test
    fun `rpc 路径按插件 id 派生`() {
        assertEquals("/gateway.local", NodeBackedPlugin.gatewayRpcPath("pl.gateway.local"))
        assertEquals("/gateway.stream", NodeBackedPlugin.gatewayRpcPath("pl.gateway.stream"))
        assertNull("不在 pl. 命名下的插件没有网关注册", NodeBackedPlugin.gatewayRpcPath("np.other"))
        assertNull(NodeBackedPlugin.gatewayRpcPath("pl."))
    }

    @Test
    fun `非网关 id 即使有 ui html 也不打 rpc`() = runBlocking {
        val dir = tmp.newFolder()
        dir.resolve(NodeBackedPlugin.UI_PAGE_FILE).writeText("<b>x</b>")
        val engine = FakeNodeEngine { _, _ -> JSONObject().put("url", "http://127.0.0.1:7788") }
        val plugin = nodePlugin("np.other", dir)
        val host = PluginHost()
        host.load(plugin)
        var called = 0

        NodeBackedPlugin.loadNodePlugins(host, engine, listOf(plugin), onGatewayUrl = { _, _ -> called++ })

        assertTrue("没有网关路径就不该打 rpc：" + engine.rpcCalls, engine.rpcCalls.isEmpty())
        assertEquals(0, called)
    }
}
