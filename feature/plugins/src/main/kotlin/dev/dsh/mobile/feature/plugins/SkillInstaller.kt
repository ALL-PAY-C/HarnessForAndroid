package dev.dsh.mobile.feature.plugins

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 从 GitHub 仓库 / zip 直链装一个 SKILL.md 技能。
 *
 * 链路：下载 zip → 解压到 cacheDir/skill-stage-<stamp> → 返回 staging 根目录，
 * 调用方再把这个目录交给技能管理器的 `install` endpoint（它内部用 skills CLI 安装）。
 * 移动端自己解压，所以不依赖 guest 里的 git。
 *
 * 全程阻塞，调用方负责丢到 IO 线程；staging 的清理由调用方负责（失败时这里已经清过）。
 * ponytail: 上限是防误填大仓库的硬编码常量，真需要更大就调 [MAX_*]。
 */
object SkillInstaller {

    /** 解析结果：GitHub 仓库（owner/name[/branch]）或任意 zip 直链。 */
    sealed interface Source {
        data class GitHub(val owner: String, val name: String, val branch: String? = null) : Source
        data class ZipUrl(val url: String) : Source
    }

    /** 给人看的失败原因（HTTP 码、缺 SKILL.md、路径非法…），UI 直接展示 message。 */
    class SkillInstallException(message: String) : Exception(message)

    /** 解压上限，默认值即生产值；测试用小值验证拒绝路径。 */
    data class Limits(
        val maxEntries: Int = MAX_ENTRIES,
        val maxEntryBytes: Long = MAX_ENTRY_BYTES,
        val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    )

    const val MAX_ENTRIES = 2000
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 60L * 1024 * 1024
    const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val SKILL_MANIFEST = "SKILL.md"

