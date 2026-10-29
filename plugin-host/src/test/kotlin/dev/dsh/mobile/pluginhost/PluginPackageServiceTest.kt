package dev.dsh.mobile.pluginhost

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 事务化安装 / 版本化 / 回滚 / 校验 / 修复 的窄测。
 *
 * 全部在真实文件系统上跑（无 Robolectric、无设备）：[PluginPackageService] 只依赖
 * root 目录 + [PackageRegistry]，不碰 Context。跑法：
 *   ./gradlew :plugin-host:testDebugUnitTest --tests '*PluginPackageServiceTest'
 */
class PluginPackageServiceTest {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private class Harness(
        val root: File = Files.createTempDirectory("plugpkg").toFile(),
        val registry: MemoryPackageRegistry = MemoryPackageRegistry(),
    ) {
        var verifyFailure: String? = null
        var activateFailureFor: String? = null
        val activated = mutableListOf<String>()

        val service = PluginPackageService(
            root = root,
            registry = registry,
            onVerify = { _, _ -> verifyFailure?.let { throw IllegalStateException(it) } },
            onActivate = { _, dir ->
                if (dir.name == activateFailureFor) throw IllegalStateException("激活失败：" + dir.name)
                activated += dir.name
            },
        )

        /** 造一个可安装的源目录（不在 root 下，模拟工作区里的插件源）。 */
        fun sourceDir(id: String, version: String, body: String = "payload-" + version): File {
            val dir = Files.createTempDirectory("pkg-src").toFile()
            dir.mkdirs()
            File(dir, "plugin.json").writeText(
                "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"version\":\"" + version + "\"}",
            )
            File(dir, "payload.txt").writeText(body)
            return dir
        }

        fun install(id: String, version: String, body: String = "payload-" + version): PackageResult = runBlocking {
            service.installFromDirectory(sourceDir(id, version, body))
        }

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

    // ------------------------------------------------------------ 版本化布局

    @Test
    fun installCreatesVersionedLayoutPointerAndRegistry() = withHarness<Unit> { h ->
        val result = h.install("dsh.demo", "1.0.0")
        assertTrue(result.error, result.ok)
        assertEquals("dsh.demo", result.id)
        assertEquals("1.0.0", result.version)

        val dir = File(h.root, "dsh.demo")
        assertEquals("1.0.0", File(dir, "current").readText().trim())
        assertTrue(File(dir, "versions/1.0.0/plugin.json").isFile)
        assertEquals("payload-1.0.0", File(dir, "versions/1.0.0/payload.txt").readText())
        assertEquals(File(dir, "versions/1.0.0").canonicalPath, h.service.activeDir("dsh.demo")!!.canonicalPath)
        assertNotNull(h.registry.raw("dsh.demo"))
        // staging 不留垃圾
        assertEquals(0, File(h.root, ".staging").listFiles()?.size ?: 0)
    }

    @Test
    fun upgradeKeepsOldVersionForRollback() = withHarness<Unit> { h ->
        assertTrue(h.install("dsh.demo", "1.0.0").ok)
        assertTrue(h.install("dsh.demo", "2.0.0").ok)

        val dir = File(h.root, "dsh.demo")
        assertEquals("2.0.0", File(dir, "current").readText().trim())
        // 旧版本保留 = 可回滚
        assertEquals("payload-1.0.0", File(dir, "versions/1.0.0/payload.txt").readText())
        assertEquals("payload-2.0.0", File(dir, "versions/2.0.0/payload.txt").readText())
        assertEquals(File(dir, "versions/2.0.0").canonicalPath, h.service.activeDir("dsh.demo")!!.canonicalPath)
    }

    @Test
    fun reinstallSameVersionReplacesPayloadWithoutLeftovers() = withHarness<Unit> { h ->
        assertTrue(h.install("dsh.demo", "1.0.0", body = "old").ok)
        val again = h.install("dsh.demo", "1.0.0", body = "new")
        assertTrue(again.error, again.ok)

        val dir = File(h.root, "dsh.demo")
        assertEquals("new", File(dir, "versions/1.0.0/payload.txt").readText())
        assertEquals(listOf("1.0.0"), File(dir, "versions").listFiles()!!.map { it.name })
        // 备份目录不留在 .staging
        assertEquals(0, File(h.root, ".staging").listFiles()?.size ?: 0)
    }

    @Test
    fun pointerIsHealedWhenCurrentFileIsMissing() = withHarness<Unit> { h ->
        assertTrue(h.install("dsh.demo", "1.0.0").ok)
        assertTrue(h.install("dsh.demo", "2.0.0").ok)
        val pointer = File(h.root, "dsh.demo/current")
        assertTrue(pointer.delete())

        val active = h.service.activeDir("dsh.demo")
        assertNotNull(active)
        assertEquals("2.0.0", active!!.name)
        assertEquals("2.0.0", pointer.readText().trim())
    }

    // ---------------------------------------------------------------- 事务回滚

    @Test
    fun activationFailureRollsBackToPreviousVersion() = withHarness<Unit> { h ->
        assertTrue(h.install("dsh.demo", "1.0.0").ok)
        val before = h.registry.raw("dsh.demo")

        h.activateFailureFor = "2.0.0"
        val failed = h.install("dsh.demo", "2.0.0", body = "boom")

        assertFalse(failed.ok)
        assertTrue(failed.error!!.contains("激活失败"))
        assertTrue(failed.active) // 旧版本仍然可加载

        val dir = File(h.root, "dsh.demo")
        assertEquals("1.0.0", File(dir, "current").readText().trim())
        assertFalse(File(dir, "versions/2.0.0").exists())
        assertEquals("payload-1.0.0", File(dir, "versions/1.0.0/payload.txt").readText())
        assertEquals(before, h.registry.raw("dsh.demo"))
        assertNotNull(h.service.lastError("dsh.demo"))
        assertEquals(0, File(h.root, ".staging").listFiles()?.size ?: 0)
    }

    @Test
    fun activationFailureOnSameVersionRestoresPreviousPayload() = withHarness<Unit> { h ->
        assertTrue(h.install("dsh.demo", "1.0.0", body = "good").ok)
        h.activateFailureFor = "1.0.0"
        val failed = h.install("dsh.demo", "1.0.0", body = "bad")
        assertFalse(failed.ok)

        val dir = File(h.root, "dsh.demo")
        assertEquals("good", File(dir, "versions/1.0.0/payload.txt").readText())
        assertEquals("1.0.0", File(dir, "current").readText().trim())
    }

    @Test
    fun verifyFailureLeavesNoTrace() = withHarness<Unit> { h ->
        h.verifyFailure = "清单与源不匹配"
        val failed = h.install("dsh.demo", "1.0.0")
        assertFalse(failed.ok)
        assertTrue(failed.error!!.contains("清单与源不匹配"))
        assertFalse(File(h.root, "dsh.demo/versions").exists())
        assertNull(h.registry.raw("dsh.demo"))
        assertEquals(0, File(h.root, ".staging").listFiles()?.size ?: 0)
    }

    @Test
    fun pointerWriteFailureDoesNotCommitNewPayloadOrRegistry() = withHarness<Unit> { h ->
        assertTrue(h.install("dsh.demo", "1.0.0", body = "good").ok)
        val before = h.registry.raw("dsh.demo")
        val pointer = File(h.root, "dsh.demo/current")
        assertTrue(pointer.delete())
        assertTrue(pointer.mkdir())
        File(pointer, "blocked").writeText("keep")

        val failed = h.install("dsh.demo", "2.0.0", body = "bad")

        assertFalse("pointer replacement failure must fail the transaction", failed.ok)
        assertEquals(before, h.registry.raw("dsh.demo"))
        assertEquals("good", File(h.root, "dsh.demo/versions/1.0.0/payload.txt").readText())
        assertFalse(File(h.root, "dsh.demo/versions/2.0.0").exists())
        assertEquals(listOf("1.0.0"), h.activated)
    }

    // -------------------------------------------------------------- 老布局兼容

    @Test
    fun legacyFlatInstallIsStillLoadableAndMigratedOnUpgrade() = withHarness<Unit> { h ->
        // 老版本装出来的扁平结构：plugins/<id>/plugin.json
        val flat = File(h.root, "legacy.plugin")
        flat.mkdirs()
        File(flat, "plugin.json").writeText("{\"id\":\"legacy.plugin\",\"name\":\"L\",\"version\":\"1.0.0\"}")
        File(flat, "payload.txt").writeText("legacy-payload")
        h.registry.put("legacy.plugin", File(flat, "plugin.json").readText())

        // 读路径回退：升级后旧插件不会「消失」
        assertEquals(flat.canonicalPath, h.service.activeDir("legacy.plugin")!!.canonicalPath)
        assertTrue(h.service.inspect("legacy.plugin").legacy)

        val upgraded = h.install("legacy.plugin", "2.0.0")
        assertTrue(upgraded.error, upgraded.ok)

        assertEquals("2.0.0", File(flat, "current").readText().trim())
        // 旧 payload 被迁进 versions/<旧版本>，没有丢
        assertEquals("legacy-payload", File(flat, "versions/1.0.0/payload.txt").readText())
        assertEquals("payload-2.0.0", File(flat, "versions/2.0.0/payload.txt").readText())
        assertFalse(h.service.inspect("legacy.plugin").legacy)
    }

    // ------------------------------------------------------------------ 校验

    @Test
    fun invalidIdsAreRejected() = withHarness<Unit> { h ->
        val bad = listOf("", "..", "../evil", "a/b", "a\\b", "C:evil", " con", "trailing.", "aux")
        bad.forEach { id ->
            val result = h.install(id, "1.0.0")
            assertFalse("id 应被拒绝：" + id, result.ok)
            assertNotNull("id 应给出错误文本：" + id, result.error)
        }
        assertEquals(0, h.registry.ids().size)
    }

    @Test
    fun invalidVersionsAreRejected() = withHarness<Unit> { h ->
        val bad = listOf("../1.0.0", "1.0.0/../x", "1:0", " 1.0.0")
        bad.forEach { version ->
            val result = h.install("dsh.demo", version)
            assertFalse("版本应被拒绝：" + version, result.ok)
            assertTrue(result.error!!.contains("版本"))
        }
    }

    @Test
    fun idWithUnsafeCharsMapsToSafeDirectory() = withHarness<Unit> { h ->
        val id = "my plugin"
        val result = h.install(id, "1.0.0")
        assertTrue(result.error, result.ok)

        val dirName = h.root.listFiles()!!.filter { it.isDirectory && !it.name.startsWith(".") }.single().name
        assertNotEquals(id, dirName)
        assertFalse(dirName.contains(' '))
        assertTrue(dirName.startsWith("my-plugin-"))
        assertTrue(h.service.inspect(id).active)
        assertEquals(dirName, h.service.idDir(id).name)
    }

    @Test
    fun twoIdsSanitizingToSameNameDoNotCollide() = withHarness<Unit> { h ->
        assertTrue(h.install("a b", "1.0.0").ok)
        assertTrue(h.install("a-b", "1.0.0").ok)
        assertTrue(h.service.inspect("a b").active)
        assertTrue(h.service.inspect("a-b").active)
        assertEquals(2, h.root.listFiles()!!.count { it.isDirectory && !it.name.startsWith(".") })
    }

    @Test
    fun brokenManifestFailsWithoutTouchingDisk() = withHarness<Unit> { h ->
        val src = Files.createTempDirectory("pkg-bad").toFile()
        File(src, "plugin.json").writeText("{ this is not json")
        val result = runBlocking { h.service.installFromDirectory(src) }
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("plugin.json 解析失败"))
        assertEquals(0, h.registry.ids().size)
        src.deleteRecursively()
    }

