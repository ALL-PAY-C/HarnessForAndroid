package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.runtimehost.NodeEngine
import dev.dsh.mobile.runtimehost.NodeEngineException
import dev.dsh.mobile.runtimehost.NodeEngineState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能中心首屏的加载时序自检：冷启动时引擎可能已经被别处用「无插件」参数拉起来，
 * 这时 [NodeEngine.ensureStarted] 只是幂等返回，插件的 RPC 路径要等异步 plugins.load 完才注册。
 * 页面必须自己补 load 并等路径注册，不能把「还没加载完」当成「引擎坏了」。
 */
class SkillCenterLoadTest {

    private val catalog = """
        {"ok":true,"value":{"entries":[
          {"name":"hello-world","description":"打个招呼","globalEnabled":false,"projectEnabled":false,"effectiveEnabled":false,"updateSupported":false,"modelInvokable":true}
        ],"projectRoots":[]}}
    """.trimIndent()

    /** 最小假引擎：插件没 load 之前 /skill-manager 一律「未注册 RPC 路径」。 */
    private class FakeEngine(
        private val reply: JSONObject,
        private val alwaysMissing: Boolean = false,
        private val failureStage: String? = null,
    ) : NodeEngine {
        val loadedDirs = mutableListOf<String>()
        var rpcCalls = 0
        private val _state = MutableStateFlow<NodeEngineState>(NodeEngineState.Ready(1))

        override val state: StateFlow<NodeEngineState> = _state
        override suspend fun ensureStarted(pluginDirs: List<File>): Result<Int> = Result.success(1)
        override suspend fun call(method: String, params: JSONObject): JSONObject {
            if (method == "plugins.load") loadedDirs += params.optString("dir")
            return JSONObject().put("ok", true)
        }

        override suspend fun rpc(path: String, endpoint: String, payload: JSONObject): JSONObject {
            rpcCalls++
            failureStage?.let { throw NodeEngineException(it, "连接引擎失败") }
            if (alwaysMissing || loadedDirs.isEmpty()) {
                throw NodeEngineException("rpc", "未注册 RPC 路径 \"" + path + "\"")
            }
            return reply
        }

        override suspend fun stop() = Unit
        override fun isRunning(): Boolean = true
    }

    private val skillDir = File("/tmp/plugins/npm.dsh-skills-manager")

    @Test
    fun coldStartLoadsSkillPluginBeforeRpc() = runBlocking {
        val engine = FakeEngine(JSONObject(catalog))
        val result = loadSkills(engine, listOf(skillDir))
        assertTrue("首屏应读到技能列表，实际 $result", result is SkillUiState.Ready)
        assertEquals(1, (result as SkillUiState.Ready).entries.size)
        assertEquals(listOf(skillDir.absolutePath), engine.loadedDirs)
    }

    @Test
    fun unregisteredPathWithoutPluginDirsFailsWithRpcStage() = runBlocking {
        val engine = FakeEngine(JSONObject(catalog), alwaysMissing = true)
        val result = loadSkills(engine, emptyList())
        assertTrue("没有插件目录时只能报 rpc 失败，实际 $result", result is SkillUiState.Failed)
        assertEquals("rpc", (result as SkillUiState.Failed).stage)
        assertEquals(8, engine.rpcCalls)
    }

    @Test
    fun otherEngineErrorsFailFastWithoutLoadingPlugins() = runBlocking {
        val engine = FakeEngine(JSONObject(catalog), failureStage = "transport")
        val result = loadSkills(engine, listOf(skillDir))
        assertTrue("传输层失败不该重试，实际 $result", result is SkillUiState.Failed)
        assertEquals("transport", (result as SkillUiState.Failed).stage)
        assertEquals(1, engine.rpcCalls)
        assertEquals(emptyList<String>(), engine.loadedDirs)
    }

