package dev.dsh.mobile.pluginhost

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「从文件安装」的按内容判型：apk / 数据插件包 / 垃圾包。
 *
 * 判型不看后缀（SAF 常把文件名吃掉），只看文件头与包内 entry，所以能整段在 JVM 上跑。
 * 跑法：./gradlew :plugin-host:testDebugUnitTest --tests '*PluginImportTest'
 */
class PluginImportTest {

    private val work: File = Files.createTempDirectory("plugimport").toFile()

    /** 把 entries（相对路径 → 文本）打成 zip；文件名故意不带 .zip 的地方由调用方自己给。 */
    private fun zip(name: String, vararg entries: Pair<String, String>): File {
        val file = File(work, name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (path, body) ->
                out.putNextEntry(ZipEntry(path))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun raw(name: String, body: String): File = File(work, name).also { it.writeText(body) }

    @Test
    fun apkByNameContent() {
        // 后缀是 .zip、名字叫「下载」，内容里却有 AndroidManifest.xml + classes.dex —— 按内容判成 APK。
        val apk = zip(
            "下载",
            "AndroidManifest.xml" to "<manifest package=\"dev.dsh.sample\" />",
            "classes.dex" to "dex\n035\u0000",
            "assets/plugin.json" to "{\"id\":\"sample\"}",
        )
        assertEquals(ImportKind.Apk, PluginImport.classify(apk))
    }

    @Test
    fun pluginPackageByContent() {
        val pkg = zip(
            "包.zip",
            "package.json" to "{\"name\":\"zip-plugin\",\"version\":\"1.0.0\",\"dsh\":{\"main\":\"index.js\"}}",
            "index.js" to "export const name = 'zip-plugin'",
        )
        assertEquals(ImportKind.PluginPackage, PluginImport.classify(pkg))

        // 声明式清单也算数据插件包（没有 JS 入口也照装）。
        val declarative = zip("declarative.zip", "plugin.json" to "{\"id\":\"dsh.declarative\"}")
        assertEquals(ImportKind.PluginPackage, PluginImport.classify(declarative))

        // npm tarball：1f 8b 开头的 gzip 容器。
        val tgz = File(work, "plugin.tgz").also { it.writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00)) }
        assertEquals(ImportKind.PluginPackage, PluginImport.classify(tgz))
    }

    @Test
    fun garbageIsRejected() {
        assertEquals(ImportKind.Garbage, PluginImport.classify(raw("notes.txt", "这不是插件包")))
        assertEquals(ImportKind.Garbage, PluginImport.classify(raw("empty.zip", "")))
        assertEquals(ImportKind.Garbage, PluginImport.classify(File(work, "missing.zip")))
    }

    /** 判成数据插件包的确实装得进去；判成垃圾的连解包都不该发生。 */
    @Test
    fun pluginPackageInstallsAndGarbageDoesNot() = runBlocking<Unit> {
        val root = Files.createTempDirectory("plugimport-root").toFile()
        val registry = MemoryPackageRegistry()
        val service = PluginPackageService(root = root, registry = registry, onVerify = { _, _ -> })

        val good = zip(
            "good.zip",
            "plugin.json" to "{\"id\":\"dsh.imported\",\"name\":\"导入包\",\"version\":\"1.0.0\",\"entry\":\"index.js\"}",
            "index.js" to "export const name = 'dsh-imported'",
        )
        assertEquals(ImportKind.PluginPackage, PluginImport.classify(good))
        val ok = service.installArchive(good)
        assertTrue(ok.error, ok.ok)
        assertEquals("dsh.imported", ok.id)
        assertTrue(registry.ids().contains("dsh.imported"))

        val junk = zip("junk.zip", "readme.txt" to "这里没有清单")
        assertEquals(ImportKind.PluginPackage, PluginImport.classify(junk))
        val failed = service.installArchive(junk)
        assertFalse(failed.ok)
        assertTrue(failed.error.orEmpty().isNotBlank())

        root.deleteRecursively()
    }
}
