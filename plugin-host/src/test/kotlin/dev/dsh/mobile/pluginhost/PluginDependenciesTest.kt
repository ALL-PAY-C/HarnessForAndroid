package dev.dsh.mobile.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * npm 运行期依赖解析的窄测：只认 dependencies、first-wins、上限收敛、scoped 路径、
 * 单包失败不拖累整体、递归到顶就停。
 *
 * 全程假 registry + 假解包，不发一个真请求。跑法：
 *   ./gradlew :plugin-host:testDebugUnitTest --tests '*PluginDependenciesTest'
 */
class PluginDependenciesTest {

    /** 假 registry：URL 里就能反推包名，方便断言「到底装了谁」。 */
    private class FakeRegistry(private val broken: Set<String> = emptySet()) : NpmRegistrySource {
        val fetched = mutableListOf<String>()
        val downloads = mutableListOf<String>()

        override fun fetchText(url: String): String {
            fetched += url
            val name = packageOf(url)
            if (name in broken) error("registry 502")
            // 解析器现在只取 packument（全部版本 + dist-tags），不再走 /<name>/<range>。
            return JSONObject()
                .put("dist-tags", JSONObject().put("latest", "1.0.0"))
                .put(
                    "versions",
                    JSONObject().put(
                        "1.0.0",
                        JSONObject().put("dist", JSONObject().put("tarball", "https://tarballs.test/" + name + ".tgz")),
                    ),
                )
                .toString()
        }

        override fun download(url: String, timeoutMs: Int): File {
            downloads += url
            return File(Files.createTempDirectory("tarball").toFile(), "pkg.tgz")
        }

        private fun packageOf(url: String): String =
            url.removePrefix("https://registry.npmjs.org/").replace("%2f", "/")
    }

    /** 解包换成假实现：目录里留个 package.json，递归就有东西可读，不必造真 tar.gz。 */
    private fun fakeUnpack(child: String?) = { _: File, dest: File ->
        dest.mkdirs()
        val body = if (child == null) JSONObject() else JSONObject().put("dependencies", JSONObject().put(child, "1.0.0"))
        File(dest, "package.json").writeText(body.toString())
    }

    private fun pluginDir(): File = Files.createTempDirectory("plugin").toFile()

    private fun pkg(deps: Map<String, String>): JSONObject =
        JSONObject().put("dependencies", JSONObject().apply { deps.forEach { (k, v) -> put(k, v) } })

    @Test
    fun installsOnlyRuntimeDependencies() {
        val dir = pluginDir()
        val manifest = JSONObject()
            .put("dependencies", JSONObject().put("runtime-dep", "1.0.0"))
            .put("devDependencies", JSONObject().put("dev-dep", "1.0.0"))
            .put("optionalDependencies", JSONObject().put("opt-dep", "1.0.0"))
            .put("peerDependencies", JSONObject().put("peer-dep", "1.0.0"))
        val registry = FakeRegistry()
        val warnings = mutableListOf<String>()

        val summary = NpmDependencyResolver(registry, fakeUnpack(null)).install(dir, manifest, warnings)

        val nodeModules = File(dir, "node_modules")
        assertTrue("runtime 依赖必须落地", File(nodeModules, "runtime-dep").isDirectory)
        assertFalse("devDependencies 不该装", File(nodeModules, "dev-dep").exists())
        assertFalse("optional 不该装", File(nodeModules, "opt-dep").exists())
        assertFalse("peer 不该装", File(nodeModules, "peer-dep").exists())
        assertEquals(1, summary.installed)
        assertEquals(1, registry.fetched.size)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun existingPackageIsSkipped() {
        val dir = pluginDir()
        // 先手放一个同名目录，模拟上次装过（版本可能不同）。
        File(dir, "node_modules/exists-dep").mkdirs()
        val registry = FakeRegistry()

        val summary = NpmDependencyResolver(registry, fakeUnpack(null))
            .install(dir, pkg(mapOf("exists-dep" to "1.0.0", "fresh-dep" to "1.0.0")), mutableListOf())

        assertEquals(1, summary.installed)
        assertEquals(1, summary.skipped)
        assertEquals(1, registry.downloads.size)
        assertTrue(File(dir, "node_modules/fresh-dep").isDirectory)
    }

    @Test
    fun capStopsInstallingAndWarns() {
        val dir = pluginDir()
        val registry = FakeRegistry()
        val warnings = mutableListOf<String>()

        val summary = NpmDependencyResolver(registry, fakeUnpack(null), maxPackages = 2)
            .install(dir, pkg(mapOf("a" to "1.0.0", "b" to "1.0.0", "c" to "1.0.0")), warnings)

        assertEquals(2, summary.installed)
        assertTrue(summary.capped)
        assertTrue(warnings.any { it.contains("上限") })
        assertFalse(File(dir, "node_modules/c").exists())
    }

    @Test
    fun scopedPackageKeepsScopePath() {
        val dir = pluginDir()
        val registry = FakeRegistry()

        NpmDependencyResolver(registry, fakeUnpack(null))
            .install(dir, pkg(mapOf("@scope/pkg" to "1.0.0")), mutableListOf())

        assertTrue(File(dir, "node_modules/@scope/pkg").isDirectory)
        assertEquals(
            "https://registry.npmjs.org/@scope%2fpkg",
            registry.fetched.single(),
        )
    }

    @Test
    fun brokenPackageDoesNotFailTheRest() {
        val dir = pluginDir()
        val registry = FakeRegistry(broken = setOf("bad-dep"))
        val warnings = mutableListOf<String>()

        val summary = NpmDependencyResolver(registry, fakeUnpack(null))
            .install(dir, pkg(mapOf("bad-dep" to "1.0.0", "good-dep" to "1.0.0")), warnings)

        assertEquals(1, summary.installed)
        assertEquals(listOf("bad-dep"), summary.failed)
        assertFalse("失败包不留半成品", File(dir, "node_modules/bad-dep").exists())
        assertTrue(File(dir, "node_modules/good-dep").isDirectory)
        assertTrue(warnings.any { it.contains("安装失败") })
        assertFalse(
            "临时目录要清掉",
            File(dir, "node_modules").listFiles().orEmpty().any { it.name.startsWith(".dsh-dep-") },
        )
    }

    @Test
    fun recursionStopsAtDepthLimit() {
        val dir = pluginDir()
        val registry = FakeRegistry()

        val summary = NpmDependencyResolver(registry, fakeUnpack("child-dep"), maxDepth = 1)
            .install(dir, pkg(mapOf("top-dep" to "1.0.0")), mutableListOf())

        assertEquals(1, summary.installed)
        assertTrue(File(dir, "node_modules/top-dep").isDirectory)
        assertFalse(
            "到深度上限就不再往里装",
            File(dir, "node_modules/top-dep/node_modules/child-dep").exists(),
        )
    }

    @Test
    fun budgetExhaustedStopsEarly() {
        val dir = pluginDir()
        val registry = FakeRegistry()
        val warnings = mutableListOf<String>()

        val summary = NpmDependencyResolver(registry, fakeUnpack(null), budgetMs = -1)
            .install(dir, pkg(mapOf("a" to "1.0.0")), warnings)

        assertEquals(0, summary.installed)
        assertTrue(summary.capped)
        assertTrue(warnings.any { it.contains("时间预算") })
        assertTrue(registry.fetched.isEmpty())
    }
}