    @Test
    fun discoversSkillPluginOnDiskWhenRegistryIsEmpty() {
        val root = kotlin.io.path.createTempDirectory("plugins").toFile()
        fun plugin(id: String, pkg: String) {
            File(root, id).mkdirs()
            File(root, "$id/package.json").writeText(pkg)
        }
        plugin("npm.dsh-skills-manager", """{"name":"dsh-skills-manager","version":"0.2.0"}""")
        plugin("npm.billion-context", """{"name":"bili-native","version":"0.1.206"}""")
        File(root, "not-a-plugin").mkdirs()

        val dirs = discoverSkillPluginDirs(root)
        assertEquals(1, dirs.size)
        assertEquals("npm.dsh-skills-manager", dirs[0].name)
    }

    // ---- T10：冷启动目录并集（显式注册 ∪ 磁盘技能插件发现）----

    /** 铺一个带 package.json 的插件目录，返回该目录。 */
    private fun plugin(root: File, id: String, pkg: String): File {
        val dir = File(root, id)
        dir.mkdirs()
        File(dir, "package.json").writeText(pkg)
        return dir
    }

    private val skillPkg = """{"name":"dsh-skills-manager","version":"0.2.0"}"""
    private val bootPkg = """{"name":"dsh-boot-video-manager","version":"0.1.0"}"""

    /**
     * 部分注册：注册表里只有 boot 这类普通插件（非空），磁盘上的技能插件仍要被补进来。
     * 旧实现按「注册表非空就不扫盘」处理，技能中心因此永远读不到技能。
     */
    @Test
    fun keepsRegisteredPluginAndAddsDiskSkillPlugin() {
        val root = kotlin.io.path.createTempDirectory("plugins").toFile()
        val boot = plugin(root, "npm.dsh-boot-video-manager", bootPkg)
        val skills = plugin(root, "npm.dsh-skills-manager", skillPkg)

        val dirs = skillPluginDirs(listOf(boot), root)

        assertEquals(listOf(boot, skills), dirs)
    }

    /** 空注册：只能靠磁盘发现。 */
    @Test
    fun emptyRegistryDiscoversSkillPluginOnDisk() {
        val root = kotlin.io.path.createTempDirectory("plugins").toFile()
        plugin(root, "npm.dsh-boot-video-manager", bootPkg)
        val skills = plugin(root, "npm.dsh-skills-manager", skillPkg)

        assertEquals(listOf(skills), skillPluginDirs(emptyList(), root))
    }

    /** 同一目录既在注册表又在磁盘（路径写法还不同）只能出现一次，否则会重复 plugins.load。 */
    @Test
    fun overlappingRegistryAndDiskDirAppearsOnce() {
        val root = kotlin.io.path.createTempDirectory("plugins").toFile()
        plugin(root, "npm.dsh-skills-manager", skillPkg)
        val registered = File(root, "npm.dsh-skills-manager/.")

        val dirs = skillPluginDirs(listOf(registered), root)

        assertEquals(1, dirs.size)
        assertEquals("npm.dsh-skills-manager", dirs[0].canonicalFile.name)
    }

    /** 磁盘侧只补技能插件：普通插件目录、没有 package.json 的目录都不进并集。 */
    @Test
    fun diskSweepIgnoresNonSkillDirs() {
        val root = kotlin.io.path.createTempDirectory("plugins").toFile()
        plugin(root, "npm.dsh-boot-video-manager", bootPkg)
        File(root, "not-a-plugin").mkdirs()

        assertEquals(emptyList<File>(), skillPluginDirs(emptyList(), root))
    }

    /** 注册表里显式给的普通插件原样保留 —— boot 等插件的加载路径不能因为这次改动变样。 */
    @Test
    fun registeredOrdinaryPluginStaysEvenWithoutDiskRoot() {
        val missingRoot = File(kotlin.io.path.createTempDirectory("plugins").toFile(), "absent")
        val boot = File("/tmp/plugins/npm.dsh-boot-video-manager")

        assertEquals(listOf(boot), skillPluginDirs(listOf(boot), missingRoot))
    }
}
