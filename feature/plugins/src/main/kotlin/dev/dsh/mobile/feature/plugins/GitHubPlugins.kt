package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.pluginhost.ExternalPluginStore
import dev.dsh.mobile.pluginhost.PluginInstaller
import java.io.File
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
/**
 * 从 GitHub 仓库直接装插件。
 *
 * 按可读性从好到差依次认，其余给可读错误：
 *  1. 数据插件：仓库里任意层级有 plugin.json（取最浅的），拉 zipball 后把该目录提到包根再交给
 *     [PluginInstaller]——它的硬性要求是 plugin.json 在 zip 根上；
 *  2. JS 插件：只有 package.json 的 npm 形态（dsh.main / main 指入口），同上提到包根再装；
 *  3. zip 插件包：仓库里直接躺着 .zip 包，raw 下载原样交给安装器解包；
 *  4. 旧 APK 插件：只有 .apk（老分发格式），raw 下载后交给 [ExternalPluginStore]。
 *
 * 不打 api.github.com：未登录只有 60 次/小时，且只是为了拿 default_branch 不值得。
 * 直接用 codeload 试 main / master，raw 用同名分支。全程阻塞，调用方负责丢到 IO 线程。
 */
object GitHubPlugins {

    data class Repo(val owner: String, val name: String, val branch: String? = null)

    /** 一次最多拉 96 MB、最多扫 4000 个条目，防止误填个大仓库把手机拖死。 */
    private const val MAX_BYTES = 96L * 1024 * 1024
    private const val MAX_ENTRIES = 4000