    @Test
    fun packageWithoutManifestFails() = withHarness<Unit> { h ->
        val src = Files.createTempDirectory("pkg-empty").toFile()
        File(src, "readme.txt").writeText("nothing here")
        val result = runBlocking { h.service.installFromDirectory(src) }
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("plugin.json"))
        src.deleteRecursively()
    }

    @Test
    fun zipSlipEntryIsRejected() = withHarness<Unit> { h ->
        val zip = File(h.root.parentFile, "slip-" + System.nanoTime() + ".zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("../escape.txt"))
            out.write("pwned".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("plugin.json"))
            out.write("{\"id\":\"dsh.slip\",\"name\":\"s\",\"version\":\"1.0.0\"}".toByteArray())
            out.closeEntry()
        }
        val result = runBlocking { h.service.installArchive(zip) }
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("包内路径"))
        assertFalse(File(h.root.parentFile, "escape.txt").exists())
        assertFalse(File(h.root, "dsh.slip").exists())
        zip.delete()
    }

    @Test
    fun missingDeclaredDependencyWarns() = withHarness<Unit> { h ->
        val src = Files.createTempDirectory("pkg-dep").toFile()
        File(src, "package.json").writeText(
            "{\"name\":\"dep-test\",\"version\":\"1.0.0\",\"dependencies\":{\"left-pad\":\"1.0.0\"}}",
        )
        // registry 上的 npm 包从不带 node_modules，缺依赖不能挡住安装：装上、留一条 warn，
        // 运行期由 Node 引擎报错并在插件卡片上显示缺哪个包。
        val installed = runBlocking { h.service.installFromDirectory(src) }
        assertTrue(installed.error, installed.ok)
        assertEquals("npm.dep-test", installed.id)
        assertTrue(installed.log.toString(), installed.log.any { it.contains("warn deps=") && it.contains("left-pad") })
        src.deleteRecursively()
    }

    @Test
    fun hostProvidedDependenciesDoNotFail() = withHarness<Unit> { h ->
        val src = Files.createTempDirectory("pkg-node").toFile()
        File(src, "package.json").writeText(
            "{\"name\":\"nodeish\",\"version\":\"1.0.0\",\"dependencies\":{\"node:fs\":\"*\",\"@deepseek-ai/core\":\"1.0.0\"}}",
        )
        val result = runBlocking { h.service.installFromDirectory(src) }
        assertTrue(result.error, result.ok)
        src.deleteRecursively()
    }

    // ---------------------------------------------------------- 巡检 / 修复

    @Test
    fun inspectReportsInstalledActivePathVersionAndError() = withHarness<Unit> { h ->
        val absent = h.service.inspect("nope")
        assertFalse(absent.installed)
        assertFalse(absent.active)

        assertTrue(h.install("dsh.demo", "1.0.0").ok)
        val info = h.service.inspect("dsh.demo")
        assertTrue(info.installed)
        assertTrue(info.active)
        assertEquals("1.0.0", info.version)
        assertEquals(File(h.root, "dsh.demo/versions/1.0.0").canonicalPath, File(info.path).canonicalPath)
        assertNull(info.lastError)

        h.activateFailureFor = "2.0.0"
        h.install("dsh.demo", "2.0.0")
        val broken = h.service.inspect("dsh.demo")
        assertNotNull(broken.lastError)
        assertTrue(broken.lastError!!.contains("激活失败"))
        assertTrue(broken.active)
    }

    @Test
    fun repairReinstallsCurrentVersionAndClearsError() = withHarness<Unit> { h ->
        assertTrue(h.install("dsh.demo", "1.0.0", body = "v1").ok)
        h.verifyFailure = "模拟坏包"
        assertFalse(h.install("dsh.demo", "1.0.0", body = "broken").ok)
        assertNotNull(h.service.lastError("dsh.demo"))

        h.verifyFailure = null
        val repaired = runBlocking { h.service.repair("dsh.demo") }
        assertTrue(repaired.error, repaired.ok)
        assertEquals("1.0.0", repaired.version)
        assertNull(h.service.lastError("dsh.demo"))
        assertEquals("v1", File(h.root, "dsh.demo/versions/1.0.0/payload.txt").readText())
        assertEquals(1, h.registry.ids().size)
    }

    @Test
    fun repairWithExplicitSourceWorksAndWithoutSourceFails() = withHarness<Unit> { h ->
        val nothing = runBlocking { h.service.repair("dsh.missing") }
        assertFalse(nothing.ok)
        assertTrue(nothing.error!!.contains("没有可重装的插件源"))

        assertTrue(h.install("dsh.demo", "1.0.0").ok)
        val src = h.sourceDir("dsh.demo", "1.0.0", body = "from-source")
        val repaired = runBlocking { h.service.repair("dsh.demo", src) }
        assertTrue(repaired.error, repaired.ok)
        assertEquals("from-source", File(h.root, "dsh.demo/versions/1.0.0/payload.txt").readText())
        src.deleteRecursively()
    }

    // ------------------------------------------------------------------ 卸载

    @Test
    fun removeDeletesPayloadAndRegistry() = withHarness<Unit> { h ->
        assertTrue(h.install("dsh.demo", "1.0.0").ok)
        assertTrue(h.install("dsh.demo", "2.0.0").ok)

        val removed = h.service.remove("dsh.demo")
        assertTrue(removed.ok)
        assertFalse(File(h.root, "dsh.demo").exists())
        assertNull(h.registry.raw("dsh.demo"))
    }

    @Test
    fun removeKeepsRegistryWhenFilesCannotBeDeleted() = withHarness<Unit> { h ->
        // 仅 Windows 可注入：POSIX 允许删除已打开文件。
        assumeTrue(isWindows)
        assertTrue(h.install("dsh.demo", "1.0.0").ok)
        val lock = File(h.root, "dsh.demo/versions/1.0.0/lock.txt")
        lock.writeText("x")
        FileOutputStream(lock).use {
            val removed = h.service.remove("dsh.demo")
            assertFalse(removed.ok)
            assertTrue(removed.error!!.contains("删除失败"))
            assertNotNull(h.registry.raw("dsh.demo")) // 文件还在 = 注册表不清
            assertTrue(removed.active)
        }
    }

    @Test
    fun removeOfMissingPackageIsSuccess() = withHarness<Unit> { h ->
        val removed = h.service.remove("dsh.none")
        assertTrue(removed.ok)
        assertFalse(removed.active)
    }

    // ------------------------------------------------------------------ 日志

    @Test
    fun installLogIsReadableByAgent() = withHarness<Unit> { h ->
        assertTrue(h.install("dsh.demo", "1.0.0").ok)
        h.verifyFailure = "坏包"
        assertFalse(h.install("dsh.demo", "2.0.0").ok)

        val log = h.service.log()
        assertTrue(log.isNotEmpty())
        assertTrue(log.any { it.contains("OK install dsh.demo@1.0.0") })
        assertTrue(log.any { it.contains("ERROR install dsh.demo 坏包") })
    }

    // -------------------------------------------------------------- 工具适配层

    @Test
    fun toolsExposeInstallInspectRepairRemoveAndLog() = withHarness<Unit> { h ->
        val names = PluginPackageTools.specs().map { it.name }
        assertEquals(
            listOf("plugins.list", "plugins.inspect", "plugins.install", "plugins.repair", "plugins.remove", "plugins.log"),
            names,
        )

        val src = h.sourceDir("dsh.demo", "1.0.0", body = "via-tool")
        val installed = runBlocking {
            PluginPackageTools.execute(h.service, "plugins.install", JSONObject().put("source", src.absolutePath))
        }
        assertTrue(installed.toString(), installed.getBoolean("ok"))
        assertEquals("dsh.demo", installed.getString("id"))

        val inspected = runBlocking {
            PluginPackageTools.execute(h.service, "plugins.inspect", JSONObject().put("id", "dsh.demo"))
        }
        assertTrue(inspected.getBoolean("ok"))
        assertEquals("1.0.0", inspected.getJSONObject("package").getString("version"))

        val listed = runBlocking { PluginPackageTools.execute(h.service, "plugins.list", JSONObject()) }
        assertEquals(1, listed.getJSONArray("packages").length())

        val logs = runBlocking { PluginPackageTools.execute(h.service, "plugins.log", JSONObject().put("limit", 5)) }
        assertTrue(logs.getJSONArray("lines").length() in 1..5)

        val repaired = runBlocking {
            PluginPackageTools.execute(h.service, "plugins.repair", JSONObject().put("id", "dsh.demo"))
        }
        assertTrue(repaired.getBoolean("ok"))

        val removed = runBlocking {
            PluginPackageTools.execute(h.service, "plugins.remove", JSONObject().put("id", "dsh.demo"))
        }
        assertTrue(removed.getBoolean("ok"))

        src.deleteRecursively()
    }

    @Test
    fun toolsReportMissingArgumentsAndUnknownTool() = withHarness<Unit> { h ->
        val missing = runBlocking { PluginPackageTools.execute(h.service, "plugins.inspect", JSONObject()) }
        assertFalse(missing.getBoolean("ok"))
        assertTrue(missing.getString("error").contains("id"))

        val unknown = runBlocking { PluginPackageTools.execute(h.service, "plugins.nope", JSONObject()) }
        assertFalse(unknown.getBoolean("ok"))
        assertTrue(unknown.getString("error").contains("未知工具"))

        val badPath = runBlocking {
            PluginPackageTools.execute(h.service, "plugins.install", JSONObject().put("source", File(h.root, "nope").absolutePath))
        }
        assertFalse(badPath.getBoolean("ok"))
    }

    // ------------------------------------------------------------ 纯函数校验

    @Test
    fun segmentAndValidationHelpers() {
        assertEquals("dsh.demo", safeSegment("dsh.demo"))
        assertNotEquals("a b", safeSegment("a b"))
        assertEquals(safeSegment("a b"), safeSegment("a b"))
        assertTrue(isSimpleSegment("npm.dep-test"))
        assertFalse(isSimpleSegment(".."))
        assertFalse(isSimpleSegment("a/b"))
        assertNull(validatePluginId("dsh.demo"))
        assertNull(validateVersion("1.0.0-beta+1"))
        assertNotNull(validatePluginId(".."))
        assertNotNull(validateVersion("1.0.0/../x"))
    }
}
