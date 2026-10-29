package dev.dsh.mobile.pluginhost

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 插件包的分发格式。
 *
 * 正常分发是 **zip**（里面放 package.json + 入口文件，或 plugin.json 声明式清单）；
 * 旧版本发出去的 .apk 插件包照样收 —— 老包不能变砖，按内容判类型而不是看后缀。
 *
 * 只做「看内容说话」的纯判定，不碰 Context，可以在 JVM 单测里直接跑。
 */
object PluginPackageFormat {

    /** 能当插件包喂给安装器的文件名后缀（.apk 是旧格式，仍然认）。 */
    private val ARCHIVE_SUFFIXES = listOf(".zip", ".apk", ".tgz", ".tar.gz", ".gz")

    fun isArchiveName(name: String): Boolean {
        val lower = name.lowercase()
        return ARCHIVE_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * 旧分发格式判据：zip 根上有 AndroidManifest.xml 或 classes*.dex 就是 APK 插件；
     * 只有 package.json / plugin.json 的是数据插件，走解包路径。
     */
    fun isAndroidApk(file: File): Boolean = runCatching {
        ZipFile(file).use { zip -> zip.entries().asSequence().any { isApkEntry(it.name) } }
    }.getOrDefault(false)

    fun isAndroidApk(bytes: ByteArray): Boolean = runCatching {
        ZipInputStream(bytes.inputStream()).use { zin ->
            var seen = 0
            while (seen < MAX_ENTRIES) {
                val e = zin.nextEntry ?: break
                seen++
                if (isApkEntry(e.name)) return@use true
            }
            false
        }
    }.getOrDefault(false)

    /** 包入口（相对包目录）。顺序与 JsPluginRuntime.resolveEntry 一致，最后才看 plugin.json。 */
    fun entryOf(dir: File): String? {
        val pkg = runCatching { JSONObject(File(dir, "package.json").readText()) }.getOrNull()
        val dsh = pkg?.optJSONObject("dsh")
        val candidates = listOf(
            dsh?.optString("main").orEmpty(),
            dsh?.optString("entry").orEmpty(),
            pkg?.optString("main").orEmpty(),
            "index.js",
            "lib/index.js",
        )
        candidates.firstOrNull { it.isNotBlank() && File(dir, it).isFile }?.let { return it }
        val manifest = runCatching { JSONObject(File(dir, "plugin.json").readText()) }.getOrNull()
        return manifest?.optString("entry")?.takeIf { it.isNotBlank() }
    }

    /** 落盘目录里必须留着的清单文件：声明式插件与 JS 运行期都靠它，装完不能删。 */
    fun manifestFiles(dir: File): List<File> =
        listOf("plugin.json", "package.json").map { File(dir, it) }.filter { it.isFile }

    private fun isApkEntry(name: String): Boolean =
        name == "AndroidManifest.xml" || (name.startsWith("classes") && name.endsWith(".dex"))

    private const val MAX_ENTRIES = 2000
}
