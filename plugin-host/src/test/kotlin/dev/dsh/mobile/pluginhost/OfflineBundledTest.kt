package dev.dsh.mobile.pluginhost

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 预置离线插件（assets/plugins → 插件目录）的安装与幂等。
 *
 * 幂等靠 plugins/.offline.json 里的包名清单：同一批次装第二次只返回 skipped，
 * 不会在 versions/ 下堆第二份。跑法：
 *   ./gradlew :plugin-host:testDebugUnitTest --tests '*OfflineBundledTest'
 */
class OfflineBundledTest {

    private class Harness {
        val root: File = Files.createTempDirectory("offline-root").toFile()
        val bundled: File = Files.createTempDirectory("offline-src").toFile()
        val registry = MemoryPackageRegistry()
        val activated = mutableListOf<String>()

        val service = PluginPackageService(
            root = root,
            registry = registry,
            onVerify = { _, _ -> },
            onActivate = { _, dir -> activated += dir.name },
        )

        val marker: File get() = OfflineBundled.markerOf(root)

        /** 一个最小可加载的离线插件包（plugin.json + Node 入口）。 */
        fun pack(fileName: String, id: String, version: String = "0.1.0"): File {
            val file = File(bundled, fileName)
            ZipOutputStream(file.outputStream()).use { out ->
                fun entry(name: String, body: String) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(body.toByteArray())
                    out.closeEntry()
                }
                entry(
                    "plugin.json",
                    "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"version\":\"" + version +
                        "\",\"entry\":\"index.js\"}",
                )
                entry("index.js", "import os from 'node:os'\nexport const name = '" + id + "'\n")
            }
            return file
        }

        fun versions(id: String): Int = File(root, id + "/versions").listFiles()?.size ?: 0

        fun cleanup() {
            root.deleteRecursively()
            bundled.deleteRecursively()
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

    @Test
    fun installTwiceIsIdempotent() = withHarness<Unit> { h ->
        h.pack("a.zip", "dsh.offline.a")
        h.pack("b.zip", "dsh.offline.b")
        val source = DirBundledSource(h.bundled)

        val first = runBlocking { OfflineBundled.install(h.service, source, h.marker) }
        assertEquals(listOf("a.zip", "b.zip"), first.installed)
        assertTrue(first.skipped.isEmpty())
        assertTrue(first.failed.toString(), first.failed.isEmpty())
        assertTrue(first.changed)
        assertTrue(first.summary().contains("已预置 2 个离线插件"))
        assertTrue(h.marker.isFile)
        assertEquals(2, h.registry.ids().size)
        assertEquals(1, h.versions("dsh.offline.a"))

        // 第二次：一个都不再装，全落在 skipped；versions 下不许有第二份。
        val second = runBlocking { OfflineBundled.install(h.service, source, h.marker) }
        assertTrue(second.installed.isEmpty())
        assertEquals(listOf("a.zip", "b.zip"), second.skipped)
        assertTrue(second.failed.isEmpty())
        assertFalse(second.changed)
        assertEquals(2, h.registry.ids().size)
        assertEquals(1, h.versions("dsh.offline.a"))
        assertEquals(1, h.versions("dsh.offline.b"))
    }

    @Test
    fun revBumpReinstallsPendingBatch() = withHarness<Unit> { h ->
        h.pack("a.zip", "dsh.offline.a")
        val source = DirBundledSource(h.bundled)

        val rev1 = runBlocking { OfflineBundled.install(h.service, source, h.marker, rev = "1") }
        assertEquals(listOf("a.zip"), rev1.installed)

        // 批次 rev 变了：老标记作废，同包重灌一遍（同 id 走版本化覆盖，不会多一份）。
        val rev2 = runBlocking { OfflineBundled.install(h.service, source, h.marker, rev = "2") }
        assertEquals(listOf("a.zip"), rev2.installed)
        assertEquals(1, h.versions("dsh.offline.a"))
        assertEquals(1, h.registry.ids().size)
    }

    @Test
    fun brokenPackageIsReportedButOthersStillInstall() = withHarness<Unit> { h ->
        h.pack("good.zip", "dsh.offline.good")
        File(h.bundled, "bad.zip").let { file ->
            ZipOutputStream(file.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("readme.txt"))
                out.write("没有清单".toByteArray())
                out.closeEntry()
            }
        }
        val report = runBlocking {
            OfflineBundled.install(h.service, DirBundledSource(h.bundled), h.marker)
        }
        assertEquals(listOf("good.zip"), report.installed)
        assertEquals(listOf("bad.zip"), report.failed.map { it.first })
        assertTrue(report.summary().contains("离线插件预置失败"))
        // 装成的那个记进标记，失败的下次还会重试。
        val again = runBlocking {
            OfflineBundled.install(h.service, DirBundledSource(h.bundled), h.marker)
        }
        assertTrue(again.installed.isEmpty())
        assertEquals(listOf("good.zip"), again.skipped)
        assertEquals(listOf("bad.zip"), again.failed.map { it.first })
    }

    /**
     * 真发出去的那两个预置包（app/src/main/assets/plugins）：必须打得开、装得上，
     * 而且被 classifyPlugin 判成 Node 宿主插件、入口解析成 index.js —— 否则装进去也加载不了。
     */
    @Test
    fun shippedOfflineAssetsAreLoadableNodePlugins() {
        val dir = File("../app/src/main/assets/plugins")
        assumeTrue("仓库里没有预置包目录", dir.isDirectory)
        val source = DirBundledSource(dir)
        assertEquals(listOf("dsh-offline-hello.zip", "dsh-offline-whoami.zip"), source.names())

        val root = Files.createTempDirectory("offline-assets").toFile()
        try {
            val service = PluginPackageService(
                root = root,
                registry = MemoryPackageRegistry(),
                onVerify = { _, _ -> },
            )
            val report = runBlocking {
                OfflineBundled.install(service, source, OfflineBundled.markerOf(root))
            }
            assertTrue(report.failed.toString(), report.failed.isEmpty())
            assertEquals(listOf("dsh-offline-hello.zip", "dsh-offline-whoami.zip"), report.installed)

            listOf("dsh.offline.hello", "dsh.offline.whoami").forEach { id ->
                val active = service.activeDir(id)
                assertNotNull("没装出 " + id, active)
                assertEquals(PluginKind.Node, classifyPlugin(active!!))
                assertEquals("index.js", PluginPackageFormat.entryOf(active))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptySourceIsNoOp() = withHarness<Unit> { h ->
        val report = runBlocking {
            OfflineBundled.install(h.service, DirBundledSource(h.bundled), h.marker)
        }
        assertTrue(report.installed.isEmpty())
        assertFalse(report.changed)
        assertFalse(h.marker.isFile)
    }
}
