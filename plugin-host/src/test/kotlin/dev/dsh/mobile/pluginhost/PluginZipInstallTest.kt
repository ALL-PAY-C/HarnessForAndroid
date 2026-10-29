package dev.dsh.mobile.pluginhost

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * zip 插件包（package.json + 入口）的窄测：分发格式从 .apk 改成 .zip 之后，
 * 装得上、package.json 留在落盘目录、同名重装不碰别的插件、失败留下完整堆栈。
 *
 * 全部走真实文件系统（无 Robolectric、无设备）。跑法：
 *   ./gradlew :plugin-host:testDebugUnitTest --tests '*PluginZipInstallTest'
 */
class PluginZipInstallTest {

    private class Harness {
        val root: File = Files.createTempDirectory("plugzip").toFile()
        val registry = MemoryPackageRegistry()
        var activateFailureFor: String? = null
        val activated = mutableListOf<String>()

        val service = PluginPackageService(
            root = root,
            registry = registry,
            onVerify = { _, _ -> },
            onActivate = { _, dir ->
                if (dir.name == activateFailureFor) throw IllegalStateException("激活失败：" + dir.name)
                activated += dir.name
            },
        )

        /** 把 entries（相对路径 → 文本）打成 zip。 */
        fun zip(vararg entries: Pair<String, String>): File {
            val file = File(Files.createTempDirectory("plug-zip").toFile(), "plugin.zip")
            ZipOutputStream(file.outputStream()).use { out ->
                entries.forEach { (name, body) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(body.toByteArray())
                    out.closeEntry()
                }
            }
            return file
        }

        /** npm 形态的插件包：根上 package.json + dsh.main 指向的入口。 */
        fun npmZip(name: String = "zip-plugin", version: String = "1.2.3", prefix: String = ""): File =
            zip(
                prefix + "package.json" to
                    "{\"name\":\"" + name + "\",\"version\":\"" + version +
                    "\",\"dsh\":{\"main\":\"index.js\",\"title\":\"" + name + "\"}}",
                prefix + "index.js" to "export default { id: '" + name + "' }",
                prefix + "lib/util.js" to "export const x = 1",
            )

        fun cleanup() {
            root.deleteRecursively()
        }
    }

    private fun <T> withHarness(block: (Harness) -> T): T {
        val h = Harness()
        try {
            return block(h)
        } finally {
            h.cleanup()
        }
    }

    // ------------------------------------------------------------- zip 安装

    @Test
    fun zipWithPackageJsonInstalls() = withHarness<Unit> { h ->
        val result = runBlocking { h.service.installArchive(h.npmZip()) }

        assertTrue(result.log.joinToString("\n"), result.ok)
        assertEquals("npm.zip-plugin", result.id)
        assertEquals("1.2.3", result.version)
        assertNotNull(h.service.activeDir("npm.zip-plugin"))
        assertNotNull(h.registry.raw("npm.zip-plugin"))
    }

    @Test
    fun packageJsonStaysInActiveDirectory() = withHarness<Unit> { h ->
        val result = runBlocking { h.service.installArchive(h.npmZip()) }
        assertTrue(result.ok)

        val active = h.service.activeDir("npm.zip-plugin")!!
        assertTrue(File(active, "package.json").isFile)
        assertTrue(File(active, "index.js").isFile)
        assertTrue(File(active, "lib/util.js").isFile)

        val kept = JSONObject(File(active, "package.json").readText())
        assertEquals("zip-plugin", kept.getString("name"))
        assertEquals("index.js", kept.getJSONObject("dsh").getString("main"))
    }

    @Test
    fun installLogReportsKeptManifests() = withHarness<Unit> { h ->
        val result = runBlocking { h.service.installArchive(h.npmZip()) }
        assertTrue(result.ok)
        val kept = result.log.firstOrNull { it.startsWith("manifests kept=") }
        assertNotNull(result.log.joinToString("\n"), kept)
        assertTrue(kept!!, kept.contains("package.json"))
        // 安装日志也要能事后翻出来。
        assertTrue(h.service.log().any { it.contains("OK install npm.zip-plugin@1.2.3") })
    }

    @Test
    fun nestedZipballLayoutIsFlattenedBeforeInstall() = withHarness<Unit> { h ->
        // GitHub zipball 形态：所有条目都在 <repo>-<branch>/ 下面。
        val result = runBlocking { h.service.installArchive(h.npmZip(prefix = "repo-main/")) }
        assertTrue(result.log.joinToString("\n"), result.ok)

        val active = h.service.activeDir("npm.zip-plugin")!!
        assertTrue(File(active, "package.json").isFile)
        assertTrue(File(active, "index.js").isFile)
        assertFalse(File(active, "repo-main").exists())
    }

    // ------------------------------------------------------- 同名清理范围

    @Test
    fun reinstallingSameIdLeavesOtherPluginsIntact() = withHarness<Unit> { h ->
        assertTrue(runBlocking { h.service.installArchive(h.npmZip(name = "alpha", version = "1.0.0")) }.ok)
        assertTrue(runBlocking { h.service.installArchive(h.npmZip(name = "beta", version = "2.0.0")) }.ok)

        // 同名重装：只换 alpha 自己的条目与版本目录。
        assertTrue(runBlocking { h.service.installArchive(h.npmZip(name = "alpha", version = "1.1.0")) }.ok)

        assertEquals("1.1.0", h.service.activeDir("npm.alpha")!!.name)
        assertEquals("2.0.0", h.service.activeDir("npm.beta")!!.name)
        assertTrue(File(h.service.activeDir("npm.beta")!!, "package.json").isFile)
        assertNotNull(h.registry.raw("npm.beta"))
        // 旧版本还留着给回滚用。
        assertTrue(File(h.root, "npm.alpha/versions/1.0.0").isDirectory)
    }

    // --------------------------------------------------------- 失败留堆栈

    @Test
    fun installFailureKeepsFullStackInLogAndLastError() = withHarness<Unit> { h ->
        h.activateFailureFor = "1.2.3"
        val failed = runBlocking { h.service.installArchive(h.npmZip()) }

        assertFalse(failed.ok)
        val joined = failed.log.joinToString("\n")
        assertTrue(joined, joined.contains("激活失败"))
        // 不只是 message：要有真正的栈帧。
        assertTrue(joined, joined.contains("at dev.dsh.mobile"))
        assertTrue(joined, joined.contains("IllegalStateException"))

        val recorded = h.service.lastError("npm.zip-plugin")
        assertNotNull(recorded)
        assertTrue(recorded!!, recorded.contains("激活失败"))
        assertTrue(recorded, recorded.contains("at dev.dsh.mobile"))

        // install.log 里也要能翻到（只留前若干行，不冲掉 200 行的尾巴）。
        assertTrue(h.service.log().any { it.contains("ERROR install npm.zip-plugin") })
    }

    @Test
    fun packageWithoutAnyManifestFailsWithZipHint() = withHarness<Unit> { h ->
        val zip = h.zip("readme.md" to "no manifest here")
        val failed = runBlocking { h.service.installArchive(zip) }

        assertFalse(failed.ok)
        val text = (failed.error ?: "") + "\n" + failed.log.joinToString("\n")
        assertTrue(text, text.contains(".zip"))
    }
}
