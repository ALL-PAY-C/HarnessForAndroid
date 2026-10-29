package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.runtimehost.NodeEngine
import dev.dsh.mobile.runtimehost.NodeEngineException
import dev.dsh.mobile.runtimehost.NodeEngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NodeRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `no js entry is declarative`() {
        val dir = tmp.newFolder()
        dir.resolve("plugin.json").writeText("{}")
        assertEquals(PluginKind.Declarative, classifyPlugin(dir))
    }

    @Test
    fun `js entry without external deps is rhino`() {
        val dir = tmp.newFolder()
        dir.resolve("index.js").writeText("export function activate(){ return {} }")
        assertEquals(PluginKind.Rhino, classifyPlugin(dir))
    }

    @Test
    fun `node builtin import routes to node`() {
        val dir = tmp.newFolder()
        dir.resolve("index.js").writeText("import fs from 'node:fs'; export function activate(){ return {} }")
        assertEquals(PluginKind.Node, classifyPlugin(dir))
    }

    @Test
    fun `deepseek scoped import routes to node`() {
        val dir = tmp.newFolder()
        dir.resolve("index.js").writeText("import { x } from '@deepseek-ai/x'; export function activate(){ return {} }")
        assertEquals(PluginKind.Node, classifyPlugin(dir))
    }

    @Test
    fun `bare npm import routes to node`() {
        val dir = tmp.newFolder()
        dir.resolve("index.js")
            .writeText("import Schema from 'schemastery'; export const Config = Schema.object({}); export function apply(){ return {} }")
        assertEquals(PluginKind.Node, classifyPlugin(dir))
    }

    // ---- loadNodePlugins 编排回归：禁用不执行 / 取消上抛 / 引擎失败 / 正常应用 ----

    private class FakeNodeEngine(
        private val ensureResult: Result<Int> = Result.success(4321),
        private val callHandler: (method: String, params: JSONObject) -> JSONObject =
            { _, _ -> JSONObject().put("name", "np").put("version", "1.0.0") },
    ) : NodeEngine {
        var ensureCalls = 0; private set
        var callCalls = 0; private set
        val callDirs = mutableListOf<String>()
        override val state: StateFlow<NodeEngineState> = MutableStateFlow(NodeEngineState.Stopped)

        override suspend fun ensureStarted(pluginDirs: List<File>): Result<Int> {
            ensureCalls++
            return ensureResult
        }

        override suspend fun call(method: String, params: JSONObject): JSONObject {
            callCalls++
            callDirs += params.optString("dir")
            return callHandler(method, params)
        }

        override suspend fun rpc(path: String, endpoint: String, payload: JSONObject): JSONObject = JSONObject()
        override suspend fun stop() {}
        override fun isRunning(): Boolean = false
    }

    private fun nodePlugin(id: String, dir: File = tmp.newFolder()) =
        NodeBackedPlugin(PluginManifestFile(id = id, name = id, version = "1"), dir)

    @Test
    fun `disabled node plugin does not execute js`() = runBlocking {
        val engine = FakeNodeEngine()
        val host = PluginHost()
        val p = nodePlugin("np.disabled")
        host.load(p)
        NodeBackedPlugin.loadNodePlugins(host, engine, listOf(p), isEnabled = { false })
        assertEquals(0, engine.ensureCalls)
        assertEquals(0, engine.callCalls)
        val s = p.status.value
        assertTrue(s is NodePluginStatus.Failed && s.stage == "disabled")
    }

    @Test
    fun `cancellation during call is rethrown`() {
        val engine = FakeNodeEngine(callHandler = { _, _ -> throw CancellationException("cancelled") })
        val host = PluginHost()
        val p = nodePlugin("np.cancel")
        host.load(p)
        try {
            runBlocking { NodeBackedPlugin.loadNodePlugins(host, engine, listOf(p)) }
            fail("预期 CancellationException")
        } catch (e: CancellationException) {
            // 取消必须上抛，不能被当成插件加载失败吞掉。
        }
    }

    @Test
    fun `node plugin applies on success`() = runBlocking {
        val engine = FakeNodeEngine()
        val host = PluginHost()
        val dir = tmp.newFolder()
        val p = nodePlugin("np.ok", dir)
        host.load(p)
        NodeBackedPlugin.loadNodePlugins(host, engine, listOf(p))
        assertEquals(1, engine.ensureCalls)
        assertEquals(1, engine.callCalls)
        assertEquals(dir.absolutePath, engine.callDirs.single())
        assertTrue(p.status.value is NodePluginStatus.Applied)
    }

    @Test
    fun `engine failure marks plugins failed`() = runBlocking {
        val engine = FakeNodeEngine(ensureResult = Result.failure(IllegalStateException("boom")))
        val host = PluginHost()
        val p = nodePlugin("np.enginefail")
        host.load(p)
        NodeBackedPlugin.loadNodePlugins(host, engine, listOf(p))
        assertEquals(1, engine.ensureCalls)
        assertEquals(0, engine.callCalls)
        val s = p.status.value
        assertTrue(s is NodePluginStatus.Failed && s.stage == "engine")
    }

    @Test
    fun `engine-side failure preserves stage and message`() = runBlocking {
        val engine = FakeNodeEngine(callHandler = { _, _ -> throw NodeEngineException("apply", "依赖未满足") })
        val host = PluginHost()
        val p = nodePlugin("np.stagefail")
        host.load(p)
        NodeBackedPlugin.loadNodePlugins(host, engine, listOf(p))
        assertEquals(1, engine.ensureCalls)
        assertEquals(1, engine.callCalls)
        val s = p.status.value
        assertTrue(s is NodePluginStatus.Failed)
        val failed = s as NodePluginStatus.Failed
        assertEquals("apply", failed.stage)
        assertEquals("依赖未满足", failed.message)
    }
}