    private val FULL_RE = Regex(
        """^(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val SHORT_RE = Regex("""^([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)$""")

    /** 接受 https://github.com/o/r、github.com/o/r/tree/main、o/r、git@github.com:o/r.git。 */
    fun parse(input: String): Repo? {
        val text = input.trim().removeSuffix("/")
        if (text.isEmpty()) return null
        FULL_RE.find(text)?.let { m ->
            // https://github.com/o/r/tree/<branch> 里带的分支优先，省掉 main/master 盲猜。
            val rest = text.substring(m.range.last + 1).trim('/')
            val branch = rest.substringAfter("tree/", "").substringBefore('/').ifBlank { null }
            return Repo(m.groupValues[1], m.groupValues[2].removeSuffix(".git"), branch)
        }
        val ssh = text.substringAfter(':', text).substringAfter("github.com", text)
        SHORT_RE.find(ssh)?.let { m ->
            return Repo(m.groupValues[1], m.groupValues[2].removeSuffix(".git"))
        }
        return null
    }

    /**
     * 比 [parse] 宽松：任何含 `github.com/owner/repo` 的串（含后面还有 /tree/x/y/z 或查询串、
     * 或只有 `owner/repo/xxx` 的路径）都能认出 owner/repo。市场索引里 repo 字段形态很杂，
     * 严格的正则会让「有仓库但装不了」的条目白白掉进 else 分支。
     */
    fun guess(input: String): Repo? {
        parse(input)?.let { return it }
        val text = input.trim().substringBefore('?').substringBefore('#')
        if (text.isEmpty()) return null
        val idx = text.indexOf("github.com/", ignoreCase = true)
        val tail = if (idx >= 0) text.substring(idx + "github.com/".length) else text
        val segs = tail.split('/').filter { it.isNotBlank() }.map { it.removeSuffix(".git") }
        if (segs.size < 2) return null
        val owner = segs[0]
        val name = segs[1]
        if (!owner.matches(NAME_RE) || !name.matches(NAME_RE)) return null
        val branch = if (segs.size >= 4 && segs[2] == "tree") segs[3] else null
        return Repo(owner, name, branch)
    }

    private val NAME_RE = Regex("^[A-Za-z0-9._-]+$")
    /** 装一个仓库。成功返回一句人话（插件名 + 类型），失败抛带原因的异常。 */
    suspend fun install(
        installer: PluginInstaller?,
        externalStore: ExternalPluginStore?,
        cacheDir: File,
        input: String,
    ): String {
        val repo = parse(input) ?: guess(input) ?: error(
            "链接看不懂：" + input.take(80) + "\n支持 https://github.com/owner/repo、owner/repo、" +
                "github.com/owner/repo/tree/分支，或直接的 .zip 包地址",
        )
        val stamp = System.currentTimeMillis()
        val archive = File(cacheDir, "gh-$stamp.zip")
        var branch = ""
        var last: Throwable? = null
        val candidates = (listOfNotNull(repo.branch) + listOf("main", "master")).distinct()
        for (candidate in candidates) {
            val url = "https://codeload.github.com/" + repo.owner + "/" + repo.name + "/zip/refs/heads/" + candidate
            val attempt = runCatching { download(url, archive) }
            if (attempt.isSuccess) {
                branch = candidate
                last = null
                break
            }
            last = attempt.exceptionOrNull()
        }
        if (branch.isEmpty()) {
            archive.delete()
            error("拉不到仓库 " + repo.owner + "/" + repo.name + "：" + (last?.message ?: "main / master 都不存在"))
        }
        try {
            val entries = scan(archive)
            val manifest = entries
                .filter { it == "plugin.json" || it.endsWith("/plugin.json") }
                .minByOrNull { it.count { ch -> ch == '/' } }
            if (manifest != null) {
                val target = installer ?: error("安装器未就绪")
                val staged = File(cacheDir, "gh-stage-$stamp.zip")
                val prefix = manifest.removeSuffix("plugin.json")
                repackage(archive, prefix, staged)
                val installed = target.installZip(staged).getOrElse { staged.delete(); throw it }
                return installed.name + "（数据插件）"
            }
            val target = installer ?: error("安装器未就绪")
            val pkgJson = entries
                .filter { it == "package.json" || it.endsWith("/package.json") }
                .minByOrNull { it.count { ch -> ch == '/' } }
            if (pkgJson != null) {
                val staged = File(cacheDir, "gh-stage-$stamp.zip")
                repackage(archive, pkgJson.removeSuffix("package.json"), staged)
                val installed = target.installZip(staged).getOrElse { staged.delete(); throw it }
                return installed.name + "（JS 插件）"
            }
            // 仓库里直接放的 zip 插件包：正常分发格式，raw 下载后原样解包。
            val zipEntry = entries
                .filter { it.endsWith(".zip", ignoreCase = true) }
                .minByOrNull { it.count { ch -> ch == '/' } }
            if (zipEntry != null) {
                val file = File(cacheDir, "gh-$stamp-plugin.zip")
                val raw = "https://raw.githubusercontent.com/" + repo.owner + "/" + repo.name + "/" + branch + "/" + zipEntry
                download(raw, file)
                val installed = target.installArchive(file).getOrElse { file.delete(); throw it }
                return installed.name + " " + installed.version + "（zip 插件包）"
            }
            // 老分发格式兜底：仓库里只有 .apk 整包。仍按内容分流，装得上就不算变砖。
            val apk = entries
                .filter { it.endsWith(".apk", ignoreCase = true) }
                .minByOrNull { it.count { ch -> ch == '/' } }
                ?: error("仓库里没有 plugin.json、package.json、.zip 或 .apk，认不出这是插件仓库")
            val store = externalStore ?: error("插件加载器未就绪")
            val file = File(cacheDir, "gh-$stamp.apk")
            val raw = "https://raw.githubusercontent.com/" + repo.owner + "/" + repo.name + "/" + branch + "/" + apk
            download(raw, file)
            val entry = store.installPackage(file).getOrElse { file.delete(); throw it }
            return entry.name + " " + entry.version + "（APK 插件）"
        } finally {
            archive.delete()
        }
    }

    private fun download(url: String, out: File) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/zip,application/octet-stream,*/*")
                setRequestProperty("User-Agent", "dsh-mobile-plugin")
            }
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP " + code)
            var total = 0L
            val counter = object : FilterInputStream(conn.inputStream) {
                override fun read(): Int = super.read().also { if (it >= 0) total++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) total += it }
            }
            out.outputStream().use { sink -> counter.use { it.copyTo(sink) } }
            if (total > MAX_BYTES) error("包太大（" + (total / 1024 / 1024) + " MB），上限 96 MB")
        } finally {
            conn?.disconnect()
        }
    }

    private fun scan(zip: File): List<String> = runCatching {
        val names = ArrayList<String>()
        ZipInputStream(zip.inputStream()).use { zin ->
            var n = 0
            while (n < MAX_ENTRIES) {
                val e = zin.nextEntry ?: break
                names += e.name
                n++
            }
        }
        names
    }.getOrElse { error("包读不出来：" + it.message) }

    /** 把 prefix 目录下的文件提到 zip 根，喂给只认「根 plugin.json」的安装器。 */
    private fun repackage(src: File, prefix: String, out: File) {
        ZipOutputStream(out.outputStream()).use { sink ->
            ZipInputStream(src.inputStream()).use { zin ->
                var n = 0
                while (n < MAX_ENTRIES) {
                    val e = zin.nextEntry ?: break
                    n++
                    if (e.isDirectory) continue
                    if (!e.name.startsWith(prefix)) continue
                    val rel = e.name.removePrefix(prefix)
                    if (rel.isEmpty()) continue
                    sink.putNextEntry(ZipEntry(rel))
                    zin.copyTo(sink)
                    sink.closeEntry()
                }
            }
        }
    }
}