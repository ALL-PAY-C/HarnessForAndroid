package dev.dsh.mobile.pluginhost

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 离线预置包的来源：生产是 APK assets，单测是磁盘目录。 */
interface BundledSource {
    /** 包名清单（只列 .zip），顺序稳定。 */
    fun names(): List<String>

    fun read(name: String): ByteArray
}

/** assets/plugins 下的 zip —— 首启的预置包都在 APK 里，全程不联网。 */
class AssetBundledSource(
    private val context: Context,
    private val dir: String = OfflineBundled.ASSET_DIR,
) : BundledSource {
    override fun names(): List<String> = runCatching {
        context.assets.list(dir).orEmpty()
            .filter { it.endsWith(".zip", ignoreCase = true) }
            .sorted()
    }.getOrDefault(emptyList())

    override fun read(name: String): ByteArray =
        context.assets.open(dir + "/" + name).use { it.readBytes() }
}

/** 磁盘目录里的同名 zip，单测与「把预置包放 U 盘」都用得上。 */
class DirBundledSource(private val dir: File) : BundledSource {
    override fun names(): List<String> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            .map { it.name }
            .sorted()

    override fun read(name: String): ByteArray = File(dir, name).readBytes()
}

/** 一次预置的结果。 */
data class OfflineReport(
    val installed: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failed: List<Pair<String, String>> = emptyList(),
) {
    /** 有没有真的装进去东西（调用方据此决定要不要热加载 / 刷新列表）。 */
    val changed: Boolean get() = installed.isNotEmpty()

    /** 给用户看的一句话；没装也没失败时为空串，调用方不要上屏。 */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (installed.isNotEmpty()) {
            parts += "已预置 " + installed.size + " 个离线插件：" +
                installed.joinToString("、") { it.removeSuffix(".zip") }
        }
        if (failed.isNotEmpty()) {
            parts += "离线插件预置失败：" +
                failed.joinToString("、") { (name, why) -> name.removeSuffix(".zip") + "（" + why + "）" }
        }
        if (parts.isEmpty()) parts += "离线插件已就绪"
        return parts.joinToString("；")
    }
}

/**
 * 预置离线插件的首启安装。
 *
 * 幂等靠 plugins/.offline.json 里记的 {rev, done:[包名]}：同批次装过就不再装，
 * 所以每次冷启都调也只会白跑一次文件列举。用户手动卸载过的预置包**不会**复活 ——
 * marker 记的是「这个包名跑过」，不是「磁盘上还有它」。
 *
 * 改了预置包内容就把 [REV] +1：老设备会把新批次重灌一遍（同 id 走既有版本化覆盖）。
 */
object OfflineBundled {

    /** APK 里的预置包目录（app/src/main/assets/plugins）。 */
    const val ASSET_DIR: String = "plugins"

    /** 预置批次版本；改了预置包内容就 +1。 */
    const val REV: String = "1"

    /** 幂等标记文件名，落在插件根目录（filesDir/plugins）下。 */
    const val MARKER: String = ".offline.json"

    fun markerOf(pluginsRoot: File): File = File(pluginsRoot, MARKER)

    suspend fun install(
        service: PluginPackageService,
        source: BundledSource,
        marker: File,
        rev: String = REV,
    ): OfflineReport = withContext(Dispatchers.IO) {
        val names = source.names()
        if (names.isEmpty()) return@withContext OfflineReport()
        val root = marker.parentFile ?: return@withContext OfflineReport()
        val done = readDone(marker, rev)
        val installed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (name in names) {
            if (name in done) {
                skipped += name
                continue
            }
            val staged = File(root, ".offline-stage.tmp")
            var ok = false
            var why: String? = null
            try {
                root.mkdirs()
                staged.writeBytes(source.read(name))
                val result = service.installArchive(staged, source = "bundled/" + name)
                ok = result.ok
                why = result.error
            } catch (t: Throwable) {
                why = t.message ?: t::class.java.simpleName
            } finally {
                staged.delete()
            }
            if (ok) {
                installed += name
                done += name
            } else {
                failed += name to (why ?: "解包失败").ifBlank { "解包失败" }
            }
        }
        writeDone(marker, rev, done)
        OfflineReport(installed = installed, skipped = skipped, failed = failed)
    }

    /** 读已装过的包名；批次 rev 变了就当作一个都没装过。 */
    private fun readDone(marker: File, rev: String): MutableSet<String> = runCatching {
        if (!marker.isFile) return@runCatching mutableSetOf<String>()
        val json = JSONObject(marker.readText())
        if (json.optString("rev") != rev) return@runCatching mutableSetOf<String>()
        val arr = json.optJSONArray("done") ?: JSONArray()
        (0 until arr.length())
            .mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            .toMutableSet()
    }.getOrDefault(mutableSetOf())

    private fun writeDone(marker: File, rev: String, done: Set<String>) {
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(JSONObject().put("rev", rev).put("done", JSONArray(done.sorted())).toString())
        }
    }
}
