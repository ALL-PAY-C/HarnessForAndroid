package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.core.common.RuntimeMirrors
import dev.dsh.mobile.design.R
import dev.dsh.mobile.core.common.archive.ZipExtract
import dev.dsh.mobile.core.common.download.DownloadCache
import dev.dsh.mobile.core.common.download.Downloads
import dev.dsh.mobile.pluginhost.ExternalPluginStore
import dev.dsh.mobile.pluginhost.PluginInstaller
import java.io.File
import java.util.zip.ZipEntry
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
        copy: (Int, List<Any>) -> String,
    ): String {
        val repo = parse(input) ?: guess(input) ?: error(
            copy(R.string.plugins_err_link_unreadable, listOf(input.take(80))),
        )
        val stamp = System.currentTimeMillis()
        val archive = File(cacheDir, "gh-$stamp.zip")
        var branch = ""
        var last: Throwable? = null
        val candidates = (listOfNotNull(repo.branch) + listOf("main", "master")).distinct()
        for (candidate in candidates) {
            val url = "https://codeload.github.com/" + repo.owner + "/" + repo.name + "/zip/refs/heads/" + candidate
            val attempt = runCatching { download(url, archive, cacheDir) }
            if (attempt.isSuccess) {
                branch = candidate
                last = null
                break
            }
            last = attempt.exceptionOrNull()
        }
        if (branch.isEmpty()) {
            archive.delete()
            error(
                copy(
                    R.string.plugins_err_repo_fetch,
                    listOf(repo.owner + "/" + repo.name, last?.message ?: copy(R.string.plugins_err_branch_missing, emptyList())),
                ),
            )
        }
        try {
            val entries = scan(archive, copy)
            val manifest = entries
                .filter { it == "plugin.json" || it.endsWith("/plugin.json") }
                .minByOrNull { it.count { ch -> ch == '/' } }
            if (manifest != null) {
                val target = installer ?: error(copy(R.string.plugins_err_installer_not_ready, emptyList()))
                val staged = File(cacheDir, "gh-stage-$stamp.zip")
                val prefix = manifest.removeSuffix("plugin.json")
                repackage(archive, prefix, staged)
                val installed = target.installZip(staged).getOrElse { staged.delete(); throw it }
                return copy(R.string.market_done_data, listOf(installed.name, installed.version))
            }
            val target = installer ?: error(copy(R.string.plugins_err_installer_not_ready, emptyList()))
            val pkgJson = entries
                .filter { it == "package.json" || it.endsWith("/package.json") }
                .minByOrNull { it.count { ch -> ch == '/' } }
            if (pkgJson != null) {
                val staged = File(cacheDir, "gh-stage-$stamp.zip")
                repackage(archive, pkgJson.removeSuffix("package.json"), staged)
                val installed = target.installZip(staged).getOrElse { staged.delete(); throw it }
                return installed.name + copy(R.string.plugins_done_js, emptyList())
            }
            // 仓库里直接放的 zip 插件包：正常分发格式，raw 下载后原样解包。
            val zipEntry = entries
                .filter { it.endsWith(".zip", ignoreCase = true) }
                .minByOrNull { it.count { ch -> ch == '/' } }
            if (zipEntry != null) {
                val file = File(cacheDir, "gh-$stamp-plugin.zip")
                val raw = "https://raw.githubusercontent.com/" + repo.owner + "/" + repo.name + "/" + branch + "/" + zipEntry
                download(raw, file, cacheDir)
                val installed = target.installArchive(file).getOrElse { file.delete(); throw it }
                return installed.name + " " + installed.version + copy(R.string.plugins_done_zip, emptyList())
            }
            // 老分发格式兜底：仓库里只有 .apk 整包。仍按内容分流，装得上就不算变砖。
            val apk = entries
                .filter { it.endsWith(".apk", ignoreCase = true) }
                .minByOrNull { it.count { ch -> ch == '/' } }
                ?: error(copy(R.string.plugins_err_no_manifest, emptyList()))
            val store = externalStore ?: error(copy(R.string.plugins_err_loader_not_ready, emptyList()))
            val file = File(cacheDir, "gh-$stamp.apk")
            val raw = "https://raw.githubusercontent.com/" + repo.owner + "/" + repo.name + "/" + branch + "/" + apk
            download(raw, file, cacheDir)
            val entry = store.installPackage(file).getOrElse { file.delete(); throw it }
            return entry.name + " " + entry.version + copy(R.string.plugins_done_apk, emptyList())
        } finally {
            archive.delete()
        }
    }

    /** 下载实现：多镜像分段下载 + 弱缓存（仓库 zipball / 单个包都没有 sha256）。 */
    private fun download(url: String, out: File, cacheDir: File) {
        Downloads.downloadBlocking(
            urls = RuntimeMirrors.downloadCandidates(url),
            target = out,
            cache = DownloadCache(File(cacheDir, "dl-cache")),
            maxBytes = MAX_BYTES,
            label = url,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 60_000,
        )
    }

    private fun scan(zip: File, copy: (Int, List<Any>) -> String): List<String> = runCatching {
        ZipExtract.names(zip, ZipExtract.Options.pathOnly(MAX_ENTRIES))
    }.getOrElse { error(copy(R.string.plugins_err_unreadable_zip, listOf(it.message ?: it::class.java.simpleName))) }

    /** 把 prefix 目录下的文件提到 zip 根，喂给只认「根 plugin.json」的安装器。 */
    private fun repackage(src: File, prefix: String, out: File) {
        ZipOutputStream(out.outputStream()).use { sink ->
            ZipExtract.forEachEntry(src.inputStream(), ZipExtract.Options.pathOnly(MAX_ENTRIES)) { name, isDirectory, data ->
                if (!isDirectory && name.startsWith(prefix)) {
                    val rel = name.removePrefix(prefix)
                    if (rel.isNotEmpty()) {
                        sink.putNextEntry(ZipEntry(rel))
                        data.copyTo(sink)
                        sink.closeEntry()
                    }
                }
            }
        }
    }
}