    private val FULL_RE = Regex(
        """^(?:https?://)?(?:www.)?github.com/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val SHORT_RE = Regex("""^([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)$""")
    private val WINDOWS_ABSOLUTE_RE = Regex("^[A-Za-z]:")

    /** 接受 https://github.com/o/r、github.com/o/r/tree/分支、o/r、任意 http(s) zip 直链。看不懂返回 null。 */
    fun parse(input: String): Source? {
        val text = input.trim().removeSuffix("/")
        if (text.isEmpty()) return null
        val httpish = text.startsWith("http://", true) || text.startsWith("https://", true)
        // 直链优先：github.com/o/r/archive/refs/heads/main.zip 也是 zip，不该当仓库去猜分支。
        if (httpish && text.substringBefore('?').substringBefore('#').endsWith(".zip", true)) {
            return Source.ZipUrl(text)
        }
        FULL_RE.find(text)?.let { m ->
            val rest = text.substring(m.range.last + 1).trim('/')
            val branch = rest.substringAfter("tree/", "").substringBefore('/').ifBlank { null }
            return Source.GitHub(m.groupValues[1], m.groupValues[2].removeSuffix(".git"), branch)
        }
        SHORT_RE.find(text)?.let { m ->
            return Source.GitHub(m.groupValues[1], m.groupValues[2].removeSuffix(".git"))
        }
        if (httpish) return Source.ZipUrl(text)
        return null
    }

    /** GitHub 先试 URL 里点名的分支，再 main、master（不打 api.github.com，省掉限流）。 */
    fun downloadUrls(source: Source): List<String> = when (source) {
        is Source.ZipUrl -> listOf(source.url)
        is Source.GitHub -> (listOfNotNull(source.branch) + listOf("main", "master")).distinct()
            .map { "https://codeload.github.com/${source.owner}/${source.name}/zip/refs/heads/$it" }
    }

    /**
     * 解压到 [into]（目录必须已存在或会被创建）。
     *
     * 拒绝绝对路径与 `..` 段（zip slip），并限制条目数 / 单文件 / 解压总大小——
     * 输入是用户贴的链接，压缩包内容不可信。
     */
    fun extract(zip: File, into: File, limits: Limits = Limits()) {
        into.mkdirs()
        val root = into.canonicalFile
        var count = 0
        var total = 0L
        val buffer = ByteArray(8192)
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    count++
                    if (count > limits.maxEntries) {
                        throw SkillInstallException("压缩包条目太多（上限 ${limits.maxEntries} 个）")
                    }
                    val target = safeTarget(root, entry.name)
                        ?: throw SkillInstallException("压缩包里有非法路径：${entry.name}")
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    var written = 0L
                    target.outputStream().use { sink ->
                        while (true) {
                            val n = zin.read(buffer)
                            if (n <= 0) break
                            written += n
                            total += n
                            if (written > limits.maxEntryBytes) {
                                throw SkillInstallException("单个文件太大：${entry.name}")
                            }
                            if (total > limits.maxTotalBytes) {
                                throw SkillInstallException(
                                    "解压后总大小超过 ${limits.maxTotalBytes / 1024 / 1024} MB",
                                )
                            }
                            sink.write(buffer, 0, n)
                        }
                    }
                    target.setReadable(true, false)
                }
            }
        } catch (e: SkillInstallException) {
            throw e
        } catch (e: IOException) {
            throw SkillInstallException("压缩包读不出来：${e.message ?: e.toString()}")
        }
    }

    /** 规范化条目名并确认它落在 [root] 内；越界返回 null。 */
    private fun safeTarget(root: File, name: String): File? {
        val clean = name.replace('\\', '/').trim()
        if (clean.isEmpty()) return null
        if (clean.startsWith("/")) return null
        if (WINDOWS_ABSOLUTE_RE.containsMatchIn(clean)) return null
        if (clean.split('/').any { it == ".." }) return null
        val target = File(root, clean).canonicalFile
        val rootPath = root.canonicalPath
        return if (target.path == rootPath || target.path.startsWith(rootPath + File.separator)) target else null
    }

    /**
     * staging 里真正该交给技能管理器的目录。
     *
     * GitHub zipball 外面套一层 `repo-branch/`，单顶层目录时剥掉它（SKILL.md 就在那一层）；
     * 根上已经有 SKILL.md、或有多顶层目录（一个包里多个技能）就原样返回根，管理器会递归。
     */
    fun stageRoot(dir: File): File {
        if (File(dir, SKILL_MANIFEST).isFile) return dir
        val children = dir.listFiles().orEmpty()
        if (children.size == 1 && children[0].isDirectory) return children[0]
        return dir
    }

    /** 递归找 SKILL.md（技能可以放在子目录里，管理器会往下扫）。 */
    fun containsSkillManifest(root: File): Boolean {
        if (File(root, SKILL_MANIFEST).isFile) return true
        return root.walkTopDown().maxDepth(4).any { it.isFile && it.name == SKILL_MANIFEST }
    }

    /**
     * 下载 + 解压 + 定位，返回 staged 根目录。
     *
     * [fetch] 是下载实现，默认走 HTTP；测试注入假实现即可完全离线。
     * 失败时 staging 已经清掉，成功时由调用方在 install 结束后删（管理器已经复制走文件）。
     */
    fun install(cacheDir: File, input: String, fetch: (String, File) -> Unit = ::httpDownload): File {
        val source = parse(input) ?: throw SkillInstallException(
            "链接看不懂：${input.take(80)}；支持 https://github.com/owner/repo、owner/repo、" +
                "github.com/owner/repo/tree/分支，或 zip 直链",
        )
        cacheDir.mkdirs()
        val stamp = System.currentTimeMillis()
        val archive = File(cacheDir, "skill-$stamp.zip")
        val stage = File(cacheDir, "skill-stage-$stamp")
        try {
            var last: Throwable? = null
            for (url in downloadUrls(source)) {
                val attempt = runCatching { fetch(url, archive) }
                if (attempt.isSuccess) {
                    last = null
                    break
                }
                last = attempt.exceptionOrNull()
            }
            if (last != null) {
                if (last is SkillInstallException) throw last
                throw SkillInstallException("下载失败：${last.message ?: last.toString()}")
            }
            if (!archive.isFile || archive.length() == 0L) throw SkillInstallException("下载到的包是空的")
            stage.mkdirs()
            extract(archive, stage)
            val root = stageRoot(stage)
            if (!containsSkillManifest(root)) {
                throw SkillInstallException("压缩包里没有 SKILL.md：技能仓库里要有 SKILL.md（根目录或子目录都行）")
            }
            return root
        } catch (t: Throwable) {
            stage.deleteRecursively()
            throw t
        } finally {
            archive.delete()
        }
    }

    /** 默认下载实现：跟随重定向、带 UA、20 MB 上限。 */
    private fun httpDownload(url: String, out: File) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/zip,application/octet-stream,*/*")
                setRequestProperty("User-Agent", "dsh-mobile-skill-installer")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw SkillInstallException("HTTP $code")
            var total = 0L
            val buffer = ByteArray(8192)
            out.outputStream().use { sink ->
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_DOWNLOAD_BYTES) {
                            throw SkillInstallException("包太大（上限 ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB）")
                        }
                        sink.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: SkillInstallException) {
            throw e
        } catch (e: IOException) {
            throw SkillInstallException("下载失败：${e.message ?: e.toString()}")
        } finally {
            conn?.disconnect()
        }
    }
}